## Roll v2.79 — the deaths that left no note now leave one

**Two shots killed the whole app, and nothing reported it — which is the bug this release fixes
first.** The crash log catches a Java throw; a native crash and a low-memory kill both end the
process with no handler run, no file written, no report offered. From the phone they are "the app
just closed", and from the code they were nothing at all — no lead to chase. Android keeps the
coroner's record, and Roll now reads it at every launch: a death by native crash, the low-memory
killer, an ANR or a signal becomes a fault on the chip and a "SEND ERROR?" offer carrying the
kernel's own description. **Update, open Roll once, and the crash that prompted this release names
itself** — the record of it is already on the phone, waiting to be read.

Each death is announced once; a Java crash the log already caught is not announced twice; swipes
from recents, force stops and updates are the system doing its job and stay out of the chip.

**And the likeliest killer gets less room to work.** A memory-trim warning is the low-memory
killer clearing its throat, and Roll now answers it by dropping everything droppable — the
Reach-back ring's frames and the map's decoded tiles, both of which rebuild in under a second of
ordinary use. Cheap insurance, paid only when the system says money is tight.

## Roll v2.78 — the RAW freeze, the PNG drag, and the sound of a photograph

Both from one field report, which named them better than the code had.

**"Camera freezes on RAW negative" — it did, and v2.77 caused it.** The RAW path's develop ran
inline on the darkroom's single thread while the shutter stayed latched, so a RAW press stood in
line behind every queued develop with the camera dead for the whole wait — and a session held
frozen like that is the likeliest trigger for the wedge the watchdog later reported as "Preview
went dark". The negative's JPEG is a saved file waiting for a filter, which is exactly what the
develop queue holds: it queues now, and the shutter frees the moment the capture lands, like every
other photograph.

**"Lossless PNG has some lag in viewfinder compared to just JPG" — worse than lag.** The lossless
setting alone was treated as a reason to develop, so every filterless shot was decoded, PNG-encoded
at 12MP (a pegged core per shot — the lag), and then had its JPEG **rewritten recompressed**:
quality lost on every untouched photograph the setting ever saw. The setting's own description
refuses a PNG of an untouched JPEG as "bytes spent preserving damage", and now the code agrees
with its comment: the lossless copy is made only when a filter, a crop or a stamp has produced
pixels that exist nowhere else. Filterless shots with PNG on are back to the untouched-JPEG fast
path — full speed, original bytes.

**The shutter got louder; the save got quieter.** The darkroom made saving something that happens
behind the photographer, several times in a row after a burst, and a save tone near the shutter's
level turned every burst into a small alarm clock. The press is the one sound that must read over
street noise — it is the only confirmation the photograph exists. Everything after it is
bookkeeping, and now sounds like it.

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
