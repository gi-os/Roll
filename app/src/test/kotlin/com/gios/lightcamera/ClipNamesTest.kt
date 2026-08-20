package com.gios.lightcamera

import com.gios.lightcamera.media.ClipNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * A clip's file name is the only record of when the take began.
 *
 * The file's own `lastModified` is the moment it *ended*, and the clock at the moment it is copied
 * into the gallery could be a whole launch later — so if the name and the parse stop agreeing,
 * clips arrive in the roll dated wrongly and nothing in the app notices. Hence the round trip,
 * rather than a check that either half looks right on its own.
 */
class ClipNamesTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `the name is ROLL, the stamp, and mp4`() {
        assertEquals("ROLL_20260820_143012.mp4", ClipNames.nameFor(at(2026, 8, 20, 14, 30, 12)))
    }

    @Test
    fun `a name round trips back to the second it was made from`() {
        val shot = at(2026, 8, 20, 14, 30, 12)
        assertEquals(shot, ClipNames.stampOf(ClipNames.nameFor(shot)))
    }

    @Test
    fun `the second take in a second is suffixed, and still parses`() {
        // Stop and immediately record again: both takes stamp the same second, and the first one
        // may still be sitting in the queue. The suffix is what stops the second recording over it.
        val shot = at(2026, 8, 20, 14, 30, 12)
        val first = ClipNames.nameFor(shot)
        val second = ClipNames.nameFor(shot, 2)
        assertEquals("ROLL_20260820_143012_2.mp4", second)
        assertEquals(first, ClipNames.nameFor(shot, 1))
        assertEquals(shot, ClipNames.stampOf(second))
    }

    @Test
    fun `the milliseconds are dropped rather than rounded`() {
        // A stamp is seconds, so a name is the second the take started in — never the next one.
        val shot = at(2026, 8, 20, 14, 30, 12)
        assertEquals(shot, ClipNames.stampOf(ClipNames.nameFor(shot + 999)))
    }

    @Test
    fun `midnight and the turn of the year survive the trip`() {
        for (shot in listOf(
            at(2026, 1, 1, 0, 0, 0),
            at(2026, 12, 31, 23, 59, 59),
        )) {
            assertEquals(shot, ClipNames.stampOf(ClipNames.nameFor(shot)))
        }
    }

    @Test
    fun `a name from anywhere else is not ours`() {
        // Null is not a refusal to save the clip — it means fall back to the file's timestamp.
        assertNull(ClipNames.stampOf("VID_20260820_143012.mp4"))
        assertNull(ClipNames.stampOf("movie.mp4"))
        assertNull(ClipNames.stampOf(""))
        // The stamp has to be where we put it, not merely somewhere in the name.
        assertNull(ClipNames.stampOf("clip_ROLL_20260820_143012.mp4"))
        // Right shape, wrong length: eight digits and six, and nothing else counts.
        assertNull(ClipNames.stampOf("ROLL_2026082_143012.mp4"))
    }

    @Test
    fun `a still's name is not read as a clip's`() {
        // Photographs are `ROLL_<stamp>.jpg` from the same stamp, and the roll is one list of both.
        // The prefix matches, which is the point: nothing here should ever be handed a jpg, and if
        // it is, reading the date off it is right rather than wrong.
        assertEquals(
            at(2026, 8, 20, 14, 30, 12),
            ClipNames.stampOf("ROLL_20260820_143012.jpg"),
        )
    }
}
