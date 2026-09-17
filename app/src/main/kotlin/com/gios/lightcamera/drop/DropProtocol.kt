package com.gios.lightcamera.drop

/**
 * HTTP, reduced to the part that can be proved without a socket.
 *
 * **The reason this file exists at all is the same reason `Captures` exists.** A server is the
 * kind of code that fails without crashing: a `Range` header parsed one byte out plays the first
 * second of a clip and then stalls, a query string split on the wrong character serves the whole
 * roll to somebody who typed the wrong PIN, and neither shows up as an exception in a log. All of
 * it is string handling, and string handling is exactly what a JVM test can hold still. So the
 * parsing, the routing and the escaping live here with no Android and no `java.net` in sight, and
 * [WifiDrop] is left holding only the sockets and the content resolver — the parts a unit test
 * could not reach anyway.
 *
 * Deliberately not a general HTTP implementation. It speaks the subset one browser needs to draw
 * a grid of photographs and scrub a video: `GET`, a single `POST` carrying a form, byte ranges,
 * and cookies. Anything else is a 404 rather than a best guess.
 */
object DropProtocol {

    /** What one connection asked for, once the head has been read off the wire. */
    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String = "",
    ) {
        /** Header lookup is case-insensitive per RFC 9110; [parseHead] lowercases the keys. */
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /**
     * Where a request is going.
     *
     * **An id, never a path.** `Thumb` and `File` carry the MediaStore row id and nothing else,
     * and [WifiDrop] resolves it by looking it up in the list it is already serving. There is no
     * point anywhere in this server where a string off the wire becomes part of a filename, which
     * is what makes `GET /file/../../../etc/passwd` a 404 by construction rather than by a check
     * somebody has to remember to write.
     */
    sealed interface Route {
        /** The page itself. */
        data object Page : Route

        /** The PIN form's target. */
        data object Unlock : Route

        /** Everything the roll is showing, as JSON. */
        data object Items : Route

        /** A small JPEG for the grid. */
        data class Thumb(val id: Long) : Route

        /** The original bytes, with ranges honoured. */
        data class File(val id: Long) : Route

        data object NotFound : Route
    }

    /**
     * Read a request head.
     *
     * Returns null for anything that is not a request line followed by headers, which covers a
     * connection that opened and said nothing, a TLS handshake arriving at a plaintext port, and
     * a scanner throwing bytes at it. The caller answers those by hanging up.
     */
    fun parseHead(head: String): Request? {
        val lines = head.split("\r\n", "\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val (path, query) = splitTarget(parts[1])
        val headers = HashMap<String, String>(lines.size)
        lines.drop(1).forEach { line ->
            val at = line.indexOf(':')
            if (at <= 0) return@forEach
            headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
        }
        return Request(method = method, path = path, query = query, headers = headers)
    }

    /** `/file/42?dl=1` -> `/file/42` and `{dl=1}`. */
    fun splitTarget(target: String): Pair<String, Map<String, String>> {
        val at = target.indexOf('?')
        if (at < 0) return target to emptyMap()
        return target.substring(0, at) to parseForm(target.substring(at + 1))
    }

    /**
     * `a=1&b=hello+world` -> `{a=1, b=hello world}`.
     *
     * Shared by the query string and the one form this server accepts, because they are the same
     * encoding and having two of them would mean one of them was wrong.
     */
    fun parseForm(encoded: String): Map<String, String> {
        if (encoded.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        encoded.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val at = pair.indexOf('=')
            val key = if (at < 0) pair else pair.substring(0, at)
            val value = if (at < 0) "" else pair.substring(at + 1)
            out[percentDecode(key)] = percentDecode(value)
        }
        return out
    }

    /**
     * Percent-decoding by hand rather than `URLDecoder`.
     *
     * `URLDecoder.decode` throws on a trailing `%` and on `%zz`, and a browser is not the only
     * thing that will ever connect to this port. A malformed escape is left as the literal
     * characters it already is, which cannot be right but also cannot take the connection down.
     */
    fun percentDecode(text: String): String {
        if ('%' !in text && '+' !in text) return text
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '+' -> {
                    bytes.write(' '.code)
                    i++
                }
                c == '%' && i + 2 < text.length -> {
                    val hex = text.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                    if (value == null) {
                        bytes.write(c.code)
                        i++
                    } else {
                        bytes.write(value)
                        i += 3
                    }
                }
                else -> {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
        }
        return bytes.toString("UTF-8")
    }

    /** The value of one cookie out of a `Cookie:` header, or null. */
    fun cookie(header: String?, name: String): String? {
        if (header.isNullOrEmpty()) return null
        header.split(';').forEach { pair ->
            val at = pair.indexOf('=')
            if (at <= 0) return@forEach
            if (pair.substring(0, at).trim() == name) return pair.substring(at + 1).trim()
        }
        return null
    }

    /** Which handler a path belongs to. Unknown paths are [Route.NotFound], never a guess. */
    fun route(path: String): Route = when {
        path == "/" || path == "/index.html" -> Route.Page
        path == "/unlock" -> Route.Unlock
        path == "/api/items" -> Route.Items
        path.startsWith("/thumb/") -> path.removePrefix("/thumb/").toLongOrNull()
            ?.let { Route.Thumb(it) } ?: Route.NotFound
        path.startsWith("/file/") -> path.removePrefix("/file/").toLongOrNull()
            ?.let { Route.File(it) } ?: Route.NotFound
        else -> Route.NotFound
    }

    /** How much of a file this request wants. */
    sealed interface Span {
        /** No `Range` header, or one this server declines to honour. Send all of it, 200. */
        data object Whole : Span

        /** Inclusive, both ends, the way HTTP counts. 206. */
        data class Part(val from: Long, val to: Long) : Span {
            val length: Long get() = to - from + 1
        }

        /** A range that cannot be met — past the end of the file. 416, and say how long it is. */
        data object Unsatisfiable : Span
    }

    /**
     * Parse a `Range` header against a known length.
     *
     * **A video element will not scrub without this, and on some browsers will not play at all.**
     * Safari asks for `bytes=0-1` first to find out whether the server honours ranges, and treats
     * a 200 carrying the whole file as a server that does not — after which the scrub bar is
     * decorative. So the three forms that matter are all here: `from-to`, `from-` for the rest of
     * the file, and `-suffix` for the last n bytes, which is how a player finds an MP4's `moov`
     * atom when it was written at the end.
     *
     * Multi-range requests (`bytes=0-50,100-150`) are answered [Whole] on purpose. They need a
     * multipart body, no browser asks for one while playing media, and a wrong implementation of
     * a thing nobody uses is worse than not having it.
     */
    fun span(header: String?, size: Long): Span {
        if (header.isNullOrBlank() || size <= 0L) return Span.Whole
        val spec = header.trim()
        if (!spec.startsWith("bytes=")) return Span.Whole
        val body = spec.removePrefix("bytes=").trim()
        if (',' in body) return Span.Whole
        val at = body.indexOf('-')
        if (at < 0) return Span.Whole
        val head = body.substring(0, at).trim()
        val tail = body.substring(at + 1).trim()
        return when {
            // "-500": the last 500 bytes. A suffix longer than the file is the whole file, which
            // is what the spec says to do rather than an error.
            head.isEmpty() -> {
                val want = tail.toLongOrNull() ?: return Span.Whole
                if (want <= 0L) return Span.Unsatisfiable
                Span.Part((size - want).coerceAtLeast(0L), size - 1)
            }
            else -> {
                val from = head.toLongOrNull() ?: return Span.Whole
                if (from >= size) return Span.Unsatisfiable
                val to = if (tail.isEmpty()) size - 1 else tail.toLongOrNull() ?: return Span.Whole
                if (to < from) return Span.Unsatisfiable
                Span.Part(from, to.coerceAtMost(size - 1))
            }
        }
    }

    /**
     * Compare two secrets without leaking how far along they matched.
     *
     * A session token is compared on every single request, and `==` on a `String` stops at the
     * first byte that differs. Over a LAN the timing difference is small and the token is 128
     * bits, so this is not the attack anybody would actually run — it is one line, and the line
     * where a cookie is checked is a bad place to be interesting.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * A JSON string literal, quotes and all.
     *
     * Hand-rolled because `org.json` is an Android stub on a JVM test classpath — every method on
     * it throws — so a server that used it could not have a single test of what it actually sends.
     * The escape set is the minimum JSON requires plus the two that bite in practice: a control
     * character in a filename, and `<` , which would otherwise let a photograph named
     * `</script>` close the tag it is being written into.
     */
    fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '<' -> out.append("\\u003c")
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    /** One object in the `/api/items` array. Assembled here so the shape is testable. */
    fun jsonItem(
        id: Long,
        name: String,
        takenAt: Long,
        width: Int,
        height: Int,
        isVideo: Boolean,
        durationMs: Long,
        formats: List<Pair<String, Long>>,
    ): String = buildString {
        append("{\"id\":").append(id)
        append(",\"name\":").append(jsonString(name))
        append(",\"takenAt\":").append(takenAt)
        append(",\"w\":").append(width)
        append(",\"h\":").append(height)
        append(",\"video\":").append(isVideo)
        append(",\"ms\":").append(durationMs)
        append(",\"formats\":[")
        formats.forEachIndexed { index, (label, fileId) ->
            if (index > 0) append(',')
            append("{\"label\":").append(jsonString(label)).append(",\"id\":").append(fileId).append('}')
        }
        append("]}")
    }

    /**
     * A filename safe to put in a `Content-Disposition`.
     *
     * A quote or a newline in that header is a response-splitting hole, and MediaStore will
     * happily hand back a display name containing either. Reduced to the characters a filename
     * needs rather than escaped, because the goal is a file that saves cleanly on three operating
     * systems, not a faithful copy of a hostile name.
     */
    fun safeFilename(name: String, fallback: String = "photo"): String {
        val cleaned = name.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim('_', '.')
        return cleaned.ifEmpty { fallback }.take(120)
    }
}
