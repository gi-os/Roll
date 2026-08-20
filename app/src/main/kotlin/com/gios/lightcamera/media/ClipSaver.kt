package com.gios.lightcamera.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * What a queued clip is called, and how the shot time is read back out of the name.
 *
 * **Separate, and free of Android imports, because the name is the only record of when a take
 * began.** The file's own `lastModified` is the moment it *ended*, and the clock at the moment it
 * is copied could be a launch later — so if this pair stops round-tripping, clips arrive in the
 * gallery dated wrongly and nothing else in the app notices. That is a thing to pin with a test on
 * the JVM rather than to find on a phone.
 */
object ClipNames {

    /** Seconds, matching the stamp [MediaStoreRepo.save] puts on a still. */
    const val STAMP = "yyyyMMdd_HHmmss"

    /** `ROLL_<yyyyMMdd_HHmmss>`, with whatever [nameFor] added to make it unique. */
    private val PATTERN = Regex("""^ROLL_(\d{8}_\d{6})""")

    /**
     * The name a take started at [millis] is recorded and saved under.
     *
     * [n] is 1 for the first clip of that second and rises from there: stop and immediately record
     * again and both takes stamp the same second, which is exactly the thing the queue exists to
     * allow. The suffix lands on the gallery name too; MediaProvider would have appended one of its
     * own otherwise.
     */
    fun nameFor(millis: Long, n: Int = 1): String {
        val stamp = SimpleDateFormat(STAMP, Locale.US).format(Date(millis))
        return if (n <= 1) "ROLL_$stamp.mp4" else "ROLL_${stamp}_$n.mp4"
    }

    /**
     * The shot time out of a clip's file name, or null if it isn't one of ours.
     *
     * Lenient on purpose: a name this does not recognise is not a reason to refuse to save the
     * clip, only a reason to fall back to a worse timestamp.
     */
    fun stampOf(name: String): Long? {
        val match = PATTERN.find(name) ?: return null
        return runCatching {
            SimpleDateFormat(STAMP, Locale.US).parse(match.groupValues[1])?.time
        }.getOrNull()
    }
}

/**
 * The queue between the recorder and the camera roll.
 *
 * **A recording used to be written straight into MediaStore, and stopping one was the most
 * expensive thing this app could do.** `MediaStoreOutputOptions` hands the muxer a descriptor on a
 * path MediaProvider owns, and since Android 11 that path is served through its FUSE daemon — so
 * every write the encoder made for the length of the take went out through another process instead
 * of straight to the filesystem, which an app's own directory does not. Then the stop wrote the
 * moov atom over that same descriptor and cleared `IS_PENDING`, and clearing it is what makes
 * MediaProvider scan the file: parse the container for its duration and resolution, and build a
 * thumbnail. All of it inside the `update` call, with the camera session still live and the
 * recorder calling back onto the main thread.
 *
 * So the recorder writes to a plain file this app owns, and getting it into the gallery is a
 * separate job that happens afterwards. Stopping a take is now a local muxer closing a local file:
 * nothing to wait for, and the next recording starts into the next file while the last one is
 * still being copied.
 *
 * Deliberately **one at a time**. Two clips copying at once is two readers and two writers on the
 * same flash, which is slower in total than doing them in order, and the point of the queue is to
 * keep the load off the camera rather than to finish sooner.
 *
 * ### Surviving the app going away
 *
 * The scope here belongs to the process, not to a view model or an activity — a copy has to keep
 * going when you leave the viewfinder, and a `viewModelScope` would cancel it half written. That
 * is as far as an app can get without a foreground service, which for a copy measured in seconds
 * would be a notification and a permission for nothing.
 *
 * What covers the rest is [sweep]. A clip nobody has saved yet is a file in a directory, so a
 * process the system killed mid-copy leaves the work sitting there to be picked up the next time
 * the camera opens. That is strictly more than a background assertion can promise, since no
 * assertion survives the process dying.
 *
 * `noBackupFilesDir` rather than `cacheDir`: the system may clear a cache directory whenever it
 * wants the space, and the file in here is a video the user has just shot. Rather than the
 * ordinary `filesDir` because a queued clip is transient by definition and has no business in a
 * cloud backup.
 */
class ClipSaver private constructor(context: Context) {

    private val repo = MediaStoreRepo(context)

    private val dir = File(context.noBackupFilesDir, CLIP_DIR)

    /**
     * Process-lifetime, and `SupervisorJob` so one clip that throws does not take the consumer
     * down with it and strand every clip queued behind it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Unbounded: a send must never suspend, because the sender is a camera callback. */
    private val queue = Channel<File>(Channel.UNLIMITED)

