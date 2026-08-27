package com.gios.lightcamera

import com.gios.lightcamera.map.Geo
import com.gios.lightcamera.map.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A projection is the kind of code where a sign error puts every photograph in the wrong
 * hemisphere and nothing crashes. So these check against tile numbers that are a matter of public
 * record rather than against whatever this implementation happens to produce.
 */
class GeoTest {

    @Test
    fun `london lands on the tile the rest of the world agrees it lands on`() {
        val tile = Geo.tileOf(Point(51.5079, -0.0877), zoom = 12)
        assertEquals(2047, tile.x)
        assertEquals(1362, tile.y)
    }

    @Test
    fun `new york lands on its tile too`() {
        val tile = Geo.tileOf(Point(40.7128, -74.0060), zoom = 12)
        assertEquals(1205, tile.x)
    }

    @Test
    fun `null island is the exact centre of the world`() {
        val pixel = Geo.toPixel(Point(0.0, 0.0), zoom = 1)
        assertEquals(256.0, pixel.x, 0.0001)
        assertEquals(256.0, pixel.y, 0.0001)
    }

    @Test
    fun `projecting and unprojecting comes back to the same place`() {
        listOf(
            Point(51.5079, -0.0877),
            Point(-33.8688, 151.2093),
            Point(64.1466, -21.9426),
            Point(0.0, 0.0),
        ).forEach { point ->
            val back = Geo.toPoint(Geo.toPixel(point, 15), 15)
            assertEquals(point.latitude, back.latitude, 0.0001)
            assertEquals(point.longitude, back.longitude, 0.0001)
        }
    }

    @Test
    fun `the poles are clamped rather than sent to infinity`() {
        // Mercator is undefined at 90 degrees; unclamped this is a division that never returns.
        val pixel = Geo.toPixel(Point(90.0, 0.0), zoom = 3)
        assertTrue(pixel.y.isFinite())
        assertTrue(pixel.y >= 0)
    }

    @Test
    fun `longitude wraps rather than running off the edge`() {
        assertEquals(-179.0, Geo.wrapLongitude(181.0), 0.0001)
        assertEquals(179.0, Geo.wrapLongitude(-181.0), 0.0001)
        assertEquals(0.0, Geo.wrapLongitude(360.0), 0.0001)
    }

    @Test
    fun `the world doubles with every zoom level`() {
        assertEquals(Geo.worldSize(5) * 2, Geo.worldSize(6), 0.0001)
    }

    /* ---------------- clustering ---------------- */

    @Test
    fun `photographs from one spot are one mark`() {
        val located = listOf(
            1L to Point(51.5079, -0.0877),
            2L to Point(51.5080, -0.0878),
            3L to Point(51.5081, -0.0876),
        )
        val clusters = Geo.cluster(located, zoom = 12)
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].size)
    }

    @Test
    fun `zooming in breaks a city into streets`() {
        val located = listOf(
            1L to Point(51.5079, -0.0877),
            2L to Point(51.5200, -0.1000),
        )
        // Far out they are one mark; close in they are two. That is the whole behaviour, and it is
        // why clustering is done in pixels rather than in degrees.
        assertEquals(1, Geo.cluster(located, zoom = 10).size)
        assertEquals(2, Geo.cluster(located, zoom = 16).size)
    }

    @Test
    fun `every photograph ends up in exactly one cluster`() {
        val located = (1L..40L).map { it to Point(51.5 + it * 0.001, -0.09 + it * 0.001) }
        listOf(8, 12, 16).forEach { zoom ->
            val clusters = Geo.cluster(located, zoom)
            val ids = clusters.flatMap { it.ids }
            assertEquals("zoom $zoom lost or duplicated a photograph", 40, ids.size)
            assertEquals(40, ids.toSet().size)
        }
    }

    @Test
    fun `nothing located is no clusters, not a crash`() {
        assertTrue(Geo.cluster(emptyList(), 12).isEmpty())
    }

    /* ---------------- what to fetch ---------------- */

    @Test
    fun `a viewport asks for a sensible number of tiles`() {
        // The failure that matters: an off-by-one that asks a donated public server for thousands.
        val tiles = Geo.tilesFor(Point(51.5079, -0.0877), zoom = 14, widthPx = 440, heightPx = 780)
        assertTrue("got ${tiles.size}", tiles.size in 1..25)
    }

    @Test
    fun `a viewport over the date line wraps instead of asking for tile minus one`() {
        val tiles = Geo.tilesFor(Point(0.0, 179.99), zoom = 4, widthPx = 800, heightPx = 400)
        val limit = (1 shl 4) - 1
        assertTrue(tiles.all { it.x in 0..limit && it.y in 0..limit })
    }

    @Test
    fun `an empty viewport asks for nothing`() {
        assertTrue(Geo.tilesFor(Point(0.0, 0.0), 12, 0, 0).isEmpty())
    }

    /* ---------------- where the map opens ---------------- */

    @Test
    fun `one photograph opens at a readable zoom rather than the whole world`() {
        assertEquals(Geo.DEFAULT_ZOOM, Geo.zoomToFit(listOf(Point(51.5, -0.09)), 440, 780))
    }

    @Test
    fun `photographs across a continent zoom out further than photographs in a city`() {
        val city = Geo.zoomToFit(
            listOf(Point(51.50, -0.09), Point(51.52, -0.11)), 440, 780,
        )
        val continent = Geo.zoomToFit(
            listOf(Point(51.50, -0.09), Point(41.90, 12.50)), 440, 780,
        )
        assertTrue("city $city continent $continent", continent < city)
    }

    @Test
    fun `the centre of two points is between them`() {
        val centre = Geo.centre(listOf(Point(51.0, -1.0), Point(53.0, 1.0)))!!
        assertTrue(centre.latitude in 51.0..53.0)
        assertTrue(centre.longitude in -1.0..1.0)
    }

    @Test
    fun `nothing located has no centre`() {
        assertEquals(null, Geo.centre(emptyList()))
    }
}
