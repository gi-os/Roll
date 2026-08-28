package com.gios.lightcamera.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Map tiles, fetched once and kept.
 *
 * **This is the only part of Roll that talks to the network without being asked to.** Everything
 * else here opens a connection exactly once, when you send a bug report you wrote yourself, and
 * that is a property worth stating rather than quietly dropping — so the map is a scope you have
 * to go to, tiles are cached on disk for good, and nothing is fetched while you are anywhere else
 * in the app.
 *
 * OpenStreetMap's tile servers are run on donations and their usage policy asks for a real
 * identifying User-Agent and no bulk downloading. Both are honoured: the agent names the app and
 * its repository, the cache means a tile is fetched once, and [MAX_CONCURRENT] keeps a pan from
 * turning into a burst of requests.
 */
class Tiles(context: Context) {

    private val cacheDir = File(context.cacheDir, "tiles").apply { mkdirs() }

    private val memory = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MEMORY_TILES
    }

    private val inFlight = Semaphore(MAX_CONCURRENT)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Drop the memory tier. The disk keeps everything; re-showing a tile costs a decode. */
    fun shed() {
        synchronized(memory) { memory.clear() }
    }

    /**
     * A tile, from memory, then disk, then the network.
     *
     * Returns null rather than throwing on any failure. A map with a missing tile is a map with a
     * grey square in it; a map that throws is a crash in a gallery.
     */
    suspend fun get(tile: Tile): Bitmap? {
        val key = "${tile.zoom}_${tile.x}_${tile.y}"
        synchronized(memory) { memory[key] }?.let { return it }

        val file = File(cacheDir, "$key.png")
        if (file.exists()) {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            }
            if (decoded != null) {
                synchronized(memory) { memory[key] = decoded }
                return decoded
            }
            // A half-written file from a killed download decodes to null for ever otherwise.
            runCatching { file.delete() }
        }

        return withContext(Dispatchers.IO) {
            inFlight.withPermit {
                runCatching {
                    val request = Request.Builder()
                        .url("$TILE_HOST/${tile.zoom}/${tile.x}/${tile.y}.png")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val bytes = response.body?.bytes() ?: return@use null
                        // Written to a temporary name and moved, so a download interrupted
                        // half way cannot leave a corrupt tile in the cache under the real name.
                        val temp = File(cacheDir, "$key.part")
                        temp.writeBytes(bytes)
                        temp.renameTo(file)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.onFailure { Log.e(TAG, "tile $key failed", it) }.getOrNull()
                    ?.also { bitmap -> synchronized(memory) { memory[key] = bitmap } }
            }
        }
    }

    companion object {
        private const val TAG = "Tiles"

        private const val TILE_HOST = "https://tile.openstreetmap.org"

        /**
         * Identifying, as the tile usage policy asks. A generic agent is what gets an app blocked,
         * and being blocked would look from the phone like a map that stopped working.
         */
        private const val USER_AGENT = "Roll/1.0 (+https://github.com/gi-os/Roll)"

        /** Enough for a screenful and the ring around it, not enough to hold a city in memory. */
        private const val MEMORY_TILES = 48

        /** A pan asks for a dozen tiles at once; this keeps that polite. */
        private const val MAX_CONCURRENT = 4

        /** The credit the map has to carry. Not optional, and not decoration. */
        const val ATTRIBUTION = "© OpenStreetMap contributors"
    }
}
