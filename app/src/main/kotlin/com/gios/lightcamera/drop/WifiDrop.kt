package com.gios.lightcamera.drop

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import com.gios.lightcamera.media.CaptureGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The roll, on a web page, served off the phone over Wi-Fi.
 *
 * **Why this exists.** There is no easy way to get a video off a Light Phone. The share sheet
 * resolves to messaging apps, and MMS caps out around three megabytes — four seconds of clip.
 * USB works and is not easy: the phone's USB mode resets to charging on every unplug, a Mac needs
 * third-party software now that Android File Transfer is gone, and a photo importer speaks PTP,
 * which carries stills and not video, which is why a clip so often arrives on a computer as a
 * thumbnail nothing will open. So the phone serves the roll itself: open a URL on a laptop, type
 * four digits, and there is the grid — the same photographs, in a browser, with a download button.
 *
 * **It is off until you ask for it, and it is on a leash.** The socket opens when you choose a
 * computer in the send picker and closes when you press stop, when ten minutes pass with nobody
 * asking for anything, or when the process dies. While it is open a partial wake lock keeps the
 * processor from suspending underneath it, because a laptop halfway through downloading a clip is
 * not a phone that should go to sleep.
 *
 * **What it will not do.** It serves ids, never paths: every route resolves a MediaStore row id
 * against the list the roll is already showing, so there is no point at which a string from the
 * network becomes part of a filename. A photograph the roll is not showing cannot be requested,
 * a file outside MediaStore cannot be named, and `/file/../../etc/passwd` is a 404 because it is
 * not a number rather than because a check caught it. Nothing is written: there is no upload, no
 * delete, no rename. The whole surface is four reads.
 *
 * The parsing all lives in [DropProtocol], which has no Android in it and is unit tested. What is
 * left here is sockets, MediaStore and the lock.
 */
object WifiDrop {

    /** Everything the phone has to show you when the server is up. */
    data class Live(
        val host: String,
        val port: Int,
        /** Four digits, new every time the server starts. */
        val pin: String,
    ) {
        val url: String get() = "http://$host:$port"
    }

    private val _live = MutableStateFlow<Live?>(null)

    /** Null when nothing is listening. The screen and the send picker both read this. */
    val live: StateFlow<Live?> = _live.asStateFlow()

    /** Why a [start] refused, for the one screen that has to explain it. */
    sealed interface Start {
        data object Running : Start
        data class Refused(val why: String) : Start
    }

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var reaper: ScheduledExecutorService? = null
    private var lock: PowerManager.WakeLock? = null

    /** The roll, as the app currently has it. Read per request, so starring something shows up. */
    private var source: () -> List<CaptureGroup> = { emptyList() }
    private var app: Context? = null

    /** The session cookie's value. New every start, so stopping invalidates every open browser. */
    private var token: String = ""

    /** When something last asked for anything, against [IDLE_MS]. */
    private val lastSeen = AtomicLong(0L)

    /**
     * Wrong PINs since the last right one.
     *
     * Four digits is ten thousand guesses, which a script on the same network would walk in
     * seconds if nothing counted. After [MAX_TRIES] the unlock route stops answering until the
     * server is restarted — restarted, not timed out, because the person who owns the phone is
     * holding it and pressing stop and start is two taps, while an attacker has to wait for them
     * to do it.
     */
    private val wrongTries = AtomicInteger(0)

