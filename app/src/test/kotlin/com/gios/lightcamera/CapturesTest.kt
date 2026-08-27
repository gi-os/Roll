package com.gios.lightcamera

import com.gios.lightcamera.media.CaptureFormat
import com.gios.lightcamera.media.Captures
import com.gios.lightcamera.media.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping is the one part of multi-format capture with no camera in it, so it is the one part
 * that can be proved here — and it is also the part that fails *invisibly*. A stem parsed wrongly
 * does not crash: it silently merges two photographs into one, or splits one into three, and the
 * only symptom is a roll that looks slightly wrong to somebody who was there.
 */
class CapturesTest {

    private var nextId = 1L

    private fun slot(name: String, video: Boolean = false) = Slot(nextId++, name, video)

    /* ---------------- stems ---------------- */

    @Test
    fun `the three formats of one press share a stem`() {
        val stem = Captures.stemOf("ROLL_20260827_143210_881.jpg")
        assertEquals(stem, Captures.stemOf("ROLL_20260827_143210_881.png"))
        assertEquals(stem, Captures.stemOf("ROLL_20260827_143210_881.dng"))
        assertEquals("ROLL_20260827_143210_881", stem)
    }

    @Test
    fun `two presses in the same second do not merge`() {
        // The whole reason the stamp carries milliseconds. Without them these are one stem.
        assertFalse(
            Captures.stemOf("ROLL_20260827_143210_881.jpg") ==
                Captures.stemOf("ROLL_20260827_143210_904.jpg"),
        )
    }

    @Test
    fun `files written before milliseconds still parse`() {
        assertEquals("ROLL_20260101_090000", Captures.stemOf("ROLL_20260101_090000.jpg"))
    }

    @Test
    fun `a role suffix is not swallowed into the identity`() {
        // `_strip` and `_1`..`_4` mark separate pictures from one booth visit. They share the
        // stamp on purpose — framesOf() links them by it — but the parse must stop at the
        // millisecond field rather than treating the role as part of the identity.
        assertEquals("ROLL_20260827_143210_881", Captures.stemOf("ROLL_20260827_143210_881_strip.jpg"))
        assertEquals("ROLL_20260827_143210_881", Captures.stemOf("ROLL_20260827_143210_881_1.jpg"))
    }

    @Test
    fun `anything not written by this app is its own stem`() {
        assertEquals("Screenshot_20260827", Captures.stemOf("Screenshot_20260827.png"))
        assertEquals("IMG_0042", Captures.stemOf("IMG_0042.jpg"))
        assertEquals("no-extension", Captures.stemOf("no-extension"))
    }

    /* ---------------- formats ---------------- */

    @Test
    fun `format comes off the extension, case insensitively`() {
        assertEquals(CaptureFormat.Jpeg, CaptureFormat.ofFile("ROLL_1.jpg"))
        assertEquals(CaptureFormat.Png, CaptureFormat.ofFile("ROLL_1.PNG"))
        assertEquals(CaptureFormat.Dng, CaptureFormat.ofFile("ROLL_1.dng"))
        assertNull(CaptureFormat.ofFile("clip.mp4"))
        assertNull(CaptureFormat.ofFile("noextension"))
    }

    @Test
    fun `raw is the one format a filter cannot touch`() {
        assertFalse(CaptureFormat.Dng.filtered)
        assertTrue(CaptureFormat.Jpeg.filtered)
        assertTrue(CaptureFormat.Png.filtered)
    }

    /* ---------------- grouping ---------------- */

    @Test
    fun `one press with three formats is one item`() {
        val plans = Captures.plan(
            listOf(
                slot("ROLL_20260827_143210_881.jpg"),
                slot("ROLL_20260827_143210_881.png"),
                slot("ROLL_20260827_143210_881.dng"),
            ),
        )
        assertEquals(1, plans.size)
        assertEquals(
            listOf(CaptureFormat.Jpeg, CaptureFormat.Png, CaptureFormat.Dng),
            plans[0].members.map { it.format },
        )
    }

    @Test
    fun `the JPEG leads even when the negative was written first`() {
        // Order of the two saves is not guaranteed — CameraX says so explicitly for RAW+JPEG.
        val plans = Captures.plan(
            listOf(
                slot("ROLL_20260827_143210_881.dng"),
                slot("ROLL_20260827_143210_881.jpg"),
            ),
        )
        assertEquals(CaptureFormat.Jpeg, plans[0].members.first().format)
    }

    @Test
    fun `a raw-only press opens as the negative`() {
        val plans = Captures.plan(listOf(slot("ROLL_20260827_143210_881.dng")))
        assertEquals(1, plans.size)
        assertEquals(CaptureFormat.Dng, plans[0].members.single().format)
    }

    @Test
    fun `the roll keeps its order`() {
        // The caller hands these over newest-first, and a group must take the position of its
        // first member, or a photograph moves up the roll because its negative landed late.
        val plans = Captures.plan(
            listOf(
                slot("ROLL_20260827_143211_100.jpg"),
                slot("ROLL_20260827_143210_881.dng"),
                slot("ROLL_20260827_143210_881.jpg"),
                slot("ROLL_20260827_143209_004.jpg"),
            ),
        )
        assertEquals(
            listOf(
                "ROLL_20260827_143211_100",
                "ROLL_20260827_143210_881",
                "ROLL_20260827_143209_004",
            ),
            plans.map { it.stem },
        )
    }

    @Test
    fun `a clip is never folded into a still's group`() {
        val plans = Captures.plan(
            listOf(
                slot("ROLL_20260827_143210_881.mp4", video = true),
                slot("ROLL_20260827_143210_881.jpg"),
            ),
        )
        assertEquals(2, plans.size)
        assertNull(plans[0].members.single().format)
    }

    @Test
    fun `a foreign file is its own item even when two share a name`() {
        // The roll shows every photo on the phone, so duplicate names from different folders
        // must not throw and must not merge into something that looks like one capture.
        val plans = Captures.plan(
            listOf(slot("IMG_0042.heic"), slot("IMG_0042.heic")),
        )
        assertEquals(2, plans.size)
    }

    @Test
    fun `an empty roll is an empty list, not a crash`() {
        assertEquals(emptyList<Any>(), Captures.plan(emptyList()))
    }
}
