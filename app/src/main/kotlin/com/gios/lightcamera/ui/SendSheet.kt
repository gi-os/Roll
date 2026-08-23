package com.gios.lightcamera.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.report.Trouble
import com.gios.lightcamera.send.ContactsRepo
import com.gios.lightcamera.send.Group
import com.gios.lightcamera.send.Groups
import com.gios.lightcamera.send.GroupsRepo
import com.gios.lightcamera.send.Handoff
import com.gios.lightcamera.send.Recipient
import com.gios.lightcamera.send.Recipients
import com.gios.lightcamera.ui.theme.LightIcon
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.designVerticalPxToDp
import com.gios.lightcamera.ui.theme.gridUnitsAsDp
import com.gios.lightcamera.ui.theme.lightClickable
import com.gios.lightcamera.ui.theme.lightTextStyle

/**
 * **Who**, not which app.
 *
 * The system share sheet asks which application should receive a photograph, which on a phone
 * with three applications is a question with an obvious answer wrapped in a grid of icons —
 * and it is a colour Material bottom sheet on a monochrome panel, so it also looks like
 * somebody else's software. The question actually being asked is who the photograph is for.
 *
 * Android will not let a third-party app ask that: the row of faces at the top of the stock
 * chooser is built from *sharing shortcuts*, which an app publishes for the system's own UI,
 * and there is no API to read another app's. So this owns the address book itself and hands
 * the result to a messaging app already addressed. See [Handoff].
 *
 * A full screen, not a sheet — LightOS has no bottom sheets, and something that half-covers a
 * photograph is a Material idiom rather than a Light one.
 *
 * **Groups sit above the address book**, because they are not in it and never can be — a group
 * iMessage is a room on the server, not a person with a number. They come from LightChat's own
 * list (see [GroupsRepo]); on a phone without it there are none and this screen is exactly what
 * it was before.
 */
