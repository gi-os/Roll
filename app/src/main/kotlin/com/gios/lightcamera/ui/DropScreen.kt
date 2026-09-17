package com.gios.lightcamera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightcamera.drop.WifiDrop
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.gridUnitsAsDp
import com.gios.lightcamera.ui.theme.lightClickable

/**
 * The address to type into a laptop, and the four digits that open it.
 *
 * **Two numbers and nothing else.** Everything this screen does happens on the other device, so
 * its whole job is to be read across a desk: the URL at the size of a heading, the PIN under it,
 * and one sentence saying what to do with them. No QR code — the computer is the thing that would
 * have to scan it, and laptops do not have cameras pointed at phones.
 *
 * **Back leaves it running, Stop stops it.** Those are different intentions and collapsing them
 * would break the normal case: you start the drop, go back to the camera, and the laptop keeps
 * downloading while you take another photograph. The server closes when you press Stop, when ten
 * minutes pass with nobody asking for anything, or when Roll's process dies.
 */
@Composable
fun DropScreen(vm: CameraViewModel, onClose: () -> Unit) {
    val colours = LightThemeTokens.colors
    val live by vm.drop.collectAsState()
    var refusal by remember { mutableStateOf<String?>(null) }

    // Started from here rather than from the picker row that opened it, so the one place that
    // can show a refusal is the one place that asked.
    LaunchedEffect(Unit) {
        refusal = (vm.startDrop() as? WifiDrop.Start.Refused)?.why
    }

    BackHandler(enabled = true) { onClose() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colours.background)
            .swallowTaps(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(3f.gridUnitsAsDp())
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeIcon(icon = LightIcons.Back, onClick = onClose)
            Spacer(Modifier.weight(1f))
            LightText(text = "ON THIS WI-FI", variant = LightTextVariant.Detail)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(2f.gridUnitsAsDp()))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val running = live
            if (running == null) {
                LightText(
                    text = refusal ?: "Starting…",
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                    align = TextAlign.Center,
                )
                if (refusal != null) {
                    LightText(
                        text = "CLOSE",
                        variant = LightTextVariant.Button,
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .lightClickable { onClose() },
                    )
                }
                return@Column
            }

            LightText(
                text = "In a browser on your computer, go to",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
            )
            // The whole reason the server prefers port 8088 and refuses to invent a hostname: this
            // line is copied by eye, one character at a time, onto another keyboard.
            LightText(
                text = running.url.removePrefix("http://"),
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = colours.rule,
                modifier = Modifier.padding(vertical = 22.dp),
            )

            LightText(
                text = "and type this PIN",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
            )
            LightText(
                text = running.pin,
                variant = LightTextVariant.Title,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            LightText(
                text = "Every photo and video the roll is showing, in the browser. " +
                    "Nothing leaves this network and nothing is uploaded anywhere.",
                variant = LightTextVariant.Paragraph,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp),
            )
            LightText(
                text = "Closing this screen leaves it running. It stops on its own after ten " +
                    "quiet minutes.",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )

            LightText(
                text = "STOP",
                variant = LightTextVariant.Button,
                modifier = Modifier
                    .padding(top = 30.dp, bottom = 24.dp)
                    .lightClickable {
                        vm.stopDrop()
                        onClose()
                    },
            )
        }
    }
}
