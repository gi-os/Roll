package com.gios.lightcamera.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropScopeTest {

    @Test
    fun `the whole roll allows everything`() {
        assertTrue(DropScope.WholeRoll.allows(1L, emptyList()))
        assertTrue(DropScope.WholeRoll.allows(99L, listOf(100L)))
        assertNull(DropScope.WholeRoll.count)
        assertEquals("the entire roll", DropScope.WholeRoll.label())
    }

    @Test
    fun `a selection allows its ids and nothing else`() {
        val scope = DropScope.Selected(setOf(4L, 7L))
        assertTrue(scope.allows(4L, listOf(4L, 5L)))
        assertFalse(scope.allows(5L, listOf(5L, 6L)))
        assertEquals(2, scope.count)
    }

    @Test
    fun `selecting any file of a press selects the press`() {
        // The roll draws the JPEG; the negative and the lossless copy ride with it.
        val scope = DropScope.Selected(setOf(12L))
        assertTrue(scope.allows(10L, listOf(10L, 11L, 12L)))
    }

    @Test
    fun `an empty selection allows nothing`() {
        val scope = DropScope.Selected(emptySet())
        assertFalse(scope.allows(1L, listOf(1L)))
        assertEquals(0, scope.count)
    }

    @Test
    fun `labels count in plain words`() {
        assertEquals("1 selected photo", DropScope.Selected(setOf(1L)).label())
        assertEquals("3 selected photos", DropScope.Selected(setOf(1L, 2L, 3L)).label())
    }
}
