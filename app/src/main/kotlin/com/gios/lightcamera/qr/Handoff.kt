package com.gios.lightcamera.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * The two things a scanned code can do off this phone: be opened, or be copied.
 *
 * Deliberately thin. Everything that decides *what* a payload is lives in [Codes], which has no
 * Android in it and is tested; this file is only the platform call, and the only judgement it makes
 * is that a failure to resolve is reported rather than crashing the viewfinder.
 */
object CodeHandoff {

    private const val TAG = "CodeHandoff"

    /**
     * Hand the payload to whatever the phone has for it.
     *
     * `FLAG_ACTIVITY_NEW_TASK` because this is launched from the application context — the view
     * model has no activity, and without the flag the platform refuses the start outright.
     *
     * `resolveActivity` is not consulted first, on purpose: from Android 11 it answers null for
     * anything outside the manifest's `<queries>`, so checking would report "nothing opens that" on
     * a phone that opens it perfectly well. Try it and catch the refusal instead — the failure is
     * the same either way and this version has no false negatives.
     */
    fun open(context: Context, target: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "no handler for $target", it) }
            .getOrDefault(false)
    }

    /** The payload on the clipboard, verbatim — never the completed URL. */
    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("QR code", text)) }
    }
}

/**
 * The other phone on this phone: Web Tools, which is where a web address actually goes.
 *
 * Two reasons this is named rather than left to [CodeHandoff.open].
 *
 * 1. **The Light Phone III ships with no browser at all.** An `ACTION_VIEW` on an `https` address
 *    resolved to nothing here until Web Tools was installed, so "Open" on a scanned poster was a
 *    button that did nothing. Naming the package skips the question.
 * 2. **A chooser is worse than a wrong guess on a 3.92" panel.** Once a second app can answer for
 *    `https` — a shop app, a pass, anything with a BROWSABLE filter — an unnamed intent puts a
 *    list in front of someone who is standing in front of a poster. Web Tools is the browser this
 *    family of apps has; it gets asked by name, and the general intent stays as the fallback for
 *    a phone where it is not installed.
 *
 * Neither call checks whether Web Tools is there first. `startActivity` on an absent package
 * throws, that throw is the same answer a check would have given, and it has no false negatives —
 * the same reasoning as [CodeHandoff.open] and the same reason both are wrapped in `runCatching`.
 * [installed] exists only so a *label* can say where a row is about to go; it is never a gate.
 */
object WebTools {

    const val PACKAGE = "com.gios.webtools"

    private const val TAG = "WebTools"

    /** Only for wording a row. Needs the `<queries>` entry in the manifest to answer truthfully. */
    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Open one address in Web Tools: its shelf tool if a wall covers the host, else a loose page. */
    fun open(context: Context, url: String): Boolean =
        send(context, Uri.parse("webtools://go").buildUpon().appendQueryParameter("u", url).build())

    /**
     * Hand over a scanned code whole, for Web Tools to make a tool out of.
     *
     * The payload travels **verbatim**, not as a parsed tool: the reader is Roll and the parser is
     * Web Tools, and a code that is a login, a part of a larger code, or simply malformed is all
     * one shape from here. Anything else would mean two parsers of the same format in two apps,
     * drifting apart at whatever rate the companion page changes.
     */
    fun add(context: Context, payload: String): Boolean =
        send(context, Uri.parse("webtools://code").buildUpon().appendQueryParameter("c", payload).build())

    private fun send(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "web tools did not take $uri", it) }
            .getOrDefault(false)
    }
}
