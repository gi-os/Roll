## Roll v3.0

These are the changes since v2.94, the last official build. Most of them are in the meter. The
meter is the ladder of values with the red needle at the edge of the viewfinder.

### The meter

The meter now works for every dial. Filters, exposure, shutter, ISO, zone focus and zoom each show
their own numbers. Before this release, only the filter dial moved the needle. Two faults caused
that. The meter hid itself after three seconds, and only the filter dial woke it again. The meter
also did not watch the values it showed.

The meter centers on the viewfinder instead of the screen. The control band no longer pushes it to
one side.

The meter leaves the screen when you go to the roll. It used to draw over the photographs there.

The numbers are larger. Exposure, zoom, focus, shutter and ISO get a deeper strip and bigger
figures. The filter ladder keeps its small text, because it holds more than twenty codes. Every
number now fits, and the needle points at the number it names.

### Zoom

The zoom ladder shows 1.0 at the bottom and 8.0 at the top. One notch of the wheel moves the lens
by a fraction of a stop. The needle moves by the same fraction of the gap. You can stop at 2.4x.
The marks follow a log scale, so 1.5 to 2 and 4 to 6 cover the same distance.

Pinch to zoom shows the meter during the gesture. The wheel keeps the dial it had.

### Picture adjustments

Settings, then Look, then Picture holds ten adjustments. They are exposure, contrast, highlights,
shadows, vibrance, warmth, tint, sharpness, grain and vignette. Each row has a minus button, a plus
button and one line that says what it does. Before this release, only the list icon on the band
opened them. The channel button had taken that icon, so there was no other way in. With all ten at
zero, Roll writes the file without changes.

### Filters and focus

Filters no longer take the other dials away. An earlier build removed exposure, focus and zoom from
the wheel under a heavy filter. The filter is a look over a real exposure. The DNG does not carry
it, and you still have to aim, expose and frame the shot.

Zone focus stops for a coarse filter. Dither 16, 1-Bit, Halftone, Game Boy and Game Boy Color
remove the detail that zone focus needs. Roll turns zone focus off under those filters. It comes
back with the next fine filter. The other dials stay.

### Faults

The `!n` mark shows for ten seconds after a fault. Then it fades. The list behind it stays, and a
shake report still carries every name.

## Roll v2.99 — the picture settings have a home, and the ladder gets its numbers back

**Ten adjustments, in Settings, where you can find them.** Exposure, contrast, highlights,
shadows, vibrance, warmth, tint, sharpness, grain and vignette now live in Settings under LOOK,
each with a minus and a plus and a line saying what it does. They were only ever reachable from
the list icon on the viewfinder band, and the wheel's channel button took that slot over, so
they had quietly become unreachable. Every value at zero still means the file is untouched.

**Bigger numbers on the value ladders.** EV, zoom, focus, shutter and ISO get a deeper strip and
numbers set as large as the rungs allow. Filters keep the small setting, since twenty-odd
three-letter codes at that size would run into each other.

**Zone focus steps aside for the coarse filters.** Focusing by zone is a judgement made by eye off
the preview, and Dither 16, 1-Bit, Halftone and the Game Boys have thrown the detail away before
it reaches the glass. Under those looks the focus channel leaves the dial and zone focus switches
itself off, in both directions: turning a coarse filter on drops it, and asking for it under one
is declined. Every other channel stays exactly where it was.

## Roll v2.98 — zoom between the marks

**The wheel zooms continuously again, and the needle shows where you are.** The printed numbers
are landmarks on a lens that has every framing in between: 2.4x is a real setting, and a needle
that could only stand on 2 or 3 was lying about it. Each notch moves the lens a fraction of a
stop and the needle slides that same fraction of the gap, so racking the wheel reads as one sweep
past the numbers instead of a pointer jumping between them.

Spacing is logarithmic, the way the marks are laid out and the way zoom is felt: 1.5 to 2 and 4 to
6 are the same distance to a hand and the same distance on the ladder. Pinch and the ladder drag
are unchanged, and both show up on the needle the same way.

## Roll v2.97 — the needle really does move now, and the fault chip stops staring

**The needle moves on every channel.** The real fault was one line up from where I kept looking:
the gauge was reading the wheel, EV, zoom and focus from values collected at the top of the screen
but never read inside the meter's own block, and Compose only redraws the part of a screen that
actually reads a value. Filters worked by accident, because the filter is read elsewhere in the
outer body and drags the whole screen along with it. The meter reads its own inputs now, so EV,
zoom, focus, shutter and ISO all sweep the way filters always did.

**The !n mark fades after ten seconds.** A mark that stayed until it was read made sense while
faults were being hunted and reads as a bandage now that they're rare. It shows for ten seconds
after the most recent fault, then fades out of the picture. Nothing is lost: the tally stands and
a shake report still carries every name.

