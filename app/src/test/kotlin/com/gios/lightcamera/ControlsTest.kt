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
        assertEquals(PressAction.Torch.name, Binding.WheelClick.default)
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
            PressAction.Torch,
            Controls.pressNow(Binding.WheelClick, PressAction.Torch, clipPlaying = true),
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
