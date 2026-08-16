package com.gios.lightcamera.hw

/**
 * What each physical control on the phone is pointed at.
 *
 * The app shipped with one mapping, chosen because it is the right one: the wheel walks the
 * filters, holding it in and turning is exposure, clicking it is the torch, and either volume
 * key is a shutter. That is still the default and nothing here changes it.
 *
 * It is a *preference* now because the LPIII's controls are shared with the rest of the phone.
 * LightControl remaps the wheel system-wide, and someone who has given the wheel a job
 * everywhere else wants the volume keys to do what the wheel does here — a case the fixed
 * mapping could not express at all. So the mapping is data, the defaults are the old
 * behaviour, and [Controls.shutterSafe] is the one rule the data has to obey.
 */
enum class Binding(val label: String, val dial: Boolean) {
    VolumeUp("Volume up", dial = false),
    VolumeDown("Volume down", dial = false),
    WheelTurn("Turn the wheel", dial = true),
    WheelPressTurn("Press and turn", dial = true),
    WheelClick("Click the wheel", dial = false),
    ;

    /** Which action this control has when nothing has been changed. */
    val default: String
        get() = when (this) {
            VolumeUp -> PressAction.Shutter.name
            VolumeDown -> PressAction.Shutter.name
            WheelTurn -> DialAction.Filter.name
            WheelPressTurn -> DialAction.Exposure.name
            // **Changed in v2.49, and it displaced the torch.** The wheel is shared with the rest of
            // the phone and turns in a pocket, so the dial now boots locked and a click is what
            // unlocks it — which only works if something is pointed at [PressAction.DialLock] out of
            // the box. The click is the only control the report asked for and the only one already
            // under the finger that turns the wheel. The torch is still here and can be bound to
            // either volume key.
            WheelClick -> PressAction.DialLock.name
        }
}

/**
 * Something a press can be pointed at.
 *
 * [Exposure] and [Zoom] open the strip rather than nudging the value: a press is one event and a
 * strip is a control you then drag, which is the whole point of putting them on a button.
 */
enum class PressAction(val label: String) {
    Shutter("Shutter"),
    Torch("Torch"),
    FlipLens("Front / rear"),
    NextMode("Next mode"),
    Timer("Self timer"),
    Exposure("Exposure strip"),
    Zoom("Zoom strip"),
    /**
     * Lock the dial, or unlock it. See [Controls.dialLive].
     *
     * A toggle rather than a hold: the wheel is not a control you keep a finger on, and a
     * press-and-hold would collide with press-and-turn, which is a binding of its own.
     */
    DialLock("Lock the dial"),
    Nothing("Nothing"),
    ;

    companion object {
        fun byName(name: String?): PressAction =
            entries.firstOrNull { it.name == name } ?: Nothing
    }
}

/** Something a dial — a thing that reports notches — can be pointed at. */
enum class DialAction(val label: String) {
    Filter("Filter"),
    Exposure("Exposure"),
    Zoom("Zoom"),
    Nothing("Nothing"),
    ;

    companion object {
        fun byName(name: String?): DialAction =
            entries.firstOrNull { it.name == name } ?: Nothing
    }
}

object Controls {

    /**
     * **Is there still a way to take a photograph?**
     *
     * The one rule the mapping has to obey, and it is not a style question: this app has no
     * shutter button on the screen. Bind both volume keys to something else on a phone whose
     * camera key is being swallowed by an accessibility service — which is the ordinary state of
     * affairs for anyone running LightControl — and the camera has no shutter at all, with
     * nothing on the panel to press and no way to get back into settings to undo it except by
     * guessing.
     *
     * So: a shutter on the camera key counts, and a shutter on either volume key counts. If
     * neither is true the mapping is refused.
     *
     * @param cameraKeyWorks whether the camera button's events are reaching this app. See
     *   [CameraKeyAdvice], which answers it by looking for a service that binds the key.
     */
    /**
     * What a press does *right now*, which is not always what it is bound to.
     *
     * One case, and it only exists because this app takes the keys before anything else can: both
     * volume keys are a shutter by default, so with a clip playing in the viewer there was no way
     * to change its volume — the keys were being spent on a shutter belonging to a viewfinder that
     * is not even on screen.
     *
     * [PressAction.Nothing] is not "do nothing" here. `LightControls` hands an unbound key back to
     * the system rather than swallowing it, which is precisely "let the phone change the volume",
     * so this needs no new plumbing and no new control on the panel.
     *
     * Volume keys only. The wheel and its click have no system meaning to give back, and the
     * camera key is not remappable in the first place. The shutter is unaffected everywhere else,
     * so [shutterSafe] still holds: nothing plays outside the viewer.
     */
    fun pressNow(binding: Binding, bound: PressAction, clipPlaying: Boolean): PressAction =
        if (clipPlaying && (binding == Binding.VolumeUp || binding == Binding.VolumeDown)) {
            PressAction.Nothing
        } else {
            bound
        }

    fun shutterSafe(
        volumeUp: PressAction,
        volumeDown: PressAction,
        wheelClick: PressAction,
        cameraKeyWorks: Boolean,
    ): Boolean = cameraKeyWorks ||
        volumeUp == PressAction.Shutter ||
        volumeDown == PressAction.Shutter ||
        wheelClick == PressAction.Shutter

    /* ---------------- the dial lock ---------------- */

    /**
     * **Is there a way to unlock the dial?**
     *
     * The same shape of rule as [shutterSafe], and it exists for the same reason. The dial boots
     * locked, so a mapping with nothing pointed at [PressAction.DialLock] would be a wheel that
     * never turns anything again — and the way back into settings is the mode picker, which is
     * reached by the wheel.
     *
     * Rather than refuse such a mapping, the lock **switches itself off**: see [dialLive]. Unbinding
     * the lock is a perfectly reasonable way to say "I never wanted this feature", and refusing it
     * would make the one control you can always reach the one you cannot give away.
     */
    fun dialUnlockable(
        volumeUp: PressAction,
        volumeDown: PressAction,
        wheelClick: PressAction,
    ): Boolean = volumeUp == PressAction.DialLock ||
        volumeDown == PressAction.DialLock ||
        wheelClick == PressAction.DialLock

    /**
     * Whether a bare turn of the wheel should reach whatever it is pointed at.
     *
     * The wheel is shared with the rest of the phone and it turns in a pocket, so a filter — a
     * decision about one photograph — was being changed by a camera being carried. Locked, a turn
     * moves nothing and says so on the panel instead.
     *
     * Two things deliberately ignore the lock, and both are gestures you could not make by
     * accident: **press-and-turn**, which needs the wheel held in, and **an open strip**, which is a
     * value you opened in order to set. The lock is for the bare turn, which is the only one a
     * pocket can produce.
     *
     * @param locked the live state, which starts true at every launch and is not remembered.
     * @param unlockable whether any press can undo it — see [dialUnlockable].
     */
    fun dialLive(locked: Boolean, unlockable: Boolean, stripOpen: Boolean): Boolean =
        !locked || !unlockable || stripOpen
}
