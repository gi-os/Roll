package com.gios.lightcamera

import com.gios.lightcamera.filter.Filters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial the user arranged, as opposed to the catalog the app ships.
 *
 * Every rule here is one that fails silently on the phone. A filter that stops appearing after
 * an update, an order that reshuffles itself when something is switched off, a wheel that can no
 * longer reach Plain — none of those throw, they just make the camera slightly wrong in a way
 * nobody would connect back to a settings screen they touched once.
 */
class FilterDialTest {

    private fun ids(order: List<String>, off: Set<String>) =
        Filters.ordered(order, off).map { it.id }

    @Test
    fun `never arranged is the catalog, in the order it was written`() {
        assertEquals(Filters.all.map { it.id }, ids(emptyList(), emptySet()))
    }

    @Test
    fun `a saved order is honored`() {
        val order = listOf("mono", "film", Filters.none.id)
        val out = ids(order, emptySet())
        assertEquals(listOf("mono", "film", Filters.none.id), out.take(3))
    }

    /**
     * The trap this whole design exists to avoid. The order is stored as ids, so a filter shipped
     * after the order was saved is a filter the order has never heard of — and the obvious
     * implementation hides it from everyone who has ever opened the settings screen.
     */
    @Test
    fun `a filter the saved order predates still shows up`() {
        val stale = Filters.all.map { it.id } - "fisheye"
        val out = ids(stale, emptySet())
        assertTrue("fisheye vanished from a stale order", "fisheye" in out)
        assertEquals("it should arrive at the end", "fisheye", out.last())
        assertEquals(Filters.all.size, out.size)
    }

    @Test
    fun `switching one off removes exactly one`() {
        val out = ids(emptyList(), setOf("halftone"))
        assertFalse("halftone" in out)
        assertEquals(Filters.all.size - 1, out.size)
    }

    @Test
    fun `plain can never be switched off`() {
        val out = ids(emptyList(), setOf(Filters.none.id, "mono"))
        assertTrue("the camera lost its plain photograph", Filters.none.id in out)
        assertFalse("mono" in out)
    }

    @Test
    fun `an unknown id in a saved order is ignored rather than crashing`() {
        val out = ids(listOf("nonesuch", "mono"), emptySet())
        assertFalse("nonesuch" in out)
        assertEquals("mono", out.first())
        assertEquals(Filters.all.size, out.size)
    }

    /**
     * Moving reorders the **whole** catalog even though the screen only shows what is on. An
     * order that recorded positions of visible filters alone would reshuffle everything else the
     * moment something was switched off and back on.
     */
    @Test
    fun `moving names every filter, including the ones switched off`() {
        val moved = Filters.move(emptyList(), "tunnel", -1)
        assertEquals(Filters.all.size, moved.size)
        assertEquals(Filters.all.map { it.id }.toSet(), moved.toSet())
    }

    @Test
    fun `moving up and back down is a round trip`() {
        val up = Filters.move(emptyList(), "mono", -1)
        val back = Filters.move(up, "mono", 1)
        assertEquals(Filters.all.map { it.id }, back)
    }

    @Test
    fun `moving is clamped at both ends rather than wrapping`() {
        val first = Filters.all.first().id
        val last = Filters.all.last().id
        assertEquals(Filters.all.map { it.id }, Filters.move(emptyList(), first, -1))
        assertEquals(Filters.all.map { it.id }, Filters.move(emptyList(), last, 1))
    }

    @Test
    fun `a position survives being switched off and on again`() {
        val order = Filters.move(emptyList(), "tunnel", -99)
        assertEquals("tunnel", order.first())
        // Off: it leaves the dial but keeps its slot in the order.
        assertFalse("tunnel" in ids(order, setOf("tunnel")))
        // On: it comes back where it was.
        assertEquals("tunnel", ids(order, emptySet()).first())
    }

    @Test
    fun `stepping walks the arranged dial and not the catalog`() {
        val dial = Filters.ordered(emptyList(), setOf("film"))
        val next = Filters.step(Filters.none, 1, dial)
        assertEquals("mono", next.id)
    }

    @Test
    fun `stepping wraps within the arranged dial`() {
        val dial = Filters.ordered(listOf(Filters.none.id, "mono"), Filters.all.map { it.id }.toSet() - "mono")
        assertEquals(2, dial.size)
        assertEquals("mono", Filters.step(Filters.byId(Filters.none.id), 1, dial).id)
        assertEquals(Filters.none.id, Filters.step(Filters.byId("mono"), 1, dial).id)
        assertEquals("mono", Filters.step(Filters.byId(Filters.none.id), -1, dial).id)
    }

    /**
     * You can be shooting a filter and then switch it off in settings. Prefs steps off it, but
     * the dial has to cope with being asked to step from somewhere it does not contain anyway —
     * a wheel that refused to move would be a wheel that looks broken.
     */
    @Test
    fun `stepping from a filter that is no longer on the dial still moves`() {
        val dial = Filters.ordered(emptyList(), setOf("tunnel"))
        assertEquals(dial.first().id, Filters.step(Filters.byId("tunnel"), 1, dial).id)
        assertEquals(dial.last().id, Filters.step(Filters.byId("tunnel"), -1, dial).id)
    }

    @Test
    fun `a dial of nothing but plain still steps`() {
        val dial = Filters.ordered(emptyList(), Filters.all.map { it.id }.toSet())
        assertEquals(listOf(Filters.none.id), dial.map { it.id })
        assertEquals(Filters.none.id, Filters.step(Filters.none, 1, dial).id)
    }
}
