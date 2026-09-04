package com.gios.lightcamera.hw

import com.gios.light.common.hw.LightKey

/**
 * A real two-stage shutter release, out of two ordinary key events.
 *
 * The LPIII's camera button has two detents and reports them as separate keys: `FOCUS` at
 * the half press and `CAMERA` at the bottom. That is the same signal a Sony body sends
 * over its shutter switch, so the same behaviour is available — half press to acquire and
 * lock focus, press through to release — provided three awkward facts are handled.
 *
 *  1. **The order varies.** A full press produces both keys, and which arrives first is
 *     not stable between presses. So the machine can't assume FOCUS precedes CAMERA; it
 *     has to tolerate a half press that arrives *after* the shutter has already fired,
 *     and swallow it rather than kicking off a pointless autofocus.
 *  2. **Neither key repeats.** Holding the button down produces one DOWN and, much later,
 *     one UP. Anything that depends on duration has to be timed by the caller.
 *  3. **UP order varies too**, so the release is only over once both keys are up.
 *
 * Deliberately free of Android imports: this is the part where a subtle mistake means the
 * shutter fires twice or the focus lock never lets go, and it is worth being able to test
 * off-device.
 */
class ShutterRelease(
    private val onHalfPress: () -> Unit,
    private val onFullPress: () -> Unit,
    private val onRelease: () -> Unit,
    /**
     * The bottom detent let go, whether or not the finger is still resting at the half press.
     * This is the edge a hold-to-burst clock must stop on: [onRelease] waits for *both* keys,
     * and a thumb that eases back to the half detent after a shot — the natural thing to do
     * with a two-stage button — would otherwise keep the burst running while it aims.
     */
    private val onFullRelease: () -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private var focusDown = false
    private var cameraDown = false

    /**
     * When the shutter last fired, so a trailing half press for the same press is ignored.
     *
     * [NEVER] is checked for explicitly rather than compared against: `now - Long.MIN_VALUE`
     * overflows to a negative number, which read as "the shutter fired a moment ago" and
     * swallowed the very first half press of the app's life.
     */
    private var firedAt = NEVER

    /** True while the current press has already released the shutter. */
    private var fired = false

    fun onKey(key: LightKey, down: Boolean): Boolean {
        when (key) {
            LightKey.Focus -> if (down) focusDown() else focusUp()
            LightKey.Camera -> if (down) cameraDown() else cameraUp()
            else -> return false
        }
        return true
    }

    private fun focusDown() {
        if (focusDown) return
        focusDown = true
        // The other half of a full press whose CAMERA key won the race. Autofocusing now
        // would hunt the lens immediately after the photo was taken.
        if (fired) return
        if (firedAt != NEVER && nowMs() - firedAt < SAME_PRESS_MS) return
        onHalfPress()
    }

    private fun cameraDown() {
        if (cameraDown) return
        cameraDown = true
        if (fired) return
        // **Switch bounce.** The bottom detent is a mechanical contact and can report
        // DOWN-UP-DOWN inside a few milliseconds of one press. When FOCUS is already held the
        // `fired` flag above covers it; when CAMERA won the race and FOCUS has not landed yet,
        // the UP in the middle has already settled the press, so the second DOWN would fire
        // the shutter again. No finger presses twice in this window, so it is not a press.
        if (firedAt != NEVER && nowMs() - firedAt < BOUNCE_MS) return
        fired = true
        firedAt = nowMs()
        onFullPress()
    }

    private fun focusUp() {
        focusDown = false
        settle()
    }

    private fun cameraUp() {
        if (!cameraDown) return
        cameraDown = false
        onFullRelease()
        settle()
    }

    /**
     * The press is over only when nothing is held. Sony keeps focus locked for as long as
     * the button is at the half detent, and so does this: [onRelease] is what drops the
     * lock, and it must not fire while the finger is still on the button.
     */
    private fun settle() {
        if (focusDown || cameraDown) return
        val hadFired = fired
        fired = false
        onRelease()
        if (!hadFired) return
        // Leave firedAt alone; a stray FOCUS arriving a few ms after both keys came up is
        // still the tail of the press that just fired.
    }

    private companion object {
        /** How long after the shutter a FOCUS key still counts as the same press. */
        const val SAME_PRESS_MS = 500L

        /** A second CAMERA DOWN this soon after the shutter is contact chatter, not a press. */
        const val BOUNCE_MS = 150L

        const val NEVER = Long.MIN_VALUE
    }
}
