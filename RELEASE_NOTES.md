## Roll v2.86 — two ways to send that need no gesture

The shake gesture asks for four sharp direction changes inside a second. That tuning is right for
a camera that gets carried, and wrong as the only door: plenty of hands never fire it and stop
trying. So there are two more doors now.

**Tap the fault tally.** The `!N` chip already knows what went wrong. Tapping it now raises the
report offer as well as reading the names out, and the full tally rides the report — the names on
your chip arrive in the issue verbatim.

**Settings → ABOUT → Send feedback.** One row, no gesture, same report: a note, an optional
screenshot, sent only when you confirm.

## Roll v2.85 — the fast one

The first official release since v2.60, and it comes down to one sentence: the press is the photograph. Your finger lands, the shot exists. Filters, encoding and saving drain through a queue behind a live viewfinder, the way a camera body's buffer works. A thin bar in the status line shows the queue doing its job. Hold the shutter down and Roll bursts until you let go.

Twenty-five nightlies went into this build. Here is what they carry.

**RAW.** One press writes the DNG and its JPEG from a single exposure, and the roll shows them as one photograph. A corner control in the viewer picks which file you send. There is a lossless PNG option too, made for the dither filters. Those patterns are the picture, and JPEG smudges them.

**Manual exposure, on the wheel.** Auto, shutter priority, ISO priority, full manual. A flat profile turns the ISP's processing off for files you plan to grade. RAW keeps the sensor's best output either way.

**Zone focus.** Lock the wheel onto FOCUS and you are in manual focus, street style. You get detented distances, a live depth-of-field readout, and focus peaking that marks sharp edges by inverting them, so the marks stay visible on a gray panel. Put AF/MF on a band slot and the switch is one tap.

**The wheel became a dial.** Bind the click to Wheel channel and it works like a camera menu: click to open the pick, turn to choose between filter, EV, shutter, ISO, focus and zoom, click to lock, turn to adjust. Switch to video and the wheel takes zoom on its own.

**A map of the roll.** Photographs placed where you took them, clustered by street as you zoom in. Location stays off until you grant it. Tagging uses the phone's last known fix, so the shutter never waits on GPS.

**Batch delete.** Long-press a day heading to select the whole day, then tap trash. Cleaning up a burst takes three taps.

**Sizes, verified.** The sensor is 50 megapixels. Its default output is 12, with four pixels binned into one, which is also why 12MP shots handle low light better. Roll defaults to 12MP like the phone itself does, and the 50MP setting gives you the full unbinned readout. That mode is slow by design. You are asking for the whole sensor.

**A camera that reports on itself.** Faults land on a small tally you can tap to read. A shake offers a report with a screenshot. Deaths that leave no crash log used to look like a black screen and no answers. They now name themselves on the next launch, with the kernel's own description attached. Nothing is ever sent without your tap.

The shutter sound got louder and the save sound got quieter, because the press is the only confirmation that matters and the filing afterwards is bookkeeping.

This camera's own reports found most of the fixes in here. If something looks wrong, shake the phone. That report is how the next release gets better.

## Roll v2.85 — the watchdog stops shooting the surgeon

**"Shutter timeout and camera restarted on RAW mode" — the restart caused the timeout.** A
full-resolution RAW readout can stall the preview stream for longer than the watchdog's four-second
stale limit, and the watchdog read that silence as a dead camera and rebound it *mid-capture*: the
shot timed out because the camera was torn down underneath it, then the develop had nothing to
develop. The order in the report title is the order it happened. The watchdog now stands aside
whenever a capture is outstanding — a camera busy taking a photograph is the opposite of a dead
one — and resumes its rounds the moment the shot lands.

## Roll v2.84 — the reporting feature is the library's, all of it

**"Make sure it's using the latest shake-to-send code from bright common" — now it is, wholesale.**
Roll had adopted light-common's chip and kept running its own aging copies of everything behind it:
the shake detector, the screenshot, the trouble collector, the crash offer, the sheet. The shake
path aged until it silently stopped offering, and nothing on the phone could say whether the
gesture, the sensor or the wiring had died — the exact class of failure shared code exists to
prevent, because shared code ages in public.

It is one line now. `ReportOverlay` owns the whole feature, lifecycle-scoped: the accelerometer
runs only while Roll is in front, a crash is offered once per real launch (activity recreations no
longer re-raise it), the app's own noticed failures raise the chip through the same door, and the
send queue flushes itself. Eight local files are gone; what Roll keeps is what only Roll knows —
which faults to record.

Two behavioural notes. The chip and sheet are exactly the ones every Bright app shows, sizes
included. And the crash-log viewer in settings now reads the library's file — a trace from before
this update was in the old file and is not shown; it was also already offered on every launch
since it happened.

If the shake still refuses to offer on 2.84, that is a report in itself — from settings, where the
send-feedback row goes through the same pipeline.

## Roll v2.83 — the gallery stops "restarting" the camera, and the ring becomes a choice