    /**
     * Small JPEGs for the grid, kept because a browser asks for every one of them at once.
     *
     * `loadThumbnail` goes to MediaStore, which on a cold row decodes the original — a hundred of
     * those arriving in parallel is the thing that would make this feel broken. Four megabytes is
     * roughly a screenful of grid at this size and is dropped with the server.
     */
    private val thumbs = object : LruCache<Long, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray): Int = value.size
    }

    /**
     * Open the socket.
     *
     * [source] is a function rather than a list because the roll changes while the server is up —
     * take a photograph on the phone and refreshing the page on the laptop should show it.
     */
    @Synchronized
    fun start(context: Context, source: () -> List<CaptureGroup>): Start {
        _live.value?.let { return Start.Running }
        val host = lanAddress()
            ?: return Start.Refused("This phone isn't on Wi-Fi. Join a network and try again.")
        val socket = openSocket()
            ?: return Start.Refused("Couldn't open a port on this phone.")

        val applicationContext = context.applicationContext
        this.app = applicationContext
        this.source = source
        val random = SecureRandom()
        token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val pin = (1..PIN_DIGITS).map { random.nextInt(10) }.joinToString("")
        wrongTries.set(0)
        thumbs.evictAll()
        touch()

        server = socket
        pool = Executors.newFixedThreadPool(WORKERS)
        hold(applicationContext)

        Thread({ accept(socket) }, "wifi-drop-accept").apply { isDaemon = true }.start()
        // The leash. Nothing asking for anything for ten minutes and the socket closes itself,
        // so a drop forgotten in a pocket is not a phone quietly serving its camera roll to a
        // hotel network all night.
        reaper = Executors.newSingleThreadScheduledExecutor().also { scheduled ->
            scheduled.scheduleWithFixedDelay(
                { if (System.currentTimeMillis() - lastSeen.get() > IDLE_MS) stop() },
                1,
                1,
                TimeUnit.MINUTES,
            )
        }

        _live.value = Live(host = host, port = socket.localPort, pin = pin)
        Log.i(TAG, "serving the roll on ${socket.localPort}")
        return Start.Running
    }

    /** Close it. Safe to call when nothing is running, which is what every teardown path does. */
    @Synchronized
    fun stop() {
        // Closing the server socket is what breaks the accept loop out of its blocking call —
        // there is no other way to interrupt one, and it throws rather than returning, which is
        // why that loop treats a throw as "we were asked to stop" rather than as a fault.
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        reaper?.shutdownNow()
        reaper = null
        thumbs.evictAll()
        // A stale token would otherwise let a browser that was open before straight back in the
        // next time the server started on the same port.
        token = ""
        source = { emptyList() }
        release()
        _live.value = null
    }

    /* ---------------- the socket ---------------- */

    private fun openSocket(): ServerSocket? {
        // A fixed port first, because the whole point is a URL somebody types off a phone screen
        // onto a laptop, and 8088 is four characters they do not have to read twice. Falling back
        // to whatever the system will give rather than refusing: a port already taken is not a
        // reason to have no answer at all.
        return runCatching { ServerSocket(PORT) }.getOrNull()
            ?: runCatching { ServerSocket(0) }.getOrNull()
    }

    /**
     * This phone's address on the network the laptop is also on.
     *
     * Enumerated rather than asked of `WifiManager`, which needs a permission Roll does not hold
     * and answers only for Wi-Fi — a phone tethering to a laptop over USB has a perfectly good
     * address on `rndis0` and no Wi-Fi at all. Loopback is not an address anybody else can reach,
     * and neither is a mobile data interface, so both are skipped; what is left is a site-local
     * IPv4, which is the shape of every home and office network.
     */
    private fun lanAddress(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull()
            .orEmpty()
        val usable = interfaces.filter { candidate ->
            runCatching { candidate.isUp && !candidate.isLoopback }.getOrDefault(false) &&
                // rmnet/ccmni are the carrier's. Serving a page to the cell network would at best
                // reach nothing and at worst reach something.
                CARRIER.none { candidate.name.startsWith(it) }
        }
        // Wi-Fi first when there is a choice, because that is the one the laptop is on.
        val ordered = usable.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        ordered.forEach { candidate ->
            candidate.inetAddresses.toList().forEach { address: InetAddress ->
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private fun accept(socket: ServerSocket) {
        while (true) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            val workers = pool ?: run {
                runCatching { client.close() }
                return
            }
            runCatching {
                workers.execute {
                    try {
                        serve(client)
                    } catch (t: Throwable) {
                        // One bad connection is one bad connection. A browser closing a tab
                        // mid-download throws here every time, and it is not news.
                        Log.d(TAG, "connection ended", t)
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        client.tcpNoDelay = true
        val input = client.getInputStream().buffered()
        val output = client.getOutputStream().buffered()
        val head = readHead(input) ?: return
        val parsed = DropProtocol.parseHead(head) ?: return
        val request = if (parsed.method == "POST") {
            val length = parsed.header("content-length")?.toIntOrNull() ?: 0
            parsed.copy(body = String(readExactly(input, length.coerceIn(0, MAX_BODY)), Charsets.UTF_8))
        } else {
            parsed
        }
        touch()
        handle(request, output)
        output.flush()
    }

    private fun handle(request: DropProtocol.Request, out: OutputStream) {
        val context = app ?: return
        val unlocked = DropProtocol.constantTimeEquals(
            DropProtocol.cookie(request.header("cookie"), COOKIE),
            token.ifEmpty { null },
        )
        when (val route = DropProtocol.route(request.path)) {
            DropProtocol.Route.Unlock -> unlock(request, out)

            // **The lock is checked once, here, above everything that reads anything.** A route
            // that forgot to ask would be the whole hole, so no route is in a position to forget.
            else -> if (!unlocked) {
                if (route == DropProtocol.Route.Page) page(context, out, locked = true)
                else send(out, "401 Unauthorized", "text/plain", "Locked".toByteArray())
            } else when (route) {
                DropProtocol.Route.Page -> page(context, out, locked = false)
                DropProtocol.Route.Items -> items(out)
                is DropProtocol.Route.Thumb -> thumb(context, route.id, out)
                is DropProtocol.Route.File -> file(context, route.id, request, out)
                else -> send(out, "404 Not Found", "text/plain", "No".toByteArray())
            }
        }
    }

    private fun unlock(request: DropProtocol.Request, out: OutputStream) {
        val live = _live.value
        if (live == null || wrongTries.get() >= MAX_TRIES) {
            send(out, "429 Too Many Requests", "text/plain", "Locked out. Restart it on the phone.".toByteArray())
            return
        }
        val offered = DropProtocol.parseForm(request.body)["pin"].orEmpty()
        if (!DropProtocol.constantTimeEquals(offered, live.pin)) {
            wrongTries.incrementAndGet()
            send(out, "303 See Other", "text/plain", ByteArray(0), listOf("Location: /?bad=1"))
            return
        }
        wrongTries.set(0)
        send(
            out,
            "303 See Other",
            "text/plain",
            ByteArray(0),
            listOf(
                // HttpOnly so a script cannot read it, SameSite=Strict so another page cannot
                // ride it. Session-scoped — no Max-Age — so closing the browser ends it too.
                "Set-Cookie: $COOKIE=$token; Path=/; HttpOnly; SameSite=Strict",
                "Location: /",
            ),
        )
    }

    private fun page(context: Context, out: OutputStream, locked: Boolean) {
        val asset = if (locked) "drop-pin.html" else "drop.html"
        val body = runCatching { context.assets.open(asset).use(InputStream::readBytes) }
            .getOrElse { "Roll".toByteArray() }
        send(out, "200 OK", "text/html; charset=utf-8", body)
    }

    private fun items(out: OutputStream) {
        val body = buildString {
            append('[')
            source().forEachIndexed { index, group ->
                val photo = group.primary.photo
                if (index > 0) append(',')
                append(
                    DropProtocol.jsonItem(
                        id = photo.id,
                        name = photo.name,
                        takenAt = photo.takenAt,
                        width = photo.width,
                        height = photo.height,
                        isVideo = photo.isVideo,
                        durationMs = photo.durationMs,
                        // Every file the one press wrote, so a laptop can take the negative as
                        // well as the print. This is the relationship the roll already knows and
                        // MediaStore has nowhere to keep — see `Captures`.
                        formats = group.members.map { member ->
                            (member.format?.label ?: "FILE") to member.photo.id
                        },
                    ),
                )
            }
            append(']')
        }.toByteArray(Charsets.UTF_8)
        send(out, "200 OK", "application/json; charset=utf-8", body, listOf("Cache-Control: no-store"))
    }

    private fun thumb(context: Context, id: Long, out: OutputStream) {
        val cached = thumbs.get(id)
        if (cached != null) {
            send(out, "200 OK", "image/jpeg", cached, listOf("Cache-Control: max-age=600"))
            return
        }
        val photo = source().firstOrNull { it.primary.photo.id == id }?.primary?.photo
            ?: return send(out, "404 Not Found", "text/plain", "No".toByteArray())
        val bytes = runCatching {
            val bitmap = context.contentResolver.loadThumbnail(photo.uri, Size(THUMB_PX, THUMB_PX), null)
            ByteArrayOutputStream(32 * 1024).also { buffer ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, buffer)
                bitmap.recycle()
            }.toByteArray()
        }.getOrNull() ?: return send(out, "404 Not Found", "text/plain", "No thumbnail".toByteArray())
        thumbs.put(id, bytes)
        send(out, "200 OK", "image/jpeg", bytes, listOf("Cache-Control: max-age=600"))
    }

    /**
     * The original bytes.
     *
     * **Ranges are honoured here or video does not work.** A `<video>` element does not download a
     * file and play it; it asks for a couple of kilobytes, finds the index, and then asks for the
     * part it wants — and a server that answers every one of those with the whole file gives you a
     * clip that plays from the start and cannot be scrubbed. See [DropProtocol.span].
     */
    private fun file(context: Context, id: Long, request: DropProtocol.Request, out: OutputStream) {
        // Any member of any group, not only the primaries: this is the route the format buttons
        // use, so a RAW that the roll never draws is still downloadable.
        val photo = source().asSequence()
            .flatMap { it.members.asSequence() }
            .firstOrNull { it.photo.id == id }
            ?.photo
            ?: return send(out, "404 Not Found", "text/plain", "No".toByteArray())

        val resolver = context.contentResolver
        val type = resolver.getType(photo.uri) ?: "application/octet-stream"
        val size = runCatching {
            resolver.query(photo.uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
                ?: -1L
        }.getOrDefault(-1L)

        val download = request.query["dl"] == "1"
        val disposition = if (download) {
            "Content-Disposition: attachment; filename=\"${DropProtocol.safeFilename(photo.name)}\""
        } else {
            "Content-Disposition: inline; filename=\"${DropProtocol.safeFilename(photo.name)}\""
        }

        when (val span = DropProtocol.span(request.header("range"), size)) {
            DropProtocol.Span.Unsatisfiable -> send(
                out,
                "416 Range Not Satisfiable",
                "text/plain",
                ByteArray(0),
                listOf("Content-Range: bytes */$size"),
            )

            DropProtocol.Span.Whole -> {
                head(
                    out,
                    "200 OK",
                    type,
                    if (size >= 0) size else null,
                    listOf("Accept-Ranges: bytes", disposition),
                )
                resolver.openInputStream(photo.uri)?.use { stream -> stream.copyTo(out, COPY_BUFFER) }
            }

            is DropProtocol.Span.Part -> {
                head(
                    out,
                    "206 Partial Content",
                    type,
                    span.length,
                    listOf(
                        "Accept-Ranges: bytes",
                        "Content-Range: bytes ${span.from}-${span.to}/$size",
                        disposition,
                    ),
                )
                // Seeking with a file descriptor rather than skipping an input stream: `skip` on a
                // content stream is allowed to move less than asked and to do it a buffer at a
                // time, which for a seek near the end of a video is the whole file read to reach
                // the last megabyte of it.
                runCatching {
                    resolver.openFileDescriptor(photo.uri, "r")?.use { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { stream ->
                            stream.channel.position(span.from)
                            copyExactly(stream, out, span.length)
                        }
                    }
                }.onFailure { Log.w(TAG, "range read failed", it) }
            }
        }
    }

    /* ---------------- wire ---------------- */

    private fun head(
        out: OutputStream,
        status: String,
        type: String,
        length: Long?,
        extra: List<String> = emptyList(),
    ) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            if (length != null) append("Content-Length: ").append(length).append("\r\n")
            // **Every response closes the connection.** Keep-alive would mean framing every body
            // exactly right or hanging the browser, and a browser opens six connections anyway.
            // The grid is a hundred small requests over a LAN; the handshake is not the cost.
            append("Connection: close\r\n")
            // Nothing here is for anybody else's page to embed or read.
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(
        out: OutputStream,
        status: String,
        type: String,
        body: ByteArray,
        extra: List<String> = emptyList(),
    ) {
        head(out, status, type, body.size.toLong(), extra)
        if (body.isNotEmpty()) out.write(body)
    }

    /** Read until the blank line that ends a request head, or give up. */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(1024)
        var last = 0
        while (buffer.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            last = (last shl 8) or b
            // CRLFCRLF, or LFLF from something hand-rolled.
            if (last == 0x0D0A0D0A || (last and 0xFFFF) == 0x0A0A) {
                return buffer.toString("ISO-8859-1")
            }
        }
        return null
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun copyExactly(from: InputStream, to: OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var left = length
        while (left > 0) {
            val n = from.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) return
            to.write(buffer, 0, n)
            left -= n
        }
    }

    private fun touch() {
        lastSeen.set(System.currentTimeMillis())
    }

    /* ---------------- the lock ---------------- */

    private fun hold(context: Context) {
        if (lock?.isHeld == true) return
        lock = runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roll:drop").apply {
                setReferenceCounted(false)
                // The ceiling is the idle timeout with room to spare: if this class is ever wrong
                // about when the server stopped, the platform drops the lock rather than leaving
                // a phone that cannot sleep. Same reasoning as `SaveLock`.
                acquire(IDLE_MS + 5 * 60 * 1000L)
            }
        }.onFailure { Log.w(TAG, "no wake lock; the drop will pause with the screen off", it) }
            .getOrNull()
    }

    private fun release() {
        val held = lock ?: return
        if (held.isHeld) runCatching { held.release() }
        lock = null
    }

    private const val TAG = "WifiDrop"
    private const val PORT = 8088
    private const val WORKERS = 4
    private const val PIN_DIGITS = 4
    private const val MAX_TRIES = 5
    private const val THUMB_PX = 400
    private const val COPY_BUFFER = 64 * 1024
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_BODY = 4 * 1024
    private const val READ_TIMEOUT_MS = 20_000
    private const val COOKIE = "roll_drop"

    /**
     * Interface name prefixes that belong to the carrier, not to the network the laptop is on.
     *
     * `rmnet` and `ccmni` are the mobile data interfaces on Qualcomm and MediaTek respectively,
     * `pdp` is the older name for the same thing, and `clat` is the 464XLAT shim sitting on top
     * of one. An address on any of them is reachable from the carrier's network and from nothing
     * a person is sitting in front of, so serving a page there would at best reach no one.
     */
    private val CARRIER = listOf("rmnet", "ccmni", "pdp", "clat")

    /** Ten minutes with nobody asking for anything and the socket closes itself. */
    private const val IDLE_MS = 10 * 60 * 1000L
}
