package com.gios.lightcamera.roll

import android.content.Context
import android.util.Log
import com.gios.lightcamera.media.MediaStoreRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** One exposure on a loaded roll. Not in the gallery, not visible, not yet a photograph. */
data class Exposure(
    val index: Int,
    val file: File,
    val takenAt: Long,
    val filterId: String,
    val width: Int,
    val height: Int,
    /** True for a frame the index had lost and recovery adopted. See [RollIndex]. */
    val recovered: Boolean = false,
)

/** A roll of film, part way through. */
data class Roll(
    val number: Int,
    val length: Int,
    val startedAt: Long,
    val exposures: List<Exposure>,
) {
    val shot: Int get() = exposures.size
    val remaining: Int get() = (length - shot).coerceAtLeast(0)
    val finished: Boolean get() = shot >= length
}

/**
 * Film-roll mode.
 *
 * A loaded roll takes the review out of taking photographs. Exposures go into app-private
 * storage, not the gallery; the shutter gives you a counter and a haptic click and nothing
 * else; and the frames only become photographs — files in `DCIM/Camera`, visible to every
 * other app — when the roll is developed, either because it ran out or because you chose to.
 *
 * The point isn't nostalgia. It's that checking the screen after every shot changes what you
 * photograph, and a roll makes that impossible for twenty-four frames at a time.
 *
 * Storage is a directory of JPEGs plus a plain-text index, one line per frame. No database:
 * the whole state is a dozen lines that has to survive a crash mid-roll and be readable by
 * eye when something has gone wrong. Each frame is written before the index line, so a
 * process death between the two loses the index entry and leaves an orphan file, which
 * [recover] adopts back onto the roll — the other order would leave an index pointing at a
 * file that isn't there, and there is no recovering from that.
 */
class FilmRoll(private val context: Context) {

    private val prefs = context.getSharedPreferences("roll", Context.MODE_PRIVATE)

    private val _roll = MutableStateFlow<Roll?>(null)
    val roll: StateFlow<Roll?> = _roll.asStateFlow()

    /** How many rolls have been developed. Only used to number the next one. */
    val developedCount: Int get() = prefs.getInt(KEY_DEVELOPED, 0)

    init {
        _roll.value = readLoadedRoll()
    }

    private fun rollsDir(): File = File(context.filesDir, "rolls").apply { mkdirs() }

    private fun dirFor(number: Int): File = File(rollsDir(), "roll-$number").apply { mkdirs() }

    private fun indexFile(number: Int): File = File(dirFor(number), "index.txt")

    /* ---------------- loading and unloading ---------------- */

    fun load(length: Int) {
        if (_roll.value != null) return
        val number = developedCount + 1
        val roll = Roll(number = number, length = length, startedAt = System.currentTimeMillis(), exposures = emptyList())
        prefs.edit()
            .putInt(KEY_CURRENT, number)
            .putInt(KEY_LENGTH, length)
            .putLong(KEY_STARTED, roll.startedAt)
            .apply()
        dirFor(number)
        _roll.value = roll
    }

    /** Throw the roll away unexposed. Asked for twice in the UI, for obvious reasons. */
    fun discard() {
        val roll = _roll.value ?: return
        runCatching { dirFor(roll.number).deleteRecursively() }
        clearLoaded()
        _roll.value = null
    }

    private fun clearLoaded() {
        prefs.edit().remove(KEY_CURRENT).remove(KEY_LENGTH).remove(KEY_STARTED).apply()
    }

    /* ---------------- exposing ---------------- */

    /**
     * Commit a frame to the roll. Returns the roll as it now stands, or null if there was
     * no roll loaded — the caller uses that to decide whether to save to the gallery instead.
     */
    suspend fun expose(
        jpeg: ByteArray,
        takenAt: Long,
        filterId: String,
        width: Int,
        height: Int,
    ): Roll? = withContext(Dispatchers.IO) {
        val current = _roll.value ?: return@withContext null
        if (current.finished) return@withContext current
        // One past the highest number on the roll, not the count plus one. The two differ only
        // after a recovery that dropped a line or adopted a frame, and then the count would name
        // a number that is already taken — and a frame written over another frame is the one
        // outcome recovery exists to prevent.
        val index = (current.exposures.maxOfOrNull { it.index } ?: 0) + 1
        val file = File(dirFor(current.number), frameName(index))
        val ok = runCatching { file.writeBytes(jpeg) }
            .onFailure { Log.e(TAG, "frame $index failed to write", it) }
            .isSuccess
        if (!ok) return@withContext current

        val exposure = Exposure(index, file, takenAt, filterId, width, height)
        runCatching {
            indexFile(current.number).appendText(RollIndex.formatLine(exposure.toEntry()) + "\n")
        }
        val updated = current.copy(exposures = current.exposures + exposure)
        _roll.value = updated
        updated
    }

    /* ---------------- developing ---------------- */

