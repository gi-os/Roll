package com.gios.lightcamera.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Where a photograph was taken. */
data class Point(val latitude: Double, val longitude: Double)

/** A tile in the standard slippy-map scheme: zoom, column, row. */
data class Tile(val zoom: Int, val x: Int, val y: Int)

/** Somewhere on the map in whole pixels at a given zoom, which is what the canvas draws in. */
data class Pixel(val x: Double, val y: Double)

/** A group of photographs close enough together to draw as one mark. */
class Cluster(val point: Point, val ids: List<Long>) {
    val size: Int get() = ids.size
}

/**
 * Web Mercator, and the clustering that keeps a city from becoming one black blob.
 *
 * **All of it is arithmetic, which is the point.** A map is the kind of feature where a sign error
 * puts every photograph in the wrong hemisphere and the only symptom is that it looks wrong — so
 * the projection lives here, with no Android and no network, and is checked against places whose
 * coordinates are known.
 */
object Geo {

    const val TILE_SIZE = 256

    /** The projection is undefined at the poles; this is where every slippy map cuts it off. */
    const val MAX_LATITUDE = 85.05112878

    fun clampLatitude(latitude: Double): Double =
        max(-MAX_LATITUDE, min(MAX_LATITUDE, latitude))

    /** Longitudes wrap; a photograph taken at 181° is at -179°, not off the edge of the world. */
    fun wrapLongitude(longitude: Double): Double {
        var lon = longitude
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }

    /** World size in pixels at this zoom. Doubles every level, which is the whole scheme. */
    fun worldSize(zoom: Int): Double = TILE_SIZE.toDouble() * (1 shl zoom.coerceIn(0, 22))

    fun toPixel(point: Point, zoom: Int): Pixel {
        val size = worldSize(zoom)
        val lat = clampLatitude(point.latitude)
        val lon = wrapLongitude(point.longitude)
        val x = (lon + 180.0) / 360.0 * size
        val sinLat = kotlin.math.sin(lat * PI / 180.0)
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * size
        // **Clamped, because the top of the world comes out a hair negative.** At exactly
        // [MAX_LATITUDE] the projection is zero on paper and -1.3e-08 in doubles, and a value a
        // hair below zero floors to tile -1 — a request for a tile that does not exist, at the one
        // latitude a clamp was supposed to have already made safe.
        return Pixel(x.coerceIn(0.0, size), y.coerceIn(0.0, size))
    }

    fun toPoint(pixel: Pixel, zoom: Int): Point {
        val size = worldSize(zoom)
        val lon = pixel.x / size * 360.0 - 180.0
        val n = PI - 2.0 * PI * pixel.y / size
        val lat = 180.0 / PI * atan(sinh(n))
        return Point(lat, wrapLongitude(lon))
    }

    fun tileOf(point: Point, zoom: Int): Tile {
        val pixel = toPixel(point, zoom)
        val limit = (1 shl zoom.coerceIn(0, 22)) - 1
        return Tile(
            zoom = zoom,
            x = floor(pixel.x / TILE_SIZE).toInt().coerceIn(0, limit),
            y = floor(pixel.y / TILE_SIZE).toInt().coerceIn(0, limit),
        )
    }

    /**
     * The zoom at which a set of points fills a viewport.
     *
     * Used when the map opens: dropped somewhere arbitrary it is useless, and starting at the
     * whole world with three photographs in one city is the same thing.
     */
    fun zoomToFit(points: List<Point>, viewWidthPx: Int, viewHeightPx: Int): Int {
        if (points.size < 2 || viewWidthPx <= 0 || viewHeightPx <= 0) return DEFAULT_ZOOM
        for (zoom in MAX_ZOOM downTo MIN_ZOOM) {
            val pixels = points.map { toPixel(it, zoom) }
            val width = pixels.maxOf { it.x } - pixels.minOf { it.x }
            val height = pixels.maxOf { it.y } - pixels.minOf { it.y }
            if (width <= viewWidthPx * FIT_MARGIN && height <= viewHeightPx * FIT_MARGIN) return zoom
        }
        return MIN_ZOOM
    }

    /** The middle of a set of points, for where the map opens. */
    fun centre(points: List<Point>): Point? {
        if (points.isEmpty()) return null
        // Averaged in projected space rather than in degrees, so a set either side of the date line
        // does not average to the middle of the Pacific.
        val sum = points.fold(0.0 to 0.0) { acc, p ->
            val pixel = toPixel(p, MAX_ZOOM)
            acc.first + pixel.x to acc.second + pixel.y
        }
        return toPoint(Pixel(sum.first / points.size, sum.second / points.size), MAX_ZOOM)
    }

