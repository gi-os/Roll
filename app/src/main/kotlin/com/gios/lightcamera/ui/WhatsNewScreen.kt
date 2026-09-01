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
                "It has been a while since 2.60. Thirty-nine nightly builds, to be exact. " +
                    "Here is what changed.",
                LightTextVariant.Detail,
                lighten = true,
            )

            NewsBlock(
                "The wheel is the whole game now",
                "Click the wheel and a list of controls spins up: filters, exposure, shutter, " +
                    "ISO, focus, zoom. Turn to pick one, click again, and the wheel is yours to " +
                    "adjust. Want it locked on a single control? Tap its icon in the band. A " +
                    "pocket bump will not move it after that.",
            )
            NewsBlock(
                "A proper meter",
                "Turn the wheel and a ladder of numbers slides down the edge of the frame. A red " +
                    "needle points at your setting, or really just the tip of one, swinging in " +
                    "from off screen. Drag the ladder to jump around, tap it to lock the dial, " +
                    "and it tucks itself away after a few seconds. Zoom reads like a lens barrel " +
                    "now: 1.0 at the bottom, 8.0 at the top.",
            )
            NewsBlock(
                "One press, three files",
                "For anyone who shoots raw but loves a good filter: one press can save a DNG, a " +
                    "PNG and a JPEG of the same moment. The DNG is your negative and no filter " +
                    "ever touches it. The roll keeps all three together as one photograph with a " +
                    "little tag in the corner. Tap it to flip between them, then send one file " +
                    "or the whole set. Settings, Frame, Files.",
            )
            NewsBlock(
                "The shutter never waits",
                "Press it and you have the photo. Roll develops it in the background, one at a " +
                    "time, while you are already framing the next shot. That small bar next to " +
                    "the counter is the queue draining. Hold the shutter down and it keeps " +
                    "shooting. The queue lives on disk now, so take as many as you want.",
            )
            NewsBlock(
                "Exposure by hand",
                "Four modes: auto, shutter priority, ISO priority and manual. The wheel takes " +
                    "over whichever half of the exposure you hand to it, with the stops sitting " +
                    "right beside the ladder. Settings, Camera, Exposure.",
            )
            NewsBlock(
                "Zone focus",
                "For the street photographers among us: set a distance and shoot, with no " +
                    "waiting for autofocus to catch up. Peaking marks the edges that are sharp, " +
                    "and the readout tells you what is in focus in feet or meters. One caveat. " +
                    "The coarse filters switch it off, because Dither 16, 1-Bit, Halftone and " +
                    "the two Game Boys throw away the detail you would be judging.",
            )
            NewsBlock(
                "Your photos, on a map",
                "The roll has a Map tab beside Camera and Starred, with every shot pinned where " +
                    "you took it, as a thumbnail of the photo itself. Turn the wheel to zoom, " +
                    "drag to move around, and tap a stack to lay out everything you shot in that " +
                    "spot along the bottom. Location tagging is on by default. Settings, Camera, " +
                    "if you would rather it was not.",
            )
            NewsBlock(
                "Ten adjustments",
                "A proper editing panel: exposure, contrast, highlights, shadows, vibrance, " +
                    "warmth, tint, sharpness, grain, vignette. Each one is a minus, a plus and a " +
                    "line saying what it does. If you remember one, make it vibrance. It lifts " +
                    "color and somehow leaves skin alone. Leave all ten at zero and Roll saves " +
                    "the file exactly as the camera made it. Settings, Look, Picture.",
            )
            NewsBlock(
                "Filters, on a real photograph",
                "Settings, Look, View Filters shows the whole catalog on a real photo, rendered " +
                    "live on your phone, one filter per page. Tap to take one off the wheel or " +
                    "put it back. And the big one: filters no longer take your other controls " +
                    "away.",
            )
            NewsBlock(
                "When something breaks, it says so",
                "A small mark in the corner counts camera faults and fades after ten seconds. " +
                    "Tap it to read what happened. Shake your phone twice and it sends a report " +
                    "with a screenshot. If a crash happened last time, it reports itself the " +
                    "moment you open the app. We would rather fix it than apologize for it.",
            )

            LightText(
                "Thanks for sticking with us through all those nightlies. Go shoot something.",
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