## Roll v2.96 — zoom reads like a lens barrel, and a pinch brings its meter up

**The zoom ladder runs the way the barrel is marked.** 1.0 at the bottom, 8.0 at the top. It was
upside down.

**One notch, one mark.** The wheel used to zoom by an eight percent multiplier, which is right for
a pinch and wrong for a dial with a ladder next to it: eight notches moved the needle less than the
gap between two printed numbers, so the wheel read as dead. On the zoom channel the wheel now walks
the marks, one per notch. Pinch stays continuous for framing.

**Pinch raises the zoom meter.** Two fingers on the glass bring the zoom ladder up for the length
of the gesture, whatever the wheel is holding, then it retreats and the wheel keeps the channel it
had. The gesture borrows the meter instead of reassigning your dial.

## Roll v2.95 — the meter shows up for every dial, sits on the picture, stays out of the roll

**The needle works on every channel now, not just filters.** The ladder fades itself out after a
few seconds, and only the filter dial was waking it back up — so on EV, shutter, ISO, focus and
zoom the needle was moving behind an invisible layer. Every turn of the wheel brings the meter
back now, whatever it's holding.

**Centred on the viewfinder, not on the screen.** The ladder sat half a control band off centre
because it was measuring the whole glass instead of the picture. It now centres on the image, with
the band's width taken out of the sum.

**Gone from the roll.** The ladder is longer than the strip it draws into, and only the rotation
folded it back inside — unclipped, it painted past the page and turned up over your photographs.
It's clipped now, and it leaves entirely when the viewfinder isn't the page you're on.

**Filters stop taking controls away.** The last build dropped EV, zone focus and zoom from the
wheel under the heavier looks. That was wrong: a filter is a look laid over a real exposure, the
DNG never wears it at all, and a Game Boy frame still has to be aimed, focused and exposed. Every
channel stays on the dial under every filter.

**EV's numbers are readable.** They were held to half the size the ladder had room for by an old
fixed ceiling. The only limit now is the ladder's own width.

## Roll v2.94 — choose your filters, all of them, on the beach

**Filter picker in Look settings.** Look → View Filters now opens the full filter list — every look,
not just the ones on the wheel — so you can see them all and switch any of them on or off any time
you want. Each page swipes to the next and renders on a photo the camera knows: a copy of that beach
shot, put through the real filter, live, on the phone. Tap a page to turn that filter off (or back
on). Your choices are saved the moment you make them, and the wheel and Photo Booth grid follow.

**Shows itself when there's nothing saved.** A fresh install — or a filter reset — has no filter
choice on the device, so Roll can't know what you want yet. In that case the picker offers itself
on first launch, then gets out of the way once you've decided. You can always get back to it from
Look settings.

**Void is gone.** It never looked like anything, so it's removed for good — one less name on the
wheel and in the picker.

## Roll v2.93 — the ladder behaves, the heavy filters keep the wheel

**The ladder comes and goes on its own terms.** Three seconds after you stop touching the dial the
gauge retreats — a slow fade that reads as the meter looking away — and the moment the wheel turns,
or a finger lands on the ladder, it's back in under a beat. The viewfinder stays clean between
adjustments; the meter only claims the edge while you're actually using it.

**The filter ladder fits between the bars.** It used to run the full width of the frame and spill
over the black chrome at both ends. Now it sits fifteen percent in from each edge — on the
viewfinder, where it belongs, with every rung still a fingertip away.

**EV stops shouting in tiny print.** The gauge sized its numbers to the whole ladder, blank rungs
included, so the third-stop grid shrank "-2" until it was dust. Now each label is sized to the
labels around it — the whole-stop numbers you actually read are three times the size they were.

**The needle keeps up with a fast spin.** It used to take 120ms per notch, so a quick turn left the
red bar limping after the wheel. Half that, and one notch reads as one motion again.

**The heavy filters keep the wheel to themselves.** Film and Mono are looks over a scene the camera
still reads, so EV, zone focus and zoom stay on the dial under them. Everything else — the dithers,
Game Boy, datamosh, the distorting ones — replaces the scene, and EV/zone/zoom under a look you
can't see through would be adjusting a photograph nobody can see. Those channels leave the wheel
until a plain look is back.

## Roll v2.92 — real glyphs for zoom and filters

**Zoom and filters stop borrowing.** The channel button was showing a plus sign for zoom and a
generic list for filters -- placeholders from the SDK set, which simply has neither. Both are now
drawn in the SDK's own hairline style: zoom is the magnifier with a plus, filters are three lenses
overlapped. Same 1px strokes as everything else on the frame, so nothing looks pasted in.

