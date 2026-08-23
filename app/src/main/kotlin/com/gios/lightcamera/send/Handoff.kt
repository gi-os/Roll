package com.gios.lightcamera.send

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Handing photographs to a messaging app, addressed to one person.
 *
 * **Why this isn't the system share sheet.** Android's chooser answers "which app?", and on a
 * Light Phone that is the wrong question — there are three apps and you already know which
 * one. The question you actually have is "who?", and the platform will not let a third-party
 * app ask it: sharing shortcuts (the row of faces in the stock chooser) are published by the
 * sending app for the system's own UI, and there is no API to read another app's. So the
 * picker owns the address book itself ([ContactsRepo]) and this turns a person into an intent.
 *
 * **The intent shape is the AOSP messaging convention**, deliberately, rather than something
 * private between these two apps: `ACTION_SEND`, an image mime type, and the recipient in an
 * `address` extra. (Written without the wildcard on purpose: Kotlin nests block comments, so a
 * literal slash-star inside a doc comment opens one that never closes and the file stops
 * compiling at its last line, several hundred lines from the cause.) Sticking to it means the same intent is understood by LightChat *and* by a
 * stock SMS app, so the fallback in [send] costs nothing to support.
 */
object Handoff {

    private const val TAG = "Handoff"

    /** Giovanni's iMessage client, and the preferred destination. */
    const val LIGHT_CHAT = "com.gios.lightchat"

    /**
     * The recipient, as the AOSP Messaging app has read it since 2010 and as LightChat reads
     * it. Not `EXTRA_PHONE_NUMBER` — that one is defined for `ACTION_DIAL`, and a messaging
     * app has no reason to look at it.
     */
    const val EXTRA_ADDRESS = "address"

    /**
     * The chat room, for a group.
     *
     * Private between this app and LightChat, and it has to be: AOSP's model of a recipient is
     * an address, and a group iMessage does not have one. It is a room on the server whose
     * membership is a property of the room rather than a way to reach it, so there is no
     * convention to follow here and nothing else on the phone would know what to do with it.
     * That is also why a group send never falls back to another app (see [sendToGroup]) — the
     * one thing every other receiver would do with this intent is ignore the extra and lose the
     * group.
     */
    const val EXTRA_CHAT_GUID = "chat_guid"

    sealed interface Outcome {
        /** Opened [pkg] with the photographs attached and the recipient filled in. */
        data class Sent(val pkg: String) : Outcome

        /**
         * No app on the phone would take an addressed image send, so the system chooser was
         * opened instead and the user still has to pick the person inside whatever they choose.
         */
        data object Chooser : Outcome

        /**
         * The send did not happen.
         *
         * [fault] separates the two kinds, and the distinction is the whole reason it exists: a
         * send with nothing selected, or to a group with no thread, is the app correctly refusing
         * something impossible. A `startActivity` that threw is the app being broken. Only the
         * second is worth raising the SEND ERROR? chip over — a chip that appeared every time you
         * tapped send on an empty selection would be a chip nobody reads.
         */
        data class Failed(val why: String, val fault: Boolean = false) : Outcome
    }

