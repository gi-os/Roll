## Roll v2.74 — a mode change starts clean

**Pick a filter in Pro, switch to Video, come back, and the filter was still on.** Reported twice
within half an hour, from both ends of the same complaint: once as a filter that "remains enabled
after changing camera modes rather than beginning with a blank slate", and once as a request to
"allow changing camera mode to reset filter to default".

The filter is a decision about one photograph. The mode is a decision about which camera you are
holding. Carrying the first across the second meant arriving somewhere new still wearing a look
chosen minutes ago for a different shot.

What made it worse than untidy is that the modes with no filter track — Video, Simple, QR, Text —
hide the dial without clearing what is on it. So the filter did not look like it was on. It was
simply gone from the screen, still applied, and the only way to turn it off was to go back to Pro
and walk the dial round to None by hand.

### Picking a mode resets the filter to None

That is the whole change. The mode strip, the band, and the two notches at the ends of the filter
dial that walk into Simple and back all go through the same place, so all of them clear it.

**Flipping the lens does not.** Photo and Selfie are one mode wearing two lenses — the code has
said so for a long time in `flipLens` — and turning the camera around mid-shoot to get the other
side of the same thing is not a new decision about the photograph. A double tap keeps your filter.

**The grade is untouched.** The ten adjustments are persisted deliberately: which adjustments you
shoot with is a property of your camera rather than of this frame, and throwing that away on a
gesture nobody thinks of as destructive would be a worse bug than the one being fixed here.

Fixes [light-reports#123] and [light-reports#128].

### The note field no longer hides under the keyboard

Typing a long note in the report sheet pushed the line you were writing behind the keyboard. The
sheet scrolls, and Compose already brings a focused field into view inside a scroll — but it was
bringing it into a region the keyboard was covering, because the sheet had no idea the bottom of
the screen had moved.

Fixes [light-reports#134].
