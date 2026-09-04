package com.gios.lightcamera

import com.gios.light.common.hw.LightKey
import com.gios.lightcamera.hw.ShutterRelease
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two-stage release.
 *
 * Worth testing precisely because the failure modes are so unpleasant on a real camera: a
 * shutter that fires twice, a focus lock that never lets go, or an autofocus that kicks off
 * immediately *after* the photograph because the two keys arrived in the other order.
 */
class ShutterReleaseTest {

    private val log = mutableListOf<String>()
    private var now = 1_000L

    private fun release() = ShutterRelease(
        onHalfPress = { log += "half" },
        onFullPress = { log += "full" },
        onRelease = { log += "release" },
        onFullRelease = { log += "up" },
        nowMs = { now },
    )

    @Test
    fun `a half press focuses and holds until the button comes up`() {
        val r = release()
        r.onKey(LightKey.Focus, down = true)
        assertEquals(listOf("half"), log)
        r.onKey(LightKey.Focus, down = false)
        assertEquals(listOf("half", "release"), log)
    }

    @Test
    fun `pressing through focuses then fires`() {
        val r = release()
        r.onKey(LightKey.Focus, down = true)
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        r.onKey(LightKey.Focus, down = false)
        assertEquals(listOf("half", "full", "up", "release"), log)
    }

    @Test
    fun `the shutter key arriving first does not trigger a pointless autofocus`() {
        // The order of FOCUS and CAMERA is not stable on this hardware. Focusing after the
        // photograph has been taken would rack the lens for nothing and, worse, look like
        // the camera focusing on the *next* shot.
        val r = release()
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Focus, down = true)
        assertEquals(listOf("full"), log)
    }

    @Test
    fun `one press is one photograph`() {
        val r = release()
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = true)
        assertEquals(listOf("full"), log)
    }

    @Test
    fun `the release only lands once both keys are up, in either order`() {
        val r = release()
        r.onKey(LightKey.Focus, down = true)
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Focus, down = false)
        assertEquals(listOf("half", "full"), log)
        r.onKey(LightKey.Camera, down = false)
        assertEquals(listOf("half", "full", "up", "release"), log)
    }

    @Test
    fun `a stray focus key just after a shot is swallowed`() {
        val r = release()
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        assertEquals(listOf("full", "up", "release"), log)
        now += 100
        r.onKey(LightKey.Focus, down = true)
        assertEquals(listOf("full", "up", "release"), log)
    }

    @Test
    fun `a deliberate half press well after a shot does focus`() {
        val r = release()
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        log.clear()
        now += 5_000
        r.onKey(LightKey.Focus, down = true)
        assertEquals(listOf("half"), log)
    }

    @Test
    fun `a second press fires again`() {
        val r = release()
        r.onKey(LightKey.Focus, down = true)
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        r.onKey(LightKey.Focus, down = false)
        log.clear()
        now += 2_000
        r.onKey(LightKey.Focus, down = true)
        r.onKey(LightKey.Camera, down = true)
        assertEquals(listOf("half", "full"), log)
    }

    @Test
    fun `the bottom detent letting go is reported before the whole button is up`() {
        // Resting at the half press after a shot keeps the focus lock; the caller's burst
        // clock needs this earlier edge to know the shutter itself has been let go.
        val r = release()
        r.onKey(LightKey.Focus, down = true)
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        assertEquals(listOf("half", "full", "up"), log)
        r.onKey(LightKey.Focus, down = false)
        assertEquals(listOf("half", "full", "up", "release"), log)
    }

    @Test
    fun `contact chatter on the shutter key is one photograph`() {
        // CAMERA won the race, bounced DOWN-UP-DOWN before FOCUS landed. The UP in the
        // middle settles the press, so without a guard the second DOWN fires again.
        val r = release()
        r.onKey(LightKey.Camera, down = true)
        now += 5
        r.onKey(LightKey.Camera, down = false)
        now += 5
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Focus, down = true)
        r.onKey(LightKey.Camera, down = false)
        r.onKey(LightKey.Focus, down = false)
        assertEquals(1, log.count { it == "full" })
    }

    @Test
    fun `two quick deliberate presses are two photographs`() {
        val r = release()
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        now += 300
        r.onKey(LightKey.Camera, down = true)
        r.onKey(LightKey.Camera, down = false)
        assertEquals(2, log.count { it == "full" })
    }

    @Test
    fun `the wheel is not the shutter`() {
        val r = release()
        assertEquals(false, r.onKey(LightKey.WheelClick, down = true))
        assertEquals(false, r.onKey(LightKey.WheelUp, down = true))
        assertEquals(emptyList<String>(), log)
    }
}
