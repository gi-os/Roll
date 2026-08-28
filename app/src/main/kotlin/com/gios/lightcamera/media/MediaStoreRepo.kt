package com.gios.lightcamera.media

import android.content.ContentUris
import com.gios.lightcamera.camera.PuriStrip
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One photo in the roll. */
data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    /** Milliseconds. `DATE_TAKEN` where the file has it, `DATE_ADDED` otherwise. */
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val bucket: String?,
    /** True for a clip. The roll draws these with a duration badge and opens them in a player. */
    val isVideo: Boolean = false,
    /** Length of the clip in milliseconds, zero for a still. */
    val durationMs: Long = 0L,
)

/** `0:07`, `1:24`, `12:03`. Minutes and seconds is all a phone clip ever needs. */
fun Photo.durationLabel(): String {
    val total = (durationMs / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

/** Which photos the roll shows. */
enum class RollScope(val label: String) {
    /** Everything under DCIM — what any phone means by "camera roll". */
    Camera("Camera roll"),

    /** Every image on the device, screenshots and downloads included. */
    Everything("All photos"),

    /**
     * The ones you starred.
     *
     * Filtered in the view model rather than in the query, because MediaStore has nowhere to record that
     * you liked something — `IS_FAVORITE` exists but only the system gallery may write it. So the star
     * list lives in this app's own settings, keyed by file name, and the scope narrows whatever was
     * loaded.
     */
    Favourites("Starred"),

    /**
     * The roll, placed on a map.
     *
     * A scope rather than a mode: the same photographs are underneath, so a tap opens the viewer
     * exactly as the grid does. Only the ones carrying a coordinate appear, which is most of
     * nobody's roll until location has been on for a while — the screen says so rather than
     * showing an empty ocean.
     */
    Map("Map"),
}

/**
 * The device's photos, read and written through MediaStore.
 *
 * The roll above the viewfinder is deliberately **the real camera roll**, not a private
 * folder: a camera app with its own separate album is two galleries to look through, and
 * the whole point of putting the roll one swipe from the shutter is that it is the place
 * your photos are. So photos are written to `DCIM/Camera` where the stock camera writes
 * them, and read back from all of DCIM.
 *
 * No `WRITE_EXTERNAL_STORAGE` anywhere: since API 29 an app owns what it inserts, and
 * `IS_PENDING` is what keeps a half-written JPEG out of every other gallery on the phone
 * while the bytes are still going down.
 */
class MediaStoreRepo(private val context: Context) {

    private val collection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * The other half of the camera roll.
     *
     * **MediaStore keeps stills and clips in two separate tables, and for eleven versions this
     * app only ever asked the first one.** Every video the camera recorded was written correctly,
     * finalised correctly and was visible in every other gallery on the phone — and invisible
     * here, which read as a recorder that silently threw the take away. Nothing was ever lost;
     * the query simply never looked.
     */
    private val videoCollection: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )

    /**
     * Deliberately the same column *names* as the stills projection, in the same order.
     *
     * `MediaStore.Video.Media` and `MediaStore.Images.Media` both inherit `MediaColumns`, so
     * `_ID`, `DISPLAY_NAME`, `DATE_TAKEN`, `WIDTH` and the rest are the identical strings in both
     * tables. Keeping the order identical means one cursor reader serves both, and the only
     * genuinely video-specific column — `DURATION` — is appended on the end.
     */
    private val videoProjection = projection + arrayOf(MediaStore.Video.Media.DURATION)

    suspend fun load(scope: RollScope): List<Photo> = withContext(Dispatchers.IO) {
        // **The four frames of a strip are hidden from the roll.** They are saved, and you can open
        // them from the strip, but a booth hands you one print — four near-identical photographs of
        // the same three seconds filling a whole screen of the grid is not what you were looking for.
        // They live in their own folder for exactly this reason, and every query excludes it.
        val hide = "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ?"
        val selection = when (scope) {
            RollScope.Camera -> "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND $hide"
            // Starred photographs can be anywhere, so the query is the wide one and the narrowing
            // happens after.
            RollScope.Everything, RollScope.Favourites, RollScope.Map -> hide
        }
        val args = when (scope) {
            RollScope.Camera -> arrayOf("DCIM/%", "$STRIP_PATH%")
            RollScope.Everything, RollScope.Favourites, RollScope.Map -> arrayOf("$STRIP_PATH%")
        }
        // DATE_TAKEN is null for anything that isn't a photo with EXIF, so it can't be the
        // sort key on its own; COALESCE with DATE_ADDED, which is in seconds.
        val order = "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, " +
            "${MediaStore.Images.Media.DATE_ADDED} * 1000) DESC"

        val out = ArrayList<Photo>(256)
        out += read(collection, projection, selection, args, order, video = false)
        out += read(videoCollection, videoProjection, selection, args, order, video = true)
        // **Merged here rather than by the database, because there are two databases.** Each table
        // came back sorted; a roll interleaves them, so the combined list is sorted once more in
        // memory. Newest first, the same key the queries used, so a clip and a still taken in the
        // same second land next to each other rather than in two blocks.
        out.sortByDescending { it.takenAt }
        out
    }

    /**
     * One cursor read, shared by the stills table and the video table.
     *
     * Both projections start with the same seven columns in the same order, so the only thing that
     * differs is whether there is a `DURATION` on the end — which is what [video] decides.
     */
    private fun read(
        from: Uri,
        columns: Array<String>,
        selection: String,
        args: Array<String>,
        order: String,
        video: Boolean,
    ): List<Photo> {
        val out = ArrayList<Photo>(128)
        runCatching {
            context.contentResolver.query(from, columns, selection, args, order)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val wCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val hCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    val bucketCol =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    // Not `getColumnIndexOrThrow`: a stills cursor has no duration and asking for
                    // one would take the whole roll down rather than the badge on one cell.
                    val durCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val taken = if (cursor.isNull(takenCol)) {
                            cursor.getLong(addedCol) * 1000L
                        } else {
                            cursor.getLong(takenCol)
                        }
                        out += Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(from, id),
                            name = cursor.getString(nameCol) ?: "",
                            takenAt = taken,
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol),
                            bucket = cursor.getString(bucketCol),
                            isVideo = video,
                            durationMs = if (durCol >= 0 && !cursor.isNull(durCol)) {
                                cursor.getLong(durCol)
                            } else {
                                0L
                            },
                        )
                    }
                }
        }.onFailure { Log.e(TAG, "query failed for $from", it) }
        return out
    }

    /**
     * Replace the bytes of a photograph this app wrote.
     *
     * For the date stamp in Simple: the untouched JPEG is saved the instant the shutter returns, and the
     * date is printed on afterwards, off the main thread, while you are already framing the next shot. The
     * row keeps its id and its timestamp, so nothing that was looking at it has to look again.
     */
    suspend fun rewrite(uri: Uri, jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // "wt" truncates. Without the t a shorter JPEG leaves the tail of the old one behind, which
            // decodes as a perfectly valid photograph with rubbish at the bottom.
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(jpeg) }
                ?: error("no stream")
        }.onFailure { Log.e(TAG, "rewrite failed", it) }.isSuccess
    }

    /**
     * The four frames behind a strip, oldest first.
     *
     * Matched by name rather than by any stored relationship, because there is nowhere in MediaStore
     * to store one: the strip is `ROLL_<stamp>_strip.jpg` and its frames are `ROLL_<stamp>_1.jpg` to
     * `_4.jpg`, so the stamp is the link. Same second, same booth visit.
     */
    suspend fun framesOf(stripName: String): List<Photo> = withContext(Dispatchers.IO) {
        val stem = stripName.removeSuffix(".jpg").removeSuffix("_strip")
        val out = ArrayList<Photo>(PuriStrip.SHOTS)
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("$STRIP_PATH%", "$stem%"),
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val bucketCol =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    out += Photo(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "",
                        takenAt = if (cursor.isNull(takenCol)) {
                            cursor.getLong(addedCol) * 1000L
                        } else {
                            cursor.getLong(takenCol)
                        },
                        width = cursor.getInt(wCol),
                        height = cursor.getInt(hCol),
                        bucket = cursor.getString(bucketCol),
                    )
                }
            }
        }.onFailure { Log.e(TAG, "frames query failed", it) }
        out
    }

    /**
     * The name one press writes under, milliseconds and all.
     *
     * **The milliseconds are the group key, not decoration.** Every file from one press shares this
     * stem, and that shared name is the only record anywhere that they are one photograph —
     * MediaStore has no field for the relationship, which is the same reason [framesOf] matches a
     * booth strip to its frames by name. At second resolution two presses inside one second would
     * write the same stem and be read back as a single photograph with two of everything.
     */
    fun stemFor(takenAt: Long): String =
        "ROLL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(takenAt))

    /**
     * The row a file is described by, before anything is written into it.
     *
     * Shared by [save], which writes the bytes itself, and by the negative, which does not — there
     * CameraX is handed these values and does the insert, because it is the only thing that can
     * build a DNG. **One function so the two paths cannot drift**: a DNG landing in a different
     * folder, or with a different `DATE_TAKEN`, would sort away from the JPEG it was taken with and
     * break the group they are supposed to form.
     */
    fun valuesFor(
        takenAt: Long,
        format: CaptureFormat,
        stem: String,
        suffix: String? = null,
        hidden: Boolean = false,
    ): ContentValues = ContentValues().apply {
        val tail = suffix?.let { "_$it" } ?: ""
        put(MediaStore.Images.Media.DISPLAY_NAME, "$stem$tail.${format.extension}")
        put(MediaStore.Images.Media.MIME_TYPE, format.mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, if (hidden) STRIP_PATH else "DCIM/Camera")
        put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
        put(MediaStore.Images.Media.DATE_ADDED, takenAt / 1000)
        put(MediaStore.Images.Media.DATE_MODIFIED, takenAt / 1000)
    }

    /** The stills table these rows go into. Public so the negative can be aimed at the same one. */
    fun imagesCollection(): Uri = collection

    /**
     * Write a file by streaming into it, for content that must never exist as one heap buffer.
     *
     * The lossless copy is the customer: a 12MP PNG is 20-35MB, and the byte-array route held
     * that beside the bitmap being encoded — an allocation a 128MB heap refuses often enough
     * that "Lossless copy failed" arrived once per photograph of a burst. [write] runs against
     * the opened stream; a false return or a throw deletes the half-written row, so a failure
     * leaves nothing pending in anyone's gallery.
     */
    fun saveStreaming(
        takenAt: Long,
        format: CaptureFormat,
        stem: String,
        write: (java.io.OutputStream) -> Boolean,
    ): Uri? {
        val values = valuesFor(takenAt, format, stem).apply {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use(write) ?: false
        }.onFailure { Log.e(TAG, "streaming write failed", it) }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    /**
     * Write a JPEG into the camera roll.
     *
     * [takenAt] is passed in rather than read from the clock because a developed roll has to
     * carry the time each frame was *shot*, months ago if that's what happened, or the roll
     * arrives in the gallery as a block of today.
     */
    suspend fun save(
        jpeg: ByteArray,
        takenAt: Long,
        width: Int,
        height: Int,
        suffix: String? = null,
        /** True for one of the four frames behind a strip: saved, but kept out of the roll. */
        hidden: Boolean = false,
        /**
         * Which format these bytes are.
         *
         * Only ever changes the name and the MIME type — the bytes arrive already encoded, because
         * the encoder needs the bitmap and the bitmap belongs to [com.gios.lightcamera.camera.Frames].
         */
        format: CaptureFormat = CaptureFormat.Jpeg,
        /**
         * The name every file from one press shares, minus its extension.
         *
         * Passed in rather than derived from [takenAt] so that a JPEG and its lossless twin cannot
         * land in different groups because the clock ticked between two `save` calls. Null means
         * "one file, on its own" and the stem is made here.
         */
        stem: String? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        val base = stem ?: stemFor(takenAt)
        val values = valuesFor(takenAt, format, base, suffix, hidden).apply {
            if (width > 0) put(MediaStore.Images.Media.WIDTH, width)
            if (height > 0) put(MediaStore.Images.Media.HEIGHT, height)
            // Invisible to every other gallery on the phone until the bytes are down, so a killed
            // write is not a broken thumbnail in Photos.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("no stream")
        }.onFailure { Log.e(TAG, "write failed", it) }.isSuccess
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        uri
    }

    /**
     * Ask the system to bin some photos.
     *
     * Trashing rather than deleting, and through the system dialog rather than quietly: the
     * roll shows photos this app did not create, and deleting someone else's file without
     * asking is not a thing a camera should be able to do. Returns the sender to launch, or
     * null on a device that won't offer one.
     */
    fun trashRequest(uris: List<Uri>): android.content.IntentSender? {
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createTrashRequest(context.contentResolver, uris, true).intentSender
        }.getOrNull()
    }

    /**
     * Fires whenever anything in either collection changes, including our own writes.
     *
     * **Both tables, for the same reason [load] reads both.** Registered on the stills table alone,
     * finishing a recording changed nothing the roll was watching, so a clip only ever appeared
     * after the next photograph happened to poke the observer.
     */
    fun observe(onChange: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(collection, true, observer)
        resolver.registerContentObserver(videoCollection, true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    private companion object {
        /** Where the frames behind a strip go, and the one folder the roll never shows. */
        const val STRIP_PATH = "DCIM/Roll Strips"

        const val TAG = "MediaStoreRepo"
    }
}

/**
 * Day headings for the roll.
 *
 * Pure, and separate from the query, because the interesting part is the calendar and the
 * calendar is where date code goes wrong: "yesterday" is not "24 hours ago", and a photo
 * from December needs its year even though it is four weeks old.
 */
object DayLabels {

    fun dayOf(millis: Long, calendar: Calendar = Calendar.getInstance()): Long {
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun label(dayStart: Long, now: Long = System.currentTimeMillis()): String {
        val today = dayOf(now)
        val days = ((today - dayStart) / 86_400_000L).toInt()
        return when {
            days == 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(dayStart))
            sameYear(dayStart, now) ->
                SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(dayStart))
            else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(dayStart))
        }
    }

    private fun sameYear(a: Long, b: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = a
        val yearA = cal.get(Calendar.YEAR)
        cal.timeInMillis = b
        return yearA == cal.get(Calendar.YEAR)
    }

    /** True on API levels where `READ_MEDIA_IMAGES` is the permission to ask for. */
    val granularMediaPermissions: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