    /**
     * Group points that would land on top of each other into one mark.
     *
     * **Done in pixels at the current zoom, not in degrees.** A degree of longitude is 111km at the
     * equator and nothing at the pole, so a fixed distance in degrees clusters differently
     * depending on where you were standing. In pixels it clusters the way it looks, which is the
     * only thing the reader cares about — and it re-clusters as you zoom, which is what makes a
     * city break apart into streets.
     */
    fun cluster(
        located: List<Pair<Long, Point>>,
        zoom: Int,
        radiusPx: Int = CLUSTER_RADIUS_PX,
    ): List<Cluster> {
        if (located.isEmpty()) return emptyList()
        val taken = BooleanArray(located.size)
        val pixels = located.map { toPixel(it.second, zoom) }
        val out = ArrayList<Cluster>()
        for (i in located.indices) {
            if (taken[i]) continue
            taken[i] = true
            val ids = ArrayList<Long>()
            ids += located[i].first
            var sumX = pixels[i].x
            var sumY = pixels[i].y
            for (j in i + 1 until located.size) {
                if (taken[j]) continue
                if (abs(pixels[i].x - pixels[j].x) > radiusPx) continue
                if (abs(pixels[i].y - pixels[j].y) > radiusPx) continue
                taken[j] = true
                ids += located[j].first
                sumX += pixels[j].x
                sumY += pixels[j].y
            }
            val centre = toPoint(Pixel(sumX / ids.size, sumY / ids.size), zoom)
            out += Cluster(centre, ids)
        }
        return out
    }

    /**
     * The tiles needed to cover a viewport.
     *
     * Returned in draw order, top-left first, and never more than the viewport can hold — an
     * off-by-one here is a request for a few thousand tiles from a public server that asks people
     * not to do that.
     */
    fun tilesFor(centre: Point, zoom: Int, widthPx: Int, heightPx: Int): List<Tile> {
        if (widthPx <= 0 || heightPx <= 0) return emptyList()
        val centrePixel = toPixel(centre, zoom)
        val limit = (1 shl zoom.coerceIn(0, 22)) - 1
        val left = centrePixel.x - widthPx / 2.0
        val top = centrePixel.y - heightPx / 2.0
        val firstX = floor(left / TILE_SIZE).toInt()
        val firstY = floor(top / TILE_SIZE).toInt()
        val lastX = floor((left + widthPx) / TILE_SIZE).toInt()
        val lastY = floor((top + heightPx) / TILE_SIZE).toInt()
        val out = ArrayList<Tile>()
        for (y in firstY..lastY) {
            for (x in firstX..lastX) {
                if (y < 0 || y > limit) continue
                // Longitude wraps, so a column off the left edge is the far right of the world.
                val wrappedX = ((x % (limit + 1)) + (limit + 1)) % (limit + 1)
                out += Tile(zoom, wrappedX, y)
            }
        }
        return out
    }

    /** Where a tile's top-left corner sits on the canvas, given where the viewport is. */
    fun offsetOf(tile: Tile, centre: Point, widthPx: Int, heightPx: Int): Pixel {
        val centrePixel = toPixel(centre, tile.zoom)
        val left = centrePixel.x - widthPx / 2.0
        val top = centrePixel.y - heightPx / 2.0
        return Pixel(tile.x * TILE_SIZE - left, tile.y * TILE_SIZE - top)
    }

    /** Where a point sits on the canvas. */
    fun screenOf(point: Point, centre: Point, zoom: Int, widthPx: Int, heightPx: Int): Pixel {
        val p = toPixel(point, zoom)
        val c = toPixel(centre, zoom)
        return Pixel(p.x - c.x + widthPx / 2.0, p.y - c.y + heightPx / 2.0)
    }

    const val MIN_ZOOM = 2
    const val MAX_ZOOM = 18
    const val DEFAULT_ZOOM = 13

    /** Two marks closer than this are one mark. Roughly a fingertip on this panel. */
    const val CLUSTER_RADIUS_PX = 44

    /** Leave a little air around the edge when fitting, so marks are not against the frame. */
    private const val FIT_MARGIN = 0.85
}
