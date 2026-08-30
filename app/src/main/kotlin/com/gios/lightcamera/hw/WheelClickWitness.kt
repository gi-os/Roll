package com.gios.lightcamera.hw

import android.os.SystemClock

/**
 * Whether a wheel click has ever reached this app, and how long ago.
 *
 * A diagnostic, and it exists because this exact question cost days. The wheel click is the one
 * control on this phone that another app decides whether you get: LightControl runs as an
 * accessibility service with `flagRequestFilterKeyEvents`, which means it sees keys *before* the
 * focused window and can swallow them, and its factory default binds the click to the torch. So a
 * click that does nothing here has two completely different causes with identical symptoms —
 * either the key never arrived, or it arrived and what it is bound to did nothing visible — and
 * from the phone there is no way to tell them apart.
 *
 * One timestamp settles it. If the settings row says the click has never been seen, the key is
 * being taken upstream and the fix is in LightControl's per-app list. If it says two seconds ago,
 * the key is arriving and the binding is the thing to look at.
 *
 * Deliberately not persisted, and deliberately measured from the app opening rather than from
 * boot: "never, since you opened Roll" is the useful window. A timestamp from last Tuesday would
 * answer a question nobody is asking.
 */
object WheelClickWitness {

    @Volatile
    private var lastAt = 0L

    @Volatile
    private var since = SystemClock.elapsedRealtime()

    /** Called from the key dispatcher on every wheel click, arriving is the whole point. */
    fun seen() {
        lastAt = SystemClock.elapsedRealtime()
    }

    /** Restart the window. Called when the camera comes to the front. */
    fun watchFrom() {
        since = SystemClock.elapsedRealtime()
        lastAt = 0L
    }

    /** Seconds since the last click reached us, or null if none has. */
    fun secondsAgo(): Long? {
        val at = lastAt
        if (at == 0L) return null
        return (SystemClock.elapsedRealtime() - at) / 1000L
    }

    /** How long we have been watching, in seconds. */
    fun watchingForSeconds(): Long = (SystemClock.elapsedRealtime() - since) / 1000L

    /** The readout, in words. */
    fun readout(): String = when (val ago = secondsAgo()) {
        null -> "never. Not once since Roll opened ${watchingForSeconds()}s ago. Something " +
            "upstream is taking it: LightControl's per-app list has to give Roll the whole " +
            "wheel, not just its turns."
        0L -> "just now. The key is reaching Roll, so anything not happening is the binding"
        else -> "${ago}s ago. The key is reaching Roll, so anything not happening is the binding"
    }
}
