package com.gios.lightcamera.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the phone is locked right now, as state.
 *
 * `KeyguardManager.isKeyguardLocked` is a question, not a stream, so this asks it at the moments
 * it can change: every RESUME (the app is `showWhenLocked`, so the screen going off and on again
 * resumes it *behind the keyguard*), and the two broadcasts that bracket a lock —
 * `ACTION_SCREEN_OFF`, which is when the keyguard engages, and `ACTION_USER_PRESENT`, which is
 * the unlock. Reading once at composition would miss both.
 */
@Composable
fun rememberPhoneLocked(): State<Boolean> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    val locked = remember { mutableStateOf(keyguard?.isKeyguardLocked == true) }

    DisposableEffect(lifecycleOwner, keyguard) {
        fun refresh() {
            locked.value = keyguard?.isKeyguardLocked == true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return locked
}

/**
 * Ask the phone to unlock itself, and run [onUnlocked] once it has.
 *
 * This is the phone's own screen — its passcode, its swipe — not anything the app draws, which
 * is the whole point: the roll is protected by exactly what protects the rest of the phone. On a
 * keyguard with no credential the dismissal is immediate. A cancelled prompt calls nothing.
 */
fun requestUnlock(context: Context, onUnlocked: () -> Unit) {
    val activity = context.findActivity() ?: return
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return
    if (!keyguard.isKeyguardLocked) {
        onUnlocked()
        return
    }
    keyguard.requestDismissKeyguard(
        activity,
        object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = onUnlocked()
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
