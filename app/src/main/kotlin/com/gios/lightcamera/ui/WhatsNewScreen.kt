package com.gios.lightcamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.lightClickable

/**
 * What arrived while you were away, shown once.
 *
 * Roll ships most days and nearly all of it lands on the nightly channel. Somebody on the official
 * channel steps from v2.60 to v3.0 in one move, and finds a wheel that does something new, a meter
 * that was not there before, a second file beside every photograph and a settings tab that has
 * moved. A store listing is not where that gets read. So the app says it once, on the first launch
 * after the update, and then never again.
 *
 * Written the way the release notes are: short sentences, one idea each. A list of what is now
 * possible, not a pitch.
 */
@Composable
fun WhatsNewScreen(onClose: () -> Unit) {
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText("ROLL 3.0", LightTextVariant.Detail)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            LightText(
                "Everything that landed since 2.60. Read it once, then it is gone.",
                LightTextVariant.Detail,
                lighten = true,
            )

            NewsBlock(
                "One press, more than one file",
                "Roll can write a DNG, a PNG and a JPEG from one press. The DNG is the negative " +
                    "and never carries a filter. The roll shows the set as a single photograph, " +
                    "and a corner tag switches between the files. Turn the formats on in " +
                    "Settings, Frame, Files.",
            )
            NewsBlock(
                "The wheel holds any control",
                "Click the wheel to pick a control. Turn to choose it, click to lock it in, then " +
                    "turn to adjust. Filters, exposure, shutter, ISO, zone focus and zoom all " +
                    "ride the same wheel. Tap the icon in the band to lock the wheel on one.",
            )
            NewsBlock(
                "The meter",
                "A ladder of values stands at the edge of the viewfinder with a red needle on " +
                    "it. It shows what the wheel holds and where the setting sits. Drag it to " +
                    "jump. Tap it to lock the dial. It arrives when you turn the wheel and " +
                    "leaves a few seconds later.",
            )
            NewsBlock(
                "Manual exposure",
                "Auto, shutter priority, ISO priority and manual. The wheel carries the half of " +
                    "the exposure that the mode leaves to you. Settings, Camera, Exposure.",
            )
            NewsBlock(
                "Zone focus",
                "Set the distance and shoot without waiting for autofocus, the way a street " +
                    "camera works. Peaking marks the edges that are sharp. The readout says what " +
                    "is in focus, in feet or meters.",
            )
            NewsBlock(
                "The press is the photograph",
                "The shutter no longer waits for the last shot to finish. Roll takes the frame " +
                    "at your finger and develops it behind you, one at a time. The bar beside " +
                    "the count shows the queue draining. Hold the shutter down for a burst.",
            )
            NewsBlock(
                "A map of the roll",
                "The roll has a map scope beside Camera and Starred. Photographs sit where you " +
                    "took them. Location tagging starts on and turns off in Settings, Camera.",
            )
            NewsBlock(
                "Ten picture adjustments",
                "Exposure, contrast, highlights, shadows, vibrance, warmth, tint, sharpness, " +
                    "grain and vignette, in Settings, Look, Picture. With all ten at zero, Roll " +
                    "writes the file without changes.",
            )
            NewsBlock(
                "Every filter, on a real photograph",
                "Settings, Look, View Filters shows the whole catalog, rendered live. Tap one to " +
                    "take it off the wheel or put it back. The wheel and the grid follow.",
            )
            NewsBlock(
                "It tells you when something goes wrong",
                "A small mark in the corner counts camera faults and fades after ten seconds. " +
                    "Tap it to read them. Shake the phone twice to send a report with a " +
                    "screenshot. A crash from the last run reports itself on the next launch.",
            )

            LightText(
                "The full notes are on the release page.",
                LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 20.dp),
            )
            LightText(
                text = "START",
                variant = LightTextVariant.Button,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onClose() }
                    .padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun NewsBlock(title: String, body: String) {
    Column(Modifier.padding(top = 18.dp)) {
        LightText(title, LightTextVariant.Copy)
        LightText(
            body,
            LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
