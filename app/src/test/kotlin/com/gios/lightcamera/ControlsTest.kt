package com.gios.lightcamera

import com.gios.lightcamera.hw.Binding
import com.gios.lightcamera.hw.Controls
import com.gios.lightcamera.hw.DialAction
import com.gios.lightcamera.hw.PressAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {

    /**
     * The mapping the app shipped with, spelled out here so that changing a default is a decision
     * somebody has to come and edit a test for.
     */
    @Test
    fun `the defaults are the mapping the app has always had`() {
        assertEquals(PressAction.Shutter.name, Binding.VolumeUp.default)
        assertEquals(PressAction.Shutter.name, Binding.VolumeDown.default)
        assertEquals(DialAction.Filter.name, Binding.WheelTurn.default)
        assertEquals(DialAction.Exposure.name, Binding.WheelPressTurn.default)
        // Torch, again. v2.49 moved this to the dial lock so a locked dial had something to
        // open it; v2.50 made the lock a setting that claims the click while it is on, so the
        // binding underneath went back to what it always was.
        assertEquals(PressAction.Torch.name, Binding.WheelClick.default)
    }

    /* ---------------- the dial lock ---------------- */

    @Test
    fun `the dial is dead while it is locked and the setting is on`() {
        assertFalse(Controls.dialLive(locked = true, lockOn = true, stripOpen = false))
        assertTrue(Controls.dialLive(locked = false, lockOn = true, stripOpen = false))
    }

    /**
     * **The v2.49 fault, as a test.** The lock was unconditional and its only way out was a
     * hardware key; on a phone where LightControl has claimed the wheel system-wide that key never
     * arrives, so the dial was locked with nothing on the device able to open it — and settings is
     * reached through the mode picker, which is reached with the wheel.
     *
     * With the setting off there is no lock at all, so an update can never take somebody's dial
     * away and the way back is a row you tap rather than a key that may not exist.
     */
    @Test
    fun `with the setting off the dial always turns`() {
        assertTrue(Controls.dialLive(locked = true, lockOn = false, stripOpen = false))
        assertTrue(Controls.dialLive(locked = false, lockOn = false, stripOpen = false))
    }

    /** A strip is a value you opened in order to set. Locking it would be locking the wrong turn. */
    @Test
    fun `an open strip takes the wheel whatever the lock says`() {
        assertTrue(Controls.dialLive(locked = true, lockOn = true, stripOpen = true))
    }

    @Test
    fun `the wheel click is claimed by the lock only while the dial is asleep`() {
        assertEquals(
            PressAction.DialLock,
            Controls.pressNow(
                Binding.WheelClick, PressAction.Torch,
                clipPlaying = false, dialAsleep = true,
            ),
        )
        // Awake, and the click is its binding again on the very next press — no relaunch, and the
        // binding underneath was never touched.
        assertEquals(
            PressAction.Torch,
            Controls.pressNow(
                Binding.WheelClick, PressAction.Torch,
                clipPlaying = false, dialAsleep = false,
            ),
        )
    }

    /**
     * **The regression this replaced.** The click used to be claimed for as long as the lock
     * setting was on, so anything bound to it was unreachable for the whole session: press it,
     * nothing happens, no way to find out why. One wake is all the lock ever needs.
     */
    @Test
    fun `a woken dial hands the click back to whatever it is bound to`() {
        assertEquals(
            PressAction.Channel,
            Controls.pressNow(
                Binding.WheelClick, PressAction.Channel,
                clipPlaying = false, dialAsleep = false,
            ),
        )
    }

    /** The lock takes the click and nothing else, however loudly it is switched on. */
    @Test
    fun `the lock never claims a volume key`() {
        assertEquals(
            PressAction.Shutter,
            Controls.pressNow(
                Binding.VolumeUp, PressAction.Shutter,
                clipPlaying = false, dialAsleep = true,
            ),
        )
    }

    /**
     * The lock is not offered in the key picker, which is what stops it being put on the last
     * shutter or taken off the click and stranding a locked dial.
     */
    @Test
    fun `the lock is not a bindable action`() {
        assertFalse(PressAction.DialLock in PressAction.assignable)
        assertTrue(PressAction.Torch in PressAction.assignable)
        assertEquals(PressAction.entries.size - 1, PressAction.assignable.size)
    }

    @Test
    fun `only the wheel turns are dials`() {
        assertTrue(Binding.WheelTurn.dial)
        assertTrue(Binding.WheelPressTurn.dial)
        assertFalse(Binding.VolumeUp.dial)
        assertFalse(Binding.VolumeDown.dial)
        assertFalse(Binding.WheelClick.dial)
    }

    /**
     * A clip playing takes the volume keys back, and gives them back again when it stops. The
     * second half is the one that matters: a flag left set would leave the phone with no shutter.
     */
    @Test
    fun `a playing clip lends the volume keys to the system`() {
        assertEquals(
            PressAction.Nothing,
            Controls.pressNow(Binding.VolumeUp, PressAction.Shutter, clipPlaying = true),
        )
        assertEquals(
            PressAction.Nothing,
            Controls.pressNow(Binding.VolumeDown, PressAction.Shutter, clipPlaying = true),
        )
        assertEquals(
            PressAction.Shutter,
            Controls.pressNow(Binding.VolumeUp, PressAction.Shutter, clipPlaying = false),
        )
    }

    /** Nothing else is touched — there is no system volume behaviour to hand a wheel back to. */
    @Test
    fun `the wheel keeps its job while a clip plays`() {
        assertEquals(
            PressAction.DialLock,
            Controls.pressNow(Binding.WheelClick, PressAction.DialLock, clipPlaying = true),
        )
    }

    /** A working camera key is a shutter, so nothing else has to be one. */
    @Test
    fun `anything goes while the camera key works`() {
        assertTrue(
            Controls.shutterSafe(
                volumeUp = PressAction.Zoom,
                volumeDown = PressAction.Nothing,
                wheelClick = PressAction.Torch,
                cameraKeyWorks = true,
            ),
        )
    }

    /**
     * The case this whole rule exists for: LightControl is swallowing the camera key, there is no
     * shutter button on the screen, and unbinding the last volume shutter would leave a camera that
     * cannot take a photograph and no way back into settings except guessing.
     */
    @Test
    fun `the last shutter cannot be given away when the camera key is swallowed`() {
        assertFalse(
            Controls.shutterSafe(
                volumeUp = PressAction.FlipLens,
                volumeDown = PressAction.Torch,
                wheelClick = PressAction.Torch,
                cameraKeyWorks = false,
            ),
        )
    }

    @Test
    fun `one shutter anywhere is enough`() {
        assertTrue(
            Controls.shutterSafe(
                PressAction.Shutter,
                PressAction.Nothing,
                PressAction.Nothing,
                cameraKeyWorks = false,
            ),
        )
        assertTrue(
            Controls.shutterSafe(
                PressAction.Nothing,
                PressAction.Shutter,
                PressAction.Nothing,
                cameraKeyWorks = false,
            ),
        )
        // The wheel counts too: a click bound to the shutter is a shutter.
        assertTrue(
            Controls.shutterSafe(
                PressAction.Nothing,
                PressAction.Nothing,
                PressAction.Shutter,
                cameraKeyWorks = false,
            ),
        )
    }

    @Test
    fun `an unknown stored name reads as nothing rather than throwing`() {
        assertEquals(PressAction.Nothing, PressAction.byName("Teleport"))
        assertEquals(DialAction.Nothing, DialAction.byName(null))
        assertEquals(PressAction.Shutter, PressAction.byName("Shutter"))
    }
}
