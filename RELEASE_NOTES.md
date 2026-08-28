## Roll v2.77 — !54, explained and mostly abolished

A field test came back with the delay fixed and the fault chip at fifty-four — and a chip that
could only replay the *last* fault answered "how many" while refusing the only question 54 raises.

**The chip now reads its contents out.** Faults tally by message; a tap shows the top three with
counts — "Buffer full ×41 · Lossless copy failed ×12" — then clears. A number with names is a bug
report; a bare number is an accusation.

**Most of the 54 should not happen again, twice over:**

**The lossless copy stops fighting the heap.** A 12-megapixel PNG is 20-35MB, and it was being
encoded into a heap buffer beside the ~48MB bitmap it came from — an allocation a 128MB heap
refuses often enough that "Lossless copy failed" fired once per photograph of a burst. The encode
now streams straight into its file while the bitmap is whole; the only large thing alive is the
bitmap the encoder is reading anyway. Applies to the develop queue and the RAW path both.

**The buffer ladder drains as fast as it fills.** Sustained hammering outran the old drain and hit
the hard cap at twelve — dozens of dropped shots, each honest, each a failure. Halves are a
quarter of the bytes *and* a quarter of the encode, quarters a sixteenth, so the queue now speeds
up as it deepens: full resolution to two deep, half to twelve, quarter to thirty-two. The cliff
still exists — it says so, once per drop — but a finger can no longer realistically reach it.

## Roll v2.76 — the chip is the library's, and the library is current

**The report chip shrank back to its intended size.** Roll was drawing its own copy of the chip
with the Button text style at grid scale — on this panel, a banner wearing a chip's name. The chip
now comes from light-common, like the rest of the shake-to-report machinery: the same 11sp popup
every Bright app shows, fading on the same clock, placing itself in its own window instead of
asking the layout for room.

**light-common 1.2.3 → 1.8.0**, which also brings the report sheet fix where the note field kept
hiding under the keyboard. The version was checked against the package registry's own metadata
rather than the repo's tags, because a tag there has resolved to nothing before.

**Real errors now offer to report themselves.** The faults the `!N` counter collects — a dropped
frame, a failed develop, a camera restart — also feed Roll's Trouble line, which raises the
standard "SEND ERROR?" chip with a screenshot attached. Trouble already knew how to do this
politely: the same failure asks once an hour at most, the first failure of a cascade is the one
that gets reported, and nothing is ever sent without the tap. The counter stays as the quiet,
persistent half; the chip is the loud, consented one.

## Roll v2.75 — the photograph is of the press, always

**A queued press used to expose late.** Presses faster than the sensor waited their turn, and each
one exposed *when its turn came* — a photograph of the wrong moment, seconds late at the back of a
burst. That is the one thing a camera must never do, and it is gone: the sensor takes the shot when
it is free, and when it is busy the live viewfinder frame is seized within milliseconds of the
finger and queued for developing. The tail of a burst trades resolution for the moment, which is
the trade every body's buffer makes — the moment is the photograph; the pixels are only how many.

**Nothing waits at the press any more, anywhere.** The panel queue's old cap paused the third
quick shot until a slot freed — the wait crept back to the finger through the back door. Now the
frame degrades instead: the first three queue at full panel resolution, the next nine at half, and
past a dozen the shot is refused out loud ("Buffer full"), with a fault mark. A refusal with a
named reason is a camera being honest about its limits; a wait is a camera lying about whose time
it spends. Reach back and sharpest-of-eight still apply to every seized frame.

**The permanent `!1` is cleared.** The crash file is kept on disk until a report is sent — the
report dialog needs it — but the fault chip read "file exists" as "new crash" and re-raised it at
every launch: an alarm that cries every morning about the same old fire teaches you to ignore
alarms. A crash is announced once; tapping the chip acknowledges it; the file still feeds the
report. A genuinely new crash announces itself again.

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
