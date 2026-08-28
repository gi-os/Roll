package com.gios.lightcamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.lightcamera.report.Reports
import com.gios.lightcamera.report.Symptom
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.gridUnitsAsDp
import com.gios.lightcamera.ui.theme.verticalGridUnitsAsDp

/**
 * Why the sheet is up: you shook the phone, the app died the last time you had it open, or the
 * app noticed by itself that something it tried did not work.
 */
enum class ReportReason { Shaken, Crashed, Failed }

/**
 * What went wrong, once you have said you want to tell somebody.
 *
 * This used to open with "did you mean to send an error report?" on its own step, because a shake
 * is a gesture the phone can misread. That question now belongs to [ReportChip] in the corner, so
 * by the time this sheet is on screen the answer is already yes — and it can get straight to the
 * part that carries information.
 *
 * It assumes typing on this phone is expensive: a chip is a complete report on its own, and the
 * note is genuinely optional.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    reason: ReportReason,
    hasScreenshot: Boolean,
    /** What the app already knows went wrong, for a failure it noticed itself. */
    failure: String? = null,
    seedNote: String = "",
    onDismiss: () -> Unit,
    onSend: (symptom: Symptom, note: String, includeScreenshot: Boolean) -> Unit,
) {
    val colors = LightThemeTokens.colors
    var symptom by remember {
        mutableStateOf(if (reason == ReportReason.Crashed) Symptom.Crashed else Symptom.Other)
    }
    var note by remember { mutableStateOf(seedNote) }
    var withScreenshot by remember { mutableStateOf(hasScreenshot) }
    val scroll = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        dragHandle = null,
    ) {
        // The keyboard is a window over this one, and this sheet is inside a scroll, so
        // without an inset the note field slides underneath it as the text grows: the caret
        // stays where it was and the line you are typing disappears behind the keys. Reported
        // as the note line "becoming hidden by keyboard as text becomes longer", which is
        // exactly what it looks like — the field is fine, the sheet simply does not know the
        // bottom of the screen moved.
        //
        // `imePadding` moves the whole column up by the keyboard's height, which is what lets
        // the scroll do the rest: a focused text field inside a vertical scroll is already
        // brought into view by Compose, and it was being brought into a region the keyboard
        // was covering.
        Column(
            Modifier
                .imePadding()
                .verticalScroll(scroll)
                .padding(
                    start = lightInset(),
                    end = lightInset(),
                    top = 1.2f.verticalGridUnitsAsDp(),
                    bottom = 1.5f.verticalGridUnitsAsDp(),
                ),
        ) {
            // Said back as the app's own failure, so it is clear the phone already knows and
            // this is not a question you have to answer from memory.
            if (failure != null) {
                LightText(
                    text = "Roll could not $failure.",
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(bottom = 1f.verticalGridUnitsAsDp()),
                )
            }
            LightText("WHAT HAPPENED", LightTextVariant.Superfine, lighten = true)
            // Two rows of chips rather than a list of full-width rows: five rows would push
            // the note field and the send button off a 3.92" panel.
            Column(
                Modifier.padding(top = 0.5f.verticalGridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(0.5f.verticalGridUnitsAsDp()),
            ) {
                Symptom.entries.chunked(2).forEach { pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(0.6f.gridUnitsAsDp()),
                    ) {
                        pair.forEach { option ->
                            LightChip(
                                label = option.chip,
                                selected = symptom == option,
                                modifier = Modifier.weight(1f),
                            ) { symptom = option }
                        }
                        // Five is odd; the last chip keeps its half rather than stretching.
                        if (pair.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }

            LightText(
                text = "NOTE",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 1.2f.verticalGridUnitsAsDp()),
            )
            LightInlineField(
                value = note,
                onValueChange = { note = it },
                placeholder = "What were you doing? (optional)",
                variant = LightTextVariant.Paragraph,
                modifier = Modifier.padding(top = 0.4f.verticalGridUnitsAsDp()),
            )

            // Typed rather than inferred from an if: a nullable lambda in an expression
            // position is the one place Kotlin reads `{ }` as a block and not a value.
            val toggleScreenshot: (() -> Unit)? =
                if (hasScreenshot) ({ withScreenshot = !withScreenshot }) else null

            LightRule(Modifier.padding(top = 1.2f.verticalGridUnitsAsDp()))
            LightListRow(
                title = "Attach the screenshot",
                sub = if (hasScreenshot) {
                    "The screen as it was when you shook it"
                } else {
                    "Could not be taken this time"
                },
                trailing = if (withScreenshot && hasScreenshot) {
                    LightIcons.SelectOn
                } else {
                    LightIcons.SelectOff
                },
                onClick = toggleScreenshot,
            )
            LightRule()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 1.2f.verticalGridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(0.8f.gridUnitsAsDp()),
            ) {
                LightWideButton(
                    label = "CANCEL",
                    filled = false,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                LightWideButton(
                    label = "SEND",
                    modifier = Modifier.weight(1f),
                    onClick = { onSend(symptom, note, withScreenshot && hasScreenshot) },
                )
            }
            LightText(
                text = if (Reports.canSend()) {
                    "Goes to the private light-reports tracker. The screenshot is the only " +
                        "thing here that can carry what you wrote in a note."
                } else {
                    "This build has no reporting key, so it will wait on the phone until one " +
                        "does."
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.8f.verticalGridUnitsAsDp()),
            )
        }
    }
}
