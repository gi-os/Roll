package com.gios.lightcamera

import com.gios.lightcamera.filter.Adjust
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Preset grade, off-device.
 *
 * The interesting property is not the arithmetic — it is that **a grade nobody has touched must
 * cost nothing**. `Filters.forGrade` is what decides whether the shutter takes the plain path that
 * writes the sensor's own JPEG or the one that decodes 12 megapixels, runs a shader over them and
 * encodes again. Getting that backwards would not look like a bug; it would look like the camera
 * getting slower for no reason.
 */
class GradeTest {

    @Test
    fun `a fresh grade is neutral`() {
        assertTrue(Grade().isNeutral)
        assertTrue(Grade.NEUTRAL.isNeutral)
        assertEquals(0, Grade().touched)
    }

    @Test
    fun `any single adjustment is enough to stop being neutral`() {
        // Every one of them, because a `isNeutral` written as a chain of ands is exactly the kind
        // of expression that loses a term in a merge and then silently ignores one adjustment.
        Adjust.entries.forEach { adjust ->
            val moved = Grade().with(adjust, 1)
            assertFalse("${adjust.label} left the grade neutral", moved.isNeutral)
            assertEquals("${adjust.label} did not read back", 1, moved[adjust])
            assertEquals(1, moved.touched)
        }
    }

    @Test
    fun `stepping clamps at each end and never wraps`() {
        Adjust.entries.forEach { adjust ->
            var g = Grade()
            repeat(20) { g = g.step(adjust, 1) }
            assertEquals("${adjust.label} ran past its top", adjust.max, g[adjust])
            repeat(40) { g = g.step(adjust, -1) }
            assertEquals("${adjust.label} ran past its bottom", adjust.min, g[adjust])
        }
    }

    @Test
    fun `grain cannot go negative`() {
        // The one one-sided adjustment: there is no negative film texture to remove.
        assertEquals(0, Adjust.Grain.min)
        assertEquals(0, Grade().step(Adjust.Grain, -3)[Adjust.Grain])
    }

    @Test
    fun `a neutral grade leaves the shutter on the untouched path`() {
        val resolved = Filters.forGrade(Filters.none, Grade.NEUTRAL)
        assertSame(Filters.none, resolved)
        // Null shader is the whole point: `Frames.process` reads it as "write the camera's JPEG".
        assertEquals(null, resolved.agsl)
    }

    @Test
    fun `a set grade swaps in the shader and carries the values`() {
        val grade = Grade().with(Adjust.Warmth, 3)
        val resolved = Filters.forGrade(Filters.none, grade)
        assertEquals("none", resolved.id)
        assertTrue(resolved.adjustable)
        assertTrue(resolved.agsl != null)
        assertEquals(grade, resolved.grade)
    }

    @Test
    fun `a grade never touches any other filter`() {
        val grade = Grade().with(Adjust.Contrast, 5)
        Filters.all.filter { it.id != Filters.none.id }.forEach { filter ->
            assertSame("$filter was rewritten by a grade", filter, Filters.forGrade(filter, grade))
        }
    }

    @Test
    fun `the shader reads the full range as minus one to one`() {
        assertEquals(1f, Grade().with(Adjust.Exposure, Grade.MAX).normalised(Adjust.Exposure), 0f)
        assertEquals(-1f, Grade().with(Adjust.Exposure, Grade.MIN).normalised(Adjust.Exposure), 0f)
        assertEquals(0f, Grade().normalised(Adjust.Exposure), 0f)
    }

    @Test
    fun `the sign is shown on everything except zero`() {
        assertEquals("0", Adjust.Exposure.display(0))
        assertEquals("+2", Adjust.Exposure.display(2))
        assertEquals("-4", Adjust.Exposure.display(-4))
    }

    @Test
    fun `the preference keys never drift`() {
        // The grade is stored one SharedPreferences key per adjustment, named off the enum constant.
        // Renaming a constant would therefore silently reset every saved preset to zero on upgrade —
        // the key would simply not be found and the default would win, with no error anywhere. This
        // test is the only thing standing between a refactor and that.
        assertEquals(
            listOf(
                "Exposure", "Contrast", "Highlights", "Shadows", "Vibrance",
                "Warmth", "Tint", "Sharpness", "Grain", "Vignette",
            ),
            Adjust.entries.map { it.name },
        )
    }

    @Test
    fun `adjustment labels fit the menu`() {
        Adjust.entries.forEach { adjust ->
            assertTrue("${adjust.label} is too long", adjust.label.length <= 12)
            assertTrue("${adjust.label} has no hint", adjust.hint.isNotBlank())
        }
    }
}