    /**
     * Write every frame into the camera roll, oldest first, and unload.
     *
     * Each frame keeps the time it was *taken*, so a roll shot over three weeks lands in the
     * gallery spread across three weeks rather than as a block of today. The files are only
     * removed once their `MediaStore` row exists, so a failure halfway leaves the rest of
     * the roll intact and developable again.
     */
    suspend fun develop(repo: MediaStoreRepo): DevelopedRoll = withContext(Dispatchers.IO) {
        val current = _roll.value ?: return@withContext DevelopedRoll(0, emptyList(), 0)
        var failed = 0
        val uris = ArrayList<android.net.Uri>(current.exposures.size)
        for (exposure in current.exposures.sortedBy { it.index }) {
            val bytes = runCatching { exposure.file.readBytes() }.getOrNull()
            if (bytes == null) {
                failed++
                continue
            }
            val uri = repo.save(
                jpeg = bytes,
                takenAt = exposure.takenAt,
                width = exposure.width,
                height = exposure.height,
                suffix = "R${current.number}F%02d".format(exposure.index),
            )
            if (uri == null) {
                failed++
            } else {
                uris += uri
                runCatching { exposure.file.delete() }
            }
        }
        if (failed == 0) {
            runCatching { dirFor(current.number).deleteRecursively() }
            prefs.edit().putInt(KEY_DEVELOPED, current.number).apply()
            clearLoaded()
            _roll.value = null
        } else {
            // Keep the roll loaded so the frames that survived can be tried again.
            _roll.value = current.copy(
                exposures = current.exposures.filter { it.file.exists() },
            )
        }
        DevelopedRoll(current.number, uris, failed)
    }

    class DevelopedRoll(val number: Int, val uris: List<android.net.Uri>, val failed: Int)

    /* ---------------- persistence ---------------- */

    private fun readLoadedRoll(): Roll? {
        val number = prefs.getInt(KEY_CURRENT, -1)
        if (number < 0) return null
        val length = prefs.getInt(KEY_LENGTH, 24)
        val started = prefs.getLong(KEY_STARTED, System.currentTimeMillis())
        val exposures = recover(number, readIndex(number))
        return Roll(number, length, started, exposures)
    }

    /** Every line that parses, whether or not its file is there. [recover] decides that. */
    private fun readIndex(number: Int): List<IndexEntry> {
        val file = indexFile(number)
        if (!file.exists()) return emptyList()
        return runCatching { file.readLines().mapNotNull(RollIndex::parseLine) }
            .getOrDefault(emptyList())
    }

    /**
     * Bring the index and the directory back into agreement, keeping every photograph.
     *
     * **These used to be deleted.** A frame the index does not know about is exactly what a
     * process death between the file write and the index line leaves behind, and for as long as
     * this app has had a film roll it swept those up as rubbish — a whole photograph, gone, with
     * the counter one short and nothing on screen to say so. The reasoning was that an unindexed
     * frame would develop out of order. It develops in the order it was written, which
     * [RollIndex.reconcile] recovers from the file's own clock; what it lacks is its filter and
     * its dimensions, and a photograph without those is still a photograph.
     *
     * What still goes: a zero-byte file, or one whose first bytes are not an image — the shape a
     * write leaves when the process dies *during* it rather than after. The index is rewritten
     * whenever the decision changed it, so the next launch reads the same roll this one did.
     */
    private fun recover(number: Int, known: List<IndexEntry>): List<Exposure> {
        val dir = dirFor(number)
        val onDisk = runCatching {
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name != RollIndex.INDEX_NAME }
                .map { file ->
                    DiskFrame(
                        name = file.name,
                        size = file.length(),
                        modifiedAt = file.lastModified(),
                        head = runCatching { readHead(file) }.getOrDefault(ByteArray(0)),
                    )
                }
        }.getOrDefault(emptyList())
        val decision = RollIndex.reconcile(known, onDisk)
        decision.delete.forEach { name ->
            Log.w(TAG, "discarding $name: not an image")
            runCatching { File(dir, name).delete() }
        }
        decision.adopted.forEach { Log.w(TAG, "recovered frame ${it.fileName} as ${it.index}") }
        if (decision.changed) writeIndex(number, decision.entries)
        return decision.entries.map { it.toExposure(dir) }
    }

    /** The first few bytes, enough for [RollIndex.looksLikeImage]. */
    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_BYTES)
        val read = input.read(buffer)
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    /**
     * Replace the index whole. Written beside and renamed over, so a death mid-write leaves the
     * old index rather than half of the new one.
     */
    private fun writeIndex(number: Int, entries: List<IndexEntry>) {
        runCatching {
            val target = indexFile(number)
            val staging = File(target.parentFile, "${target.name}.tmp")
            staging.writeText(entries.joinToString("") { RollIndex.formatLine(it) + "\n" })
            if (!staging.renameTo(target)) {
                // A rename that fails on the same directory is rare enough to fall back to a
                // plain write: the alternative is an index that still names deleted files.
                target.writeText(entries.joinToString("") { RollIndex.formatLine(it) + "\n" })
                staging.delete()
            }
        }.onFailure { Log.e(TAG, "index rewrite failed", it) }
    }

    private fun Exposure.toEntry(): IndexEntry =
        IndexEntry(index, takenAt, filterId, width, height, file.name, recovered)

    private fun IndexEntry.toExposure(dir: File): Exposure =
        Exposure(index, File(dir, fileName), takenAt, filterId, width, height, recovered)

    private fun frameName(index: Int): String = "frame-%02d.jpg".format(index)

    private companion object {
        const val TAG = "FilmRoll"
        const val KEY_CURRENT = "current"
        const val KEY_LENGTH = "length"
        const val KEY_STARTED = "started"
        const val KEY_DEVELOPED = "developed"

        /** Enough for a JPEG's SOI and a PNG's eight-byte signature. */
        const val HEAD_BYTES = 8
    }
}
