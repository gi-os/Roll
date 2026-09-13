package com.gios.lightcamera.hw

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Keep the processor awake while a photograph is being written.
 *
 * **The press is over long before the file exists.** Roll hands every capture to the darkroom and
 * returns, which is what makes the shutter quick: the decode, the shader pass, the encode and the
 * MediaStore write all happen behind the viewfinder, and on this phone that is most of a second
 * for one frame and several for a queue. Turn the screen off inside that window — the deliberate
 * press of the power key, since the window is flagged to never dim on its own — and the activity
 * stops, the processor is free to suspend, and the work that was nearly finished simply stops
 * where it is. It resumes the next time something wakes the phone, which may be minutes later and
 * may be never if the app is killed first.
 *
 * A `PARTIAL_WAKE_LOCK` is the narrow tool for exactly this: the screen and the keyboard stay off,
 * the processor keeps running. It is held only while the darkroom has work and dropped the moment
 * the queue drains, so the cost is bounded by the thing it is protecting.
 *
 * **Not reference counted, and given a ceiling.** A counted lock leaks on any path that acquires
 * without releasing, and the release here lives in a `finally` in one loop, so a plain flag is
 * both simpler and easier to reason about. The timeout is the backstop underneath that: if this
 * class is ever wrong about when a queue is empty, the platform drops the lock for us instead of
 * leaving a phone that cannot sleep.
 *
 * Related: [[doze-background-work]] — a wake lock keeps the processor awake, which is a different
 * thing from a foreground service keeping the process alive. A develop that outlives the process
 * itself is not something this fixes.
 */
class SaveLock(context: Context) {

    /**
     * Long enough for any queue this app can build — the darkroom is capped at thirty-two frames
     * and drains in well under a minute — and short enough that a bug cannot flatten a battery.
     */
    private val ceilingMs = 3 * 60 * 1000L

    private val lock: PowerManager.WakeLock? = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // The tag is what shows up in a battery report, so it names the app and the job.
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roll:darkroom").apply {
            setReferenceCounted(false)
        }
    }.onFailure { Log.e(TAG, "no wake lock available; saves will pause with the screen off", it) }
        .getOrNull()

    /** Hold it, if it is not already held. Safe to call on every job. */
    fun hold() {
        val lock = lock ?: return
        if (lock.isHeld) return
        runCatching { lock.acquire(ceilingMs) }
            .onFailure { Log.e(TAG, "could not hold the save lock", it) }
    }

    /** Let it go. Safe to call when it was never held. */
    fun release() {
        val lock = lock ?: return
        if (!lock.isHeld) return
        runCatching { lock.release() }
            .onFailure { Log.e(TAG, "could not release the save lock", it) }
    }

    private companion object {
        const val TAG = "SaveLock"
    }
}