@Composable
fun SendSheet(
    photos: List<Photo>,
    recentKeys: List<String>,
    onRemember: (String) -> Unit,
    onNotice: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val colours = LightThemeTokens.colors

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    /**
     * **Refused twice means the dialog is gone for good**, and the button that asks for it
     * becomes permanently inert with nothing to explain why. Android reports that state only
     * indirectly: after a denial, `shouldShowRequestPermissionRationale` goes *false* — the
     * system is saying it will no longer ask. So the button changes to one that opens the app's
     * own settings page, which is the only route left.
     */
    val activity = context as? android.app.Activity
    var blocked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok && activity != null) {
            blocked = !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
        }
    }
    // Granting from the settings page happens outside this app, so the answer has to be re-read
    // on the way back rather than waiting for another tap.
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) blocked = false
        onPauseOrDispose { }
    }

    var all by remember { mutableStateOf<List<Recipient>?>(null) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        all = ContactsRepo(context).load()
    }

    // Groups need no permission of ours — they come out of LightChat's provider, not the
    // address book — but they are loaded on the same trigger anyway, because they are rendered
    // above a list that isn't there until contacts are granted. Read once per opening rather
    // than cached: the whole list is four fields per group, and a stale one would offer a
    // thread that has since been renamed.
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        groups = GroupsRepo(context).load()
    }

    var query by remember { mutableStateOf("") }

    /**
     * The person picked, waiting on confirmation.
     *
     * Tapping a name used to send immediately, which put the only irreversible step in the
     * flow behind the same gesture as scrolling past somebody. A misplaced thumb sent a
     * photograph to the wrong person, and there is no unsend. So a tap now *chooses*, and
     * sending is its own deliberate act.
     */
    var chosen by remember { mutableStateOf<Choice?>(null) }

    // Back steps out of the choice before it steps out of the picker — one level at a time,
    // the same expectation as everywhere else. Only then does it close, landing where you
    // were rather than on the photograph behind it.
    BackHandler(enabled = true) {
        if (chosen != null) chosen = null else onClose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colours.background)
            // The picker is drawn over the viewer, and Compose does not consume touches for a
            // background — without this, taps fall through to the photograph underneath.
            .swallowTaps(),
    ) {
        // ---- header ----------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(3f.gridUnitsAsDp())
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeIcon(icon = LightIcons.Back, onClick = onClose)
            Spacer(Modifier.weight(1f))
            LightText(
                text = if (photos.size == 1) "SEND TO" else "SEND ${photos.size} TO",
                variant = LightTextVariant.Detail,
            )
            Spacer(Modifier.weight(1f))
            // Balances the back chevron so the title sits on the centre line rather than
            // being pushed off it by an icon on one side only.
            Spacer(Modifier.width(2f.gridUnitsAsDp()))
        }

        // **Who this is going to, and the two ways out of it.**
        //
        // Pinned under the header rather than floating over the list, so it is the first
        // thing read on the way down and it doesn't cover the name it is talking about. The
        // list stays live underneath: tapping somebody else moves the choice rather than
        // making you cancel first.
        val picked = chosen
        if (picked != null) {
            // A person can be picked and still have nowhere to send to; a group always has its
            // room. Resolved once here so the confirmation line, the dimming and the send all
            // read the same answer.
            val address = (picked as? Choice.Person)?.who?.forPhoto
            val sendable = picked is Choice.Chat || address != null
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp())
                    .padding(bottom = 8.dp),
            ) {
                LightText(
                    text = if (photos.size == 1) "SENDING 1 PHOTO TO" else "SENDING ${photos.size} PHOTOS TO",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
                LightText(
                    text = picked.name,
                    variant = LightTextVariant.Subheading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // The number it is actually going to, spelled out. A contact with several is
                // the case where a confirmation step earns its keep — this is the line that
                // catches a photograph about to go to somebody's old landline. A group has no
                // number to check, so it says how many people are about to see the photograph,
                // which is the fact worth a second look on that side.
                LightText(
                    text = when (picked) {
                        is Choice.Chat -> picked.group.subtitle
                        is Choice.Person -> address?.raw ?: "No way to reach them"
                    },
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = "CANCEL",
                        variant = LightTextVariant.Button,
                        lighten = true,
                        modifier = Modifier.lightClickable { chosen = null },
                    )
                    Spacer(Modifier.weight(1f))
                    LightText(
                        text = "SEND",
                        variant = LightTextVariant.Button,
                        // Dimmed rather than hidden when there is nowhere to send: the reason
                        // is on the line above it, and a button that vanishes explains nothing.
                        lighten = !sendable,
                        modifier = Modifier.lightClickable(enabled = sendable) {
                            val uris = photos.map { it.uri }
                            val outcome = when (picked) {
                                is Choice.Chat -> Handoff.sendToGroup(context, uris, picked.group)
                                is Choice.Person ->
                                    address?.let { Handoff.send(context, uris, it) }
                                        ?: return@lightClickable
                            }
                            when (outcome) {
                                is Handoff.Outcome.Sent -> {
                                    // Recents are an address-book idea and groups are ordered by
                                    // their own last activity already, which is a better signal
                                    // than this and doesn't spend one of six slots.
                                    if (picked is Choice.Person) address?.let { onRemember(it.key) }
                                    onClose()
                                }
                                Handoff.Outcome.Chooser -> {
                                    onNotice("Nothing here can address a photo — pick an app")
                                    onClose()
                                }
                                is Handoff.Outcome.Failed -> {
                                    // A notice is 1.4 seconds of grey text at the bottom of the
                                    // viewfinder, and the app says "Copied" and "Timer 3s" the
                                    // same way — so a send that actually broke looked exactly
                                    // like a send that worked. Anything that is our fault goes
                                    // into Trouble as well, which is what raises the SEND ERROR?
                                    // chip and offers to file it with a screenshot attached.
                                    if (outcome.fault) {
                                        Trouble.record("Sending photographs failed", outcome.why)
                                    }
                                    onNotice(outcome.why)
                                }
                            }
                        },
                    )
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = colours.rule,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        when {
            !granted -> Column(
                modifier = Modifier.fillMaxSize().padding(2f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText("Who is it for?", LightTextVariant.Subheading, align = TextAlign.Center)
                LightText(
                    "Roll shows your own contacts here instead of a grid of apps, so it needs to read them. " +
                        "They are read on this phone and nothing is sent anywhere.",
                    LightTextVariant.Paragraph,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (blocked) {
                    LightText(
                        "Contacts are blocked for Roll. Turn them on in the phone's app settings.",
                        LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    LightText(
                        "OPEN SETTINGS",
                        LightTextVariant.Button,
                        modifier = Modifier
                            .padding(top = 20.dp)
                            .lightClickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.parse("package:" + context.packageName)),
                                    )
                                }
                            },
                    )
                } else {
                    LightText(
                        "ALLOW",
                        LightTextVariant.Button,
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .lightClickable { ask.launch(Manifest.permission.READ_CONTACTS) },
                    )
                }
            }

            all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LightText("Reading contacts…", LightTextVariant.Paragraph, lighten = true)
            }

            else -> {
                val loaded = all.orEmpty()
                val filtered = remember(loaded, query) {
                    if (query.isBlank()) loaded else loaded.filter { Recipients.matches(it, query) }
                }
                // Recents only in the resting state. Once something has been typed the user has
                // said who they are looking for, and a "recent" heading above the answer is a
                // second list to read past.
                val ordered = remember(filtered, recentKeys, query) {
                    if (query.isBlank()) {
                        Recipients.ordered(filtered, recentKeys)
                    } else {
                        Recipients.Ordered(emptyList(), filtered)
                    }
                }
                // At rest, the few most recently active. Once something has been typed, every
                // group that matches it — the cap exists to stop groups pushing contacts off
                // the resting screen, and a search has already narrowed the screen.
                val shownGroups = remember(groups, query) {
                    if (query.isBlank()) {
                        groups.take(Groups.RESTING)
                    } else {
                        groups.filter { Groups.matches(it, query) }
                    }
                }

                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                )

                // Tapping a name selects it. Tapping the one already selected clears it, so
                // the gesture that chose is also the gesture that un-chooses.
                val choose: (Choice) -> Unit = { next ->
                    chosen = if (chosen == next) null else next
                }

                if (loaded.isEmpty() && shownGroups.isEmpty()) {
                    EmptyState(
                        text = "No contacts on this phone.",
                        detail = "Add somebody to the address book and they will appear here.",
                    )
                } else if (filtered.isEmpty() && shownGroups.isEmpty()) {
                    EmptyState(text = "Nobody matches “${query.trim()}”.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // Above everything, including recents. A group is the thing you cannot
                        // reach any other way from here, and there are at most five of them.
                        if (shownGroups.isNotEmpty()) {
                            item(key = "groups-heading") { SectionHeading("GROUPS") }
                            items(shownGroups, key = { "group-${it.guid}" }) { group ->
                                val choice = Choice.Chat(group)
                                PickerRow(
                                    title = group.name,
                                    subtitle = group.subtitle,
                                    chosen = chosen == choice,
                                    onClick = { choose(choice) },
                                )
                            }
                            item(key = "groups-rule") {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = colours.rule,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                        }
                        if (ordered.recent.isNotEmpty()) {
                            item(key = "recent-heading") { SectionHeading("RECENT") }
                            items(ordered.recent, key = { "recent-${it.id}" }) { who ->
                                RecipientRow(who, chosen = chosen == Choice.Person(who)) {
                                    choose(Choice.Person(who))
                                }
                            }
                            item(key = "recent-rule") {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = colours.rule,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                        }
                        items(ordered.rest, key = { it.id }) { who ->
                            RecipientRow(who, chosen = chosen == Choice.Person(who)) {
                                choose(Choice.Person(who))
                            }
                        }
                        // The last row clears the gesture strip.
                        item(key = "tail") { Spacer(Modifier.height(4f.gridUnitsAsDp())) }
                    }
                }
            }
        }
    }
}

