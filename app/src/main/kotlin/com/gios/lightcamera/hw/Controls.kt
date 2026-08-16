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
            WheelClick -> PressAction.Torch.name
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
}