## Roll v2.91 — the filter ladder takes the whole edge, and the catches come off

**Filters get the full frame line.** On the filter channel the ladder now runs the entire left
edge of the viewfinder -- every filter on the dial, laid out at once, a fingertip away. The other
channels keep the small fixed meter; only filters stretch.

**And the dwell is gone.** The dial used to stop dead on None and Purikura to keep a fast spin
from skating past them. With the whole track visible on the edge, a catch is a hesitation you can
see no reason for -- so it's out, every notch lands, and None is findable by eye instead.

**EV thinks in thirds now.** Each notch of the wheel moves a third of a stop -- the step
photographers actually use -- instead of the sensor's tenth-stop crawl. The ladder marks only the
whole stops, -2 to +2, and the needle lands between them.

**The needle got the last of its manners.** The whole gauge sits flush with the frame line, the
bar is a touch thicker at 80% opacity so the numbers stay legible through it, and it sweeps to a
new value instead of teleporting -- a meter's arm has mass.

## Roll v2.90 — the meter stands up, the channels turn to icons, feet arrive

**The meter matches the sketch now.** No box around it, ladder text left-aligned, and the whole
gauge turned sideways with the rest of the chrome — hold the phone in the shooting grip and the
ladder stands upright on the frame's edge, the pivot hidden off-screen, only the red tip of the
needle sliding in under the numbers.

**The channel button is an icon, and the readouts got out of the way.** The meter names the values,
so the button only says which dial — crosshair for focus, the exposure glyph for EV, the list for
filters; shutter, ISO and zoom borrow glyphs until they earn their own. Solid means locked,
lightened means free. The top-line focus and exposure texts are gone: the dial is the readout.

**Channel unlock works the way a hand expects.** A click on a locked channel frees it — the old
build refused the click and pointed at a tap target instead, and a lock the most natural gesture
cannot open reads as broken, not strict.

**Feet.** Settings → CAMERA → Distances. The zone-focus ladder and its sharpness readout speak
feet or meters as you prefer; the optics stay metric underneath.

## Roll v2.89 — the meter learns to latch, the dial learns when to catch

**∞ no longer reads NaN.** Focused at infinity, the depth-of-field formula divided infinity by
infinity and the readout printed the result, verbatim. The limit is well defined — everything from
the hyperfocal distance out is sharp — and now it says so.

**Two locks, both a tap.** Tap the meter and the dial latches: wheel turns change nothing until you
tap it again, so the shutter speed you set stays set in a pocket. Tap the channel label in the band
and the *channel* locks — the wheel keeps adjusting the thing you chose and the click stops opening
the pick, shown as [SHUTTER] in the band. Values and channels lock separately because they are
different promises.

**Filters ride the meter as codes.** DBW, D16, GB, FLM — three letters at most, because the ladder
is a centimeter wide and the viewfinder is already showing you what the filter looks like. Drag the
needle to jump the track.

**The catch on None and Purikura engages only when you spin.** The dial stopping dead on a landmark
is for finding it at speed — past four filters a second, as designed. At a browsing pace the wheel
now steps cleanly past, because a deliberate turn interrupted by a catch reads as a stuck wheel,
and Purikura's catch — which had quietly stopped happening at all — is back where the spin rule
wants it.

## Roll v2.88 — the meter

**A needle, for whatever the wheel holds.** Put the wheel on shutter, ISO, focus, zoom or EV and a
small ladder of that dial's values appears on the right edge of the viewfinder, with a red needle
sweeping on a fixed pivot — read like a speedometer, dragged like a slider. Your finger sets the
value by height; the wheel still steps it; the needle answers both. Filters get no needle, because
a needle pointing at a name is just a list in costume.

**The band and the menu traded jobs.** The wheel's channel now sits in the band where Adjust used
to be — tap it to open the pick, same act as clicking the wheel, built for thumbs. Adjust moved
into the mode menu next to Filters. And the mode menu gains a photo type entry in Pro and Selfie:
tap to cycle JPG, JPG+PNG, JPG+RAW, all three — which files a press writes is a per-scene decision,
and it now lives one tap from the shutter instead of three switches deep in settings.

**The queue bar drains downward** now instead of filling up, the way a buffer actually empties.

## Roll v2.87 — holding the shutter now means it

Two shots from a held button was the hardware being taken at its word: the camera key sends one
DOWN and one UP with nothing in between, so "hold to burst" was a promise the release notes made
and the code never kept. The clock lives in the app now. The first shot fires at the press, as
always. Keep the button down half a second and shots follow at about three a second until you let
go — a pace the pipeline absorbs without filling the fault chip. Video, the QR and text readers,
and a running self-timer never start the clock.

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
