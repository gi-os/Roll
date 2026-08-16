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
        // Was Torch until v2.49. The dial boots locked, so the click has to be the thing that
        // unlocks it or the wheel would never turn anything out of the box.
        assertEquals(PressAction.DialLock.name, Binding.WheelClick.default)
    }

    /* ---------------- the dial lock ---------------- */

    @Test
    fun `the dial is dead while it is locked and something can unlock it`() {
        assertFalse(Controls.dialLive(locked = true, unlockable = true, stripOpen = false))
        assertTrue(Controls.dialLive(locked = false, unlockable = true, stripOpen = false))
    }

    /**
     * The trap this avoids: the dial boots locked, so a mapping with nothing pointed at the lock
     * would be a wheel that never turns anything again — and settings is reached through the mode
     * picker, which is reached with the wheel. Unbinding the lock switches the feature off instead.
     */
    @Test
    fun `a mapping with no way to unlock has no lock`() {
        assertTrue(Controls.dialLive(locked = true, unlockable = false, stripOpen = false))
    }

    /** A strip is a value you opened in order to set. Locking it would be locking the wrong turn. */
    @Test
    fun `an open strip takes the wheel whatever the lock says`() {
        assertTrue(Controls.dialLive(locked = true, unlockable = true, stripOpen = true))
    }

    @Test
    fun `the lock can live on any press, and the default puts it on the click`() {
        assertTrue(
            Controls.dialUnlockable(
                volumeUp = PressAction.Shutter,
                volumeDown = PressAction.Shutter,
                wheelClick = PressAction.DialLock,
            ),
        )
        assertTrue(
            Controls.dialUnlockable(
                volumeUp = PressAction.DialLock,
                volumeDown = PressAction.Shutter,
                wheelClick = PressAction.Torch,
            ),
        )
        assertFalse(
            Controls.dialUnlockable(
                volumeUp = PressAction.Shutter,
                volumeDown = PressAction.Shutter,
                wheelClick = PressAction.Torch,
            ),
        )
    }

    /**
     * The lock must never be able to cost somebody the shutter. It is a separate rule from
     * [Controls.shutterSafe] and they have to agree: binding the lock over the last shutter is
     * refused exactly as any other action would be.
     */
    @Test
    fun `the lock cannot take the last shutter`() {
        assertFalse(
            Controls.shutterSafe(
                volumeUp = PressAction.DialLock,
                volumeDown = PressAction.Torch,
                wheelClick = PressAction.DialLock,
                cameraKeyWorks = false,
            ),
        )
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
