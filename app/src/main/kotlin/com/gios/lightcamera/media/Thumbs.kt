package com.gios.lightcamera.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thumbnails, and full frames for the viewer.
 *
 * No image loading library. Coil would be one line at the call site and about a megabyte,
 * and what it mostly buys — a disk cache, a network stack, transformations — is redundant
 * here: MediaStore already keeps a thumbnail cache of its own, the files are local, and the
 * only transformation wanted is a decode at the right size.
 *
 * What does need care is not decoding six full-size JPEGs at once while the wheel is
 * spinning, hence [gate].
 */
class Thumbs(private val context: Context) {

    /**
     * Sized in bytes rather than entries, because a 256px thumbnail and a 1080px frame
     * differ by twenty times and an entry count would either waste memory or thrash.
     */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Four at a time: enough to keep the grid ahead of a scroll, few enough to stay smooth. */
    private val gate = Semaphore(4)

    fun cached(id: Long): Bitmap? = cache["t$id"]

    suspend fun thumbnail(uri: Uri, id: Long, px: Int): Bitmap? {
        cache["t$id"]?.let { return it }
        return gate.withPermit {
            cache["t$id"]?.let { return@withPermit it }
            val bitmap = withContext(Dispatchers.IO) {
                // loadThumbnail hands back MediaStore's own cached thumbnail where there is
                // one, which is far cheaper than decoding the JPEG ourselves.
                runCatching {
                    context.contentResolver.loadThumbnail(uri, Size(px, px), null)
                }.getOrNull()
            }
            if (bitmap != null) cache.put("t$id", bitmap)
            bitmap
        }
    }

    /**
     * A frame for the full-screen viewer, decoded no larger than it will be drawn.
     *
     * `inSampleSize` only takes powers of two, so this deliberately under-shoots — it finds
     * the largest power-of-two reduction that still covers the target — rather than
     * decoding at 1:1 and scaling down, which for a 12MP JPEG is 48MB of heap to throw away.
     */
    suspend fun frame(uri: Uri, id: Long, maxPx: Int, video: Boolean = false): Bitmap? {
        val key = "f$id-$maxPx"
        cache[key]?.let { return it }
        return gate.withPermit {
            cache[key]?.let { return@withPermit it }
            val bitmap = withContext(Dispatchers.IO) {
                if (video) videoFrame(uri, maxPx) else stillFrame(uri, maxPx)
            }
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }
    }

    private fun stillFrame(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    /**
     * The first frame of a clip, to sit behind the play triangle.
     *
     * [stillFrame] is `BitmapFactory`, which decodes an image file and nothing else — handed a
     * video it returns null, and the viewer drew a black rectangle with a triangle floating in
     * the middle of it. Every other page in that pager shows a picture, so a clip looked like a
     * frame that had failed to load.
     *
     * `loadThumbnail` first, because it is the same call the roll grid makes and MediaStore
     * mostly has the answer already cached; it also applies the clip's rotation on the way out,
     * so the poster frame and the playing video are the same way up. `MediaMetadataRetriever` is
     * the fallback for a clip MediaStore has no thumbnail for — a full decode of one frame, which
     * is why it is not the first choice. `OPTION_CLOSEST_SYNC` at zero is the opening keyframe.
     */
    private fun videoFrame(uri: Uri, maxPx: Int): Bitmap? =
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(maxPx, maxPx), null)
        }.getOrNull() ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()

    /** An undeveloped frame, which is a plain file and not in MediaStore at all. */
    suspend fun file(file: File, maxPx: Int): Bitmap? = gate.withPermit {
        val key = "l${file.absolutePath}-$maxPx"
        cache[key]?.let { return@withPermit it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longest / (sample * 2) >= maxPx) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
        if (bitmap != null) cache.put(key, bitmap)
        bitmap
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val CACHE_BYTES = 48 * 1024 * 1024
    }
}