    /**
     * Every clip this queue has taken responsibility for and not finished with — recording into,
     * waiting, or being copied.
     *
     * **The thing that makes [sweep] safe to call more than once.** A sweep is the whole directory
     * offered to the queue, and the camera opening twice in one process would otherwise offer a
     * clip that is at that moment half way into MediaStore — two rows in the gallery for one take,
     * one of them truncated. The names in here are the ones already accounted for.
     */
    private val claimed = Collections.synchronizedSet(mutableSetOf<String>())

    private val _saving = MutableStateFlow(0)

    /** How many clips are waiting or being written. The viewfinder shows this, and nothing waits on it. */
    val saving: StateFlow<Int> = _saving.asStateFlow()

    private val _failed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Fires once per clip that could not be saved, so the viewfinder can say so. */
    val failed: SharedFlow<Unit> = _failed.asSharedFlow()

    init {
        scope.launch {
            for (file in queue) {
                val uri = runCatching { repo.saveClip(file) }
                    .onFailure { Log.e(TAG, "save threw for ${file.name}", it) }
                    .getOrNull()
                if (uri != null) {
                    // Only after the row is out of IS_PENDING. A temp file deleted on a failed
                    // copy is a clip lost for good; one left behind is picked up by the next sweep.
                    runCatching { file.delete() }
                    claimed.remove(file.name)
                } else {
                    Log.e(TAG, "couldn't save ${file.name}")
                    // Left on disk and left claimed: claimed so this run does not retry it in a
                    // loop, on disk so the next launch sweeps it and tries once more.
                    _failed.tryEmit(Unit)
                }
                _saving.value = (_saving.value - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Where the next take is recorded. A plain file, so the muxer never touches MediaStore.
     *
     * The name is the one the clip will carry in the gallery, and it is where its timestamp comes
     * from as well — see [ClipNames]. Uniquified against both the directory and the clips this run
     * is already dealing with, so a second take in the same second cannot be recorded over the
     * first one while it waits in the queue.
     */
    fun newClip(): File {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        var n = 1
        var file = File(dir, ClipNames.nameFor(now))
        while (file.exists() || claimed.contains(file.name)) {
            n++
            file = File(dir, ClipNames.nameFor(now, n))
        }
        claimed.add(file.name)
        return file
    }

    /**
     * Hand a finished take over. Returns immediately; the copy happens on the queue.
     *
     * Called from the recorder's finalize callback, which is on the main thread — so the only work
     * here is two stats on a file that was being written a moment ago and a channel send that
     * cannot block. The flag the camera waits on is already down by the time this runs.
     */
    fun enqueue(file: File) {
        if (!file.isFile || file.length() == 0L) {
            discard(file)
            return
        }
        claimed.add(file.name)
        _saving.value += 1
        // UNLIMITED, so this cannot fail — but a dropped send would be a lost clip, so it is
        // checked rather than assumed.
        if (queue.trySend(file).isFailure) {
            _saving.value = (_saving.value - 1).coerceAtLeast(0)
            Log.e(TAG, "queue refused ${file.name}")
            _failed.tryEmit(Unit)
        }
    }

    /** A take that produced nothing usable. Nothing to save, and nothing to leave behind. */
    fun discard(file: File) {
        runCatching { file.delete() }
        claimed.remove(file.name)
    }

    /**
     * Pick up anything a previous run left behind.
     *
     * Called when the camera opens. A file in here is by definition a clip that was recorded and
     * not yet saved — the consumer deletes each one as it lands in the gallery — so the whole
     * directory is the backlog, minus whatever this run is already dealing with.
     *
     * Returns immediately and does the listing on [scope]. Reading a directory is a syscall and
     * this is called while the view model is being constructed, which is the last place on this
     * phone that should be waiting on the disk — the viewfinder is not up yet.
     */
    fun sweep() {
        scope.launch {
            val leftovers = runCatching { dir.listFiles() }.getOrNull() ?: return@launch
            for (file in leftovers) {
                if (claimed.contains(file.name)) continue
                if (!file.isFile || file.length() == 0L) {
                    runCatching { file.delete() }
                    continue
                }
                enqueue(file)
            }
        }
    }

    companion object {

        private const val CLIP_DIR = "clips"

        private const val TAG = "ClipSaver"

        @Volatile private var instance: ClipSaver? = null

        /**
         * The one queue in the process.
         *
         * A singleton rather than a field on [com.gios.lightcamera.RollApp], which exists only to
         * install the crash log and says so: built on first use, which is when the camera opens,
         * rather than adding eager work in front of the viewfinder.
         */
        fun of(context: Context): ClipSaver =
            instance ?: synchronized(this) {
                instance ?: ClipSaver(context.applicationContext).also { instance = it }
            }
    }
}