/**
 * The SDK's text field: a 3-design-pixel rule under the text, no container and no floating
 * label. Material's filled box appears nowhere in LightOS.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colours = LightThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Box {
            if (value.isEmpty()) {
                LightText("Search", LightTextVariant.Copy, lighten = true)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = lightTextStyle(LightTextVariant.Copy).copy(color = colours.content),
                cursorBrush = SolidColor(colours.content),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(
            // 3 design pixels, the SDK's underline weight.
            thickness = 3f.designVerticalPxToDp(),
            color = colours.contentSecondary,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.8f),
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier.padding(start = 1f.gridUnitsAsDp(), top = 8.dp, bottom = 2.dp),
    )
}

/**
 * What the picker is currently pointed at.
 *
 * A sealed pair rather than one nullable-everything row type, because the two cases diverge at
 * exactly one point — the send — and everywhere else they are a name and a second line. Making
 * that divergence a `when` the compiler checks is the cheapest way to be sure a group never
 * takes the address path, which is the failure that would look like success: LightChat would
 * receive the photographs with no recipient and wait for a thread to be opened.
 *
 * Data classes, so equality is by value: the selection is compared against freshly built
 * instances while the list recomposes, and identity would never match.
 */
private sealed interface Choice {
    val name: String

    data class Person(val who: Recipient) : Choice {
        override val name: String get() = who.name
    }

    data class Chat(val group: Group) : Choice {
        override val name: String get() = group.name
    }
}

/**
 * One person. Name over address, which is the SDK's list row — `copy` over `detail`.
 *
 * No avatar. Contact photos are a colour circle on a greyscale panel, and reading them means a
 * bitmap per row out of the contacts provider; a name is what you are reading anyway.
 */
@Composable
private fun RecipientRow(who: Recipient, chosen: Boolean, onClick: () -> Unit) {
    PickerRow(title = who.name, subtitle = who.subtitle, chosen = chosen, onClick = onClick)
}

/**
 * A row in the picker, whoever it names.
 *
 * Groups and people share it deliberately. They are different kinds of destination and the
 * temptation is to mark the difference — an icon, an indent, a different weight — but on this
 * panel that reads as two lists that happen to be adjacent. The heading above already says
 * which is which, and the second line ("4 people" against a phone number) says it again. What
 * the user is doing is picking one name.
 */
@Composable
private fun PickerRow(title: String, subtitle: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(title, LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                LightText(
                    subtitle,
                    LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The same mark the roll uses for a picked frame, so "chosen" looks the same
        // wherever it appears.
        if (chosen) {
            LightIcon(
                icon = LightIcons.SelectOn,
                size = 14.dp,
                tint = LightThemeTokens.colors.content,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
