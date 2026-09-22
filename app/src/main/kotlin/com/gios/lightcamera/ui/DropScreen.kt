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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightcamera.drop.DropScope
import com.gios.lightcamera.drop.WifiDrop
import com.gios.lightcamera.media.Photo
import com.gios.lightcamera.ui.theme.LightIcons
import com.gios.lightcamera.ui.theme.LightText
import com.gios.lightcamera.ui.theme.LightTextVariant
import com.gios.lightcamera.ui.theme.LightThemeTokens
import com.gios.lightcamera.ui.theme.gridUnitsAsDp
import com.gios.lightcamera.ui.theme.lightClickable

/**
 * Send to computer: what to send, then the address to type into a laptop and the four digits
 * that open it.
 *
 * **Two numbers and nothing else, once it is running.** Everything this screen does happens on
 * the other device, so its whole job is to be read across a desk: the URL at the size of a
 * heading, the PIN under it, and one sentence saying what to do with them. No QR code — the
 * computer is the thing that would have to scan it, and laptops do not have cameras pointed at
 * phones.
 *
 * **What to send is asked first, every time.** The server used to put the whole roll on offer
 * the moment it started, which was never the wrong answer for pulling a shoot off and often the
 * wrong one for three photographs from lunch. So before anything listens, the screen asks: the
 * photographs you had selected, or the entire roll. Neither is chosen for you. Arriving from a
 * selection while the server is already up offers the same choice and changes the scope in
 * place, so the address and PIN the laptop already has typed in stay good. See
 * [com.gios.lightcamera.drop.DropScope].
 *
 * **Back leaves it running, Stop sending stops it.** Those are different intentions and
 * collapsing them would break the normal case: you start sending, go back to the camera, and the
 * laptop keeps downloading while you take another photograph. The server closes when you press
 * Stop sending, when ten minutes pass with nobody asking for anything, or when Roll's process
 * dies.
 *
 * @param selected the photographs selected on the roll when this was opened; empty when it was
 *   opened from the bar with nothing selected.
 */
@Composable
fun DropScreen(vm: CameraViewModel, selected: List<Photo>, onClose: () -> Unit) {
    val colours = LightThemeTokens.colors
    val live by vm.drop.collectAsState()
    var refusal by remember { mutableStateOf<String?>(null) }
    // True once a scope has been chosen on *this* visit. Opening the screen while the server is
    // up with nothing selected has nothing to ask, so it goes straight to the address.
    var chosen by remember { mutableStateOf(live != null && selected.isEmpty()) }

    BackHandler(enabled = true) { onClose() }

    // Started from here rather than from the row that opened it, so the one place that can show
    // a refusal is the one place that asked.
    fun choose(scope: DropScope) {
        if (live == null) {
            refusal = (vm.startDrop(scope) as? WifiDrop.Start.Refused)?.why
        } else {
            vm.rescopeDrop(scope)
        }
        chosen = true
    }

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
            LightText(text = "SEND TO COMPUTER", variant = LightTextVariant.Detail)
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
            if (!chosen) {
                ScopeChoice(
                    selected = selected,
                    alreadySending = running?.scope?.label(),
                    onChoose = ::choose,
                )
                return@Column
            }
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
                text = "Open this address in a browser on your computer",
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
                text = "Sending ${running.scope.label()}. " +
                    "Nothing leaves this network and nothing is uploaded anywhere.",
                variant = LightTextVariant.Paragraph,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp),
            )
            LightText(
                text = "Closing this screen keeps sending. It stops on its own after ten " +
                    "quiet minutes.",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            // The other scope is one tap away while it runs, so widening from a few photographs
            // to the roll does not mean stopping and typing a new PIN on the laptop. Narrowing
            // needs a selection, which is what the bar's Computer button brings here.
            if (running.scope is DropScope.Selected) {
                LightText(
                    text = "SEND THE ENTIRE ROLL INSTEAD",
                    variant = LightTextVariant.Detail,
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .lightClickable { vm.rescopeDrop(DropScope.WholeRoll) },
                )
            }

            LightText(
                text = "STOP SENDING",
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

/**
 * The question: what should the computer see?
 *
 * Two answers when there is a selection, one when there is not — and in that case a line saying
 * how to get the other, because "the entire roll" being the only button is not the same as it
 * being the default. Nothing here starts anything; the tap does.
 */
@Composable
private fun ScopeChoice(
    selected: List<Photo>,
    alreadySending: String?,
    onChoose: (DropScope) -> Unit,
) {
    LightText(
        text = "What should your computer see?",
        variant = LightTextVariant.Paragraph,
        align = TextAlign.Center,
    )
    if (alreadySending != null) {
        LightText(
            text = "Already sending $alreadySending. Choosing changes what is sent and keeps " +
                "the same address and PIN.",
            variant = LightTextVariant.Detail,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    if (selected.isNotEmpty()) {
        val scope = DropScope.Selected(selected.map { it.id }.toSet())
        LightText(
            text = scope.label().uppercase(),
            variant = LightTextVariant.Button,
            modifier = Modifier
                .padding(top = 30.dp)
                .lightClickable { onChoose(scope) },
        )
    }
    LightText(
        text = "THE ENTIRE ROLL",
        variant = LightTextVariant.Button,
        modifier = Modifier
            .padding(top = if (selected.isNotEmpty()) 18.dp else 30.dp)
            .lightClickable { onChoose(DropScope.WholeRoll) },
    )
    if (selected.isEmpty()) {
        LightText(
            text = "To send only some, hold a photograph on the roll to select it, then tap " +
                "Computer on the bar.",
            variant = LightTextVariant.Detail,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