**Opening the roll restarted the camera — every time, and it was the watchdog being wrong.** The
roll releases the camera (deliberately: no viewfinder, no sensor), but the preview's heartbeat
timestamp survived the release. Flick back and the watchdog compared "now" against a stamp from
before you left, found ten stale seconds, and "recovered" a healthy, freshly bound camera — one
false restart and one false fault per gallery visit. A new bind now starts with no heartbeat at
all, which the watchdog already knows is not evidence.

**The zero-shutter-lag ring is now a switch, and it ships off.** The ring is a second stream at
full still resolution running beside the preview, thirty times a second, whatever the shutter is
doing — and on this hardware both persistent complaints, the laggy viewfinder and the dying
preview, track sessions where that stream was up. The press stopped waiting for the sensor ten
releases ago, so a capture without the ring is late only by the sensor's own pipeline; Reach back
still covers the exact moment for panel shots. Hardware that takes the ring gracefully can have
it: Settings -> CAMERA -> "Sensor ring buffer", its cost written on the switch.

If the viewfinder is still not glassy after this one, the next report to send is exactly that —
the autopsy pipeline is listening.

## Roll v2.82 — the wedge loses its common thread

**Every dark-preview report on file shares one fact, and it is not the flat profile.** #158 came
off v2.77 with flat off; #163 off v2.80 with flat on. What both sessions were doing was holding
the zero-shutter-lag ring for a long stretch — and rebinding a dead preview back into the same
configuration asks the HAL to reproduce whatever it just choked on. So the first dark preview now
costs ZSL its seat for the session: the recovery rebind comes up without the ring. The shutter
stays instant — the press stopped waiting for the sensor ten releases ago — and what is bought is
a camera that cannot die the same death twice a minute.

**The zero-lag retry was firing into the bind that had just failed.** The abandon posts its
rebind to the main queue, and the retry — on the same queue — usually ran first: it saw the
camera still "ready" (true, of the doomed bind), spent its one warranted attempt reproducing the
failure, and the shot fell to the panel rescue with a "Sensor didn't answer". The retry now waits
for the bind itself to change, which is what it always meant to wait for.

**Reach-back's readback stops taxing the viewfinder.** A panel grab is main-thread work — the one
thread the viewfinder cannot share — and the ring was taking thirty a second while develops fought
for the cores. The cadence halves (the ring still holds twice the reach the setting offers), and
while the darkroom is working the ring thins to a check: a slightly sparser ring under load still
holds the moment; a janky viewfinder holds nothing.

**If it still crashes: the phone has the autopsy — send it.** Since v2.79 every silent death is
read back at the next launch and offered on the chip and as "SEND ERROR?". Tapping that is the
difference between fixing the disease and treating symptoms; the last two real fixes both came
from reports.

## Roll v2.81 — our style is the default again

**v2.80 made the flat profile the default, and that was wrong twice.** First in taste: Roll's
photographs have a look of their own, and shipping every unfiltered shot flat traded it for
another camera's opinion — "keep it our style" is the whole review, and it is accepted. Second in
fact: the very first field report off 2.80 was the watchdog catching a dead preview, and the
prime suspect is the default itself — a linear tone curve with processing disabled is one thing
on a still and another on the *repeating* request, thirty times a second, where this HAL
evidently tolerates it poorly.

So flat is opt-in again, exactly as capable as v2.80 made it: choose it and the still carries
Zero's keys baked in — noise reduction, edge, hot pixels and aberration off, linear tonemap —
with the JPEG written at 95, riding the zero-lag path. Unset phones revert on update; a flat
profile you switched on yourself stays switched on.

The default Roll: the ISP's own processed JPEG, untouched on the filterless path, at the speed
the last ten releases built. Our style.

## Roll v2.80 — Zero-grade files, by default, at zero-lag speed

**The insight this release is built on: Zero's quality and Zero's speed are the same decision.**
Its files look the way they do because the ISP is told to do *nothing* — noise reduction off, edge
enhancement off, hot pixels left alone, a straight line for a tone curve — and nothing is cheaper
than the FAST processing everyone else asks for. Quality was never the thing traded for speed;
both were traded away together, for punch.

So the flat profile is now **on by default**, and a flat still states Zero's exact keys on the
capture itself rather than trusting session options to win a HAL negotiation: NR OFF, EDGE OFF,
HOT_PIXEL OFF, aberration OFF, linear tonemap — and the JPEG writes at quality 95, which is what
Zero writes. White balance stays on, because a frame with none is not flat, it is green. Lens
correction remains its own switch, defaulted on.

And because OFF is cheap, all of it rides zero-shutter-lag: the instant shutter now produces the
unprocessed file. That combination is the thing neither app had — Zero's negative-grade JPEG,
Roll's press-is-the-photograph timing, one camera.

Prefer the ISP's processed look? Settings -> CAMERA -> Flat profile, one tap, as before. Flat also
remains the better base for every filter — a shader grading a linear frame instead of grading a
grade.

Toggling flat or lens correction now re-binds the camera (one viewfinder blink) so the next
photograph is guaranteed to carry the new setting rather than the old bind's opinion.

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