    /**
     * Sends [uris] to [address].
     *
     * Order of preference: LightChat, then any other app that registered for an image send,
     * then the chooser. The middle case matters more than it looks — it's what makes this
     * useful on a phone where LightChat isn't installed yet, instead of a picker that ends in
     * an error.
     */
    fun send(context: Context, uris: List<Uri>, to: Address): Outcome {
        if (uris.isEmpty()) return Outcome.Failed("Nothing to send")
        val base = intentFor(context, uris, to)

        val takers = runCatching {
            // MATCH_DEFAULT_ONLY, so the list agrees with what `startActivity` would actually
            // launch — flags of 0 can return an activity that then can't be started.
            context.packageManager
                .queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())

        /**
         * **Only an app that can be expected to read the recipient gets the explicit send.**
         *
         * Taking the first thing that resolves was wrong in a way that lied to the user: a
         * gallery editor, a wallpaper cropper, a file manager and a cloud drive all register for
         * an image send and none of them looks at an `address` extra. The photograph went
         * somewhere with no recipient attached, and this reported success — so the picker closed,
         * recorded the person as recently sent to, and said nothing. Believing a photograph went
         * to Alex when it went into Drive is worse than being asked to pick an app.
         *
         * "Can be expected to" means LightChat, or something that also handles the messaging
         * scheme for this kind of address. Everything else falls through to the chooser, which at
         * least tells the truth about the recipient being lost.
         */
        val target = when {
            LIGHT_CHAT in takers -> LIGHT_CHAT
            else -> messagingApps(context, to).firstOrNull { it in takers }
        }

        if (target != null) {
            val explicit = intentFor(context, uris, to).apply {
                setPackage(target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = runCatching { context.startActivity(explicit) }
                .onFailure { Log.w(TAG, "explicit send to $target failed: $it") }
                .isSuccess
            if (ok) return Outcome.Sent(target)
        }

        // Nothing took it. The chooser can't be addressed, so the person is lost here — but a
        // photograph that reaches an app the user then addresses by hand beats a dead end.
        val chooser = Intent.createChooser(intentFor(context, uris, to), "Send photo")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(chooser) }
            .fold(
                onSuccess = { Outcome.Chooser },
                onFailure = { Outcome.Failed("Nothing on the phone takes photos", fault = true) },
            )
    }

    /**
     * Sends [uris] to [group].
     *
     * **No chooser, and no second-choice app.** The address send below can fall through to any
     * messaging app because an `address` extra is a convention every one of them understands;
     * a chat guid is understood by exactly one package on the phone. Handing this intent to
     * anything else would strip the recipient and drop the photographs into a thread the user
     * never chose — the same lie the address path was fixed for, except worse, because a group
     * has no address for the receiving app to fall back to and the send would look like it
     * worked. So if LightChat won't take it, this says so and nothing happens.
     */
    fun sendToGroup(context: Context, uris: List<Uri>, group: Group): Outcome {
        if (uris.isEmpty()) return Outcome.Failed("Nothing to send")
        if (group.guid.isBlank()) return Outcome.Failed("That group has no thread")
        if (!lightChatCanReceive(context)) return Outcome.Failed("LightChat isn't here to take it")
        val intent = intentFor(context, uris, to = null).apply {
            putExtra(EXTRA_CHAT_GUID, group.guid)
            setPackage(LIGHT_CHAT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { Outcome.Sent(LIGHT_CHAT) },
                onFailure = {
                    Log.w(TAG, "group send failed: $it")
                    Outcome.Failed("Couldn't open LightChat", fault = true)
                },
            )
    }

    /**
     * The packages that handle *addressed messages* of this kind — `smsto:` for a number,
     * `mailto:` for an email address. Being registered for one of these is the closest thing
     * Android offers to "this app knows what a recipient is"; a gallery editor is not.
     */
    private fun messagingApps(context: Context, to: Address): List<String> {
        val scheme = when (to.kind) {
            Address.Kind.Phone -> "smsto:"
            Address.Kind.Email -> "mailto:"
        }
        val probe = Intent(Intent.ACTION_SENDTO, Uri.parse(scheme))
        return runCatching {
            context.packageManager
                .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }

    /** Whether LightChat is installed *and* can currently receive an image. */
    fun lightChatCanReceive(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage(LIGHT_CHAT)
        }
        return runCatching {
            context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * The intent itself.
     *
     * Two things here are load-bearing and neither is obvious:
     *
     * **`ClipData` as well as the extras.** `FLAG_GRANT_READ_URI_PERMISSION` grants the URI in
     * the intent's `data` and every URI in its `ClipData` — it does *not* walk
     * `EXTRA_STREAM`. A single-image send usually works anyway, because the receiver holds
     * `READ_MEDIA_IMAGES` and reads the MediaStore row under its own permission; a receiver
     * that doesn't gets a `SecurityException` per photograph, and the failure looks like the
     * image being corrupt rather than unreadable. Putting the URIs in the ClipData too makes
     * the grant real and costs one object.
     *
     * **One photograph is `ACTION_SEND`, several is `ACTION_SEND_MULTIPLE`.** They are
     * different actions with different extras (`EXTRA_STREAM` as a `Uri` versus an
     * `ArrayList<Uri>`), and an app that only registered for the singular action must not be
     * sent the plural one — it either resolves to nothing or receives an extra of the wrong
     * type and reads null.
     */
    private fun intentFor(context: Context, uris: List<Uri>, to: Address?): Intent {
        val multiple = uris.size > 1
        return Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            // **Asked, not assumed.** The roll shows every image on the device by default, so a
            // selection can be a screenshot (PNG) or a mix of formats. Declaring `image/jpeg`
            // over a PNG contradicts the ClipDescription — which reads the real type from the
            // provider — and a receiver that trusts the intent's type will either re-encode it or
            // refuse it. One mixed selection can only honestly be described as `image/*`.
            type = if (multiple) {
                "image/*"
            } else {
                context.contentResolver.getType(uris.first()) ?: "image/*"
            }
            if (multiple) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            } else {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
            // **The recipient goes in the extra that matches its kind.** `address` is the SMS
            // convention and means nothing to a mail client; putting an email address in it hands
            // a messaging app something it will try to text.
            // Null for a group, whose recipient is the guid the caller attaches instead — there
            // is no address to put here and inventing one would address a person.
            when (to?.kind) {
                Address.Kind.Phone -> putExtra(EXTRA_ADDRESS, to.raw)
                Address.Kind.Email -> putExtra(Intent.EXTRA_EMAIL, arrayOf(to.raw))
                null -> Unit
            }
            // A real resolver, not null: `newUri` asks it for the URI's mime type and throws on
            // a content:// URI without one.
            clipData = ClipData.newUri(context.contentResolver, "photo", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
