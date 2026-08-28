## Roll v2.72 — the buffer moves to disk, where it always belonged

**Three quick shots lagged, then the screen went black. The crash report is the diagnosis:** "Heap
9 MB of 128 MB", "the app did not die" — no stack trace, because nothing threw. The darkroom was
holding each queued shot's 12-megapixel JPEG *in memory*, beside a worker whose decode needs 48MB
and whose filtered copy needs 48 more, inside a heap this phone caps at 128MB. Three shots queued
was the whole heap: the lag was the garbage collector fighting for scraps, and the black screen was
Android's low-memory killer taking the process — which is exactly the kind of death that leaves no
trace and no dialog.

**Now the disk is the buffer, the way a body's buffer is the card.** Every sensor shot is saved
*untouched, immediately* — it appears on the roll unfiltered within a beat of the press — and what
queues is a file reference and the settings, a few hundred bytes. The darkroom reads each file
back, develops it alone, and rewrites it in place, the same pattern the negative's JPEG has used
since v2.62. Shoot as fast as the sensor answers, for as long as you like: the queue is bounded by
free space, and the report puts that at 68.9 GB. A process death mid-queue now costs filters,
never photographs.

The develop also moved to its own thread at minimum priority — a 12MP decode on the shared pool
was visible jank in the viewfinder — and the sensor takes one capture at a time, because two
outstanding against a small ZSL ring is a fine way to wedge a HAL, silently.

**The gauge is a bar now, the way a body draws it.** A thin vertical bar fills as the current
develop finishes; the count beside it is what waits behind. The count falling and the bar
refilling is the queue draining — the old dot could only say "busy", and once the shutter could
outrun the darkroom, "busy" stopped being information.

**The fault chip.** A notice lives two seconds; a dropped frame mid-burst deserves a mark that
stays. `!N` sits in the status line until tapped — tap replays the last fault and clears it. A
crash from the previous run arrives there too, which is how a silent black screen introduces
itself on the next launch.

**The wheel is now the AF/MF switch.** FOCUS is always on the dial. Lock the pick onto it and the
app switches to zone focus; lock any other channel and the lens is the camera's again. The band
slot and the settings row still work, and agree.

## Roll v2.71 — press, and the camera is already yours again

**The press no longer waits for anything at all.** v2.70 moved the develop off the shutter; the
capture itself was still awaited, which billed the sensor's round trip to the finger even with the
ring buffer warm — zero shutter lag, waited for, which misses its point. A press now snapshots its
settings, *issues* the capture and returns: the frame lands whenever it lands and walks into the
darkroom on its own. CameraX queues overlapping captures by design; two may be outstanding at the
sensor at once, and presses past that queue up in order — a held shutter becomes a burst, which is
what a held shutter has always meant on a real body. The pipeline is bounded end to end: two at
the sensor, six in the darkroom, and the shutter honestly pauses only when the whole of that is
full. The `•N` gauge now counts both.

**Simple and the coarse filters queue too.** A panel shot used to encode its JPEG at the press —
cheap, but a burst pays every cost it is charged. The press now keeps only the panel readback,
tens of milliseconds; the rotate, the shader, the encodes and the save run behind the viewfinder
in press order. Flash and captures another app asked for keep the deliberate, awaited path: a
flash exposure is a conversation with the scene, and neither is a burst.

**Found while in there: the panel path never wrote the lossless copy.** The coarse filters —
Dither, Halftone, Game Boy, the entire reason the PNG setting exists — always shoot the panel, and
`fromPreview` was never taught the parameter `process` learned in v2.61. PNG-on produced a JPEG
and a "Lossless copy failed" notice that blamed the encoder. It writes the PNG now, off the same
bitmap the shader produced.

## Zone focus takes the wheel, the way a GR does

**Switch to MF and the distance is on the dial, immediately.** The GR bodies are the model: snap
focus exists for the street, and on the street the distance is set walking, from the hip, without
looking at anything. A wheel that needed click-turn-click to reach focus after you had already
said "manual focus" was asking you to say it twice — switching MF on is the statement, and the
dial now follows it. Turn for 0.3, 0.5, 1, 2, 5 metres, the hyperfocal and infinity; the readout
says what is sharp; peaking marks it on the frame.

Switching back to AF hands the wheel whatever it held before, so AF -> MF -> AF round-trips leave
the dial where you had it. Fastest loop: put AF/MF on a band slot (Settings -> KEYS), tap it, turn.

## Roll v2.70 — the shutter stops thinking first

**Pro fires like Simple now, and zero shutter lag finally does something.** Two facts had been
sitting next to each other in the same function: the ring buffer that makes a shutter instant was
bound only in Simple — the one mode that never captures from the sensor, because Simple shoots the
panel — and Pro deliberately asked the HAL for its high-quality still pipeline, which is where the
1.8 seconds lived. A ring nobody captures from, next to a capture nobody gave a ring.

Pro now binds zero shutter lag and asks for the fast variant of every processing stage, the same
trade the quickest cameras on this phone make. The deliberate wait is narrowed to the one gesture
that states it: **RAW on**. A negative is a request for everything the sensor saw, and waiting for
it is correct; a JPEG is a photograph of a moment, and the moment does not wait. All the old
guards stay — a ring that is not yet warm is not asked, and a single failed zero-lag capture
abandons the mode for the session rather than costing a second photograph.

Half-press still helps: focus and exposure locked in advance is work the shutter does not do at
the press.

**The darkroom.** Developing a photograph — the 12-megapixel decode, the shader, the encodes —
used to sit inside the same latch as the shutter, so the second press waited on the first
picture's *filter*. It now goes into a queue drained behind a live viewfinder, which is the buffer
model every real camera body uses: the shutter waits for the capture and for nothing else. Shoot
faster than the darkroom drains for long enough and the shutter pauses exactly as a body's does
when the card cannot keep up — six deep, briefly, and only then. A small `•N` in the status line
is the depth gauge while anything is developing.

One worker, deliberately serial: a develop is a ~48MB bitmap, and two at once is an out-of-memory
wearing a throughput argument. What a process death mid-queue loses is the undeveloped shots — the
same thing a camera's buffer loses when the battery comes out.

**Video hands the wheel to zoom.** No filter track, no shutter dial — re-framing mid-recording is
the entire reason a camcorder puts zoom under a finger. Leaving Video hands the wheel back to
whatever it held before. Everywhere else, zoom is on the wheel as a channel: click, turn to ZOOM,
click, turn.

## Roll v2.69 — the wheel finally works the way it was described

**Click to pick, turn to choose, click to lock, turn to adjust.** The channel wheel shipped as a
click that silently cycled a selection nothing read: bind the click to "Wheel channel" and the spin
still walked the filters, because turns were routed by the *turn* binding alone. The two gestures
disagreed about what the wheel was. Now, if either gesture is pointed at the channel system, the
whole wheel belongs to it — one binding is enough — and the wheel is properly modal: a click opens
the choice (the status line reads `›FILTER‹`), a turn steps through what the wheel can hold, a
click locks it in (`FILTER`), and turns adjust it. Bind "Wheel channel" to the click in
Settings -> KEYS and that is the entire interaction.

While picking, one channel per flick; locked in, every notch counts — a value is racked, a name is
read.

**The half press respects your tap.** Tap the subject at the edge of the frame, half-press to lock,
and the lens snapped back to the centre — the half press only ever knew about faces and the middle,
so it overrode the most explicit instruction the camera gets. A half press now locks on the tapped
spot for twenty seconds after the tap: long enough to be framing the same shot, short enough that a
stale tap cannot aim a lock at a scene that has moved on. A lens flip or mode change forgets the
tap, since it is in the old framing's coordinates.

**Settings: FRAME split into FRAME and CAMERA.** FRAME had grown to the size the un-tabbed screen
was when the tabs were introduced — the fault tabs exist to fix, reproduced inside one. FRAME is
now the picture: size, shape, grid, level. CAMERA is the act: focus, shutter, exposure, the flat
profile, files and location.

**Two controls brought up to the viewfinder band.** The two free band slots (Settings -> KEYS) can
now hold **AF/MF** — zone focus, one tap, because street photography is the whole reason it exists
and a control three taps deep is not a control you use on a street — and **RAW**, lit while the
negative is being written, since that is a per-scene decision too. On a camera with no RAW the
band slot says so once, in a notice, instead of lighting up and writing nothing.

## Roll v2.68 — seven found in review, none by a crash report

A deliberate audit of everything the last five nightlies added, hunting the class of bug that
passes every test and fails on the phone. Seven, in order of how much they mattered:

**Reach back handed the shutter a recycled frame.** Taking a frame from the ring and clearing the
ring were two calls, and the clear released everything it held — including the frame it had just
handed out. A crash on the very next draw, with Reach back on and burst off, which is the default
pairing. Taking now removes what it returns before releasing the rest, and there is a test whose
name is the bug.

**The map was dead on arrival.** Every location permission was declared in the manifest and
requested nowhere, and every code path checks and quietly does nothing when the check fails — right
at the shutter, wrong as a whole: a map that is always empty, a tagging switch that reads "On"
while tagging nothing, and no line anywhere saying why. The map now asks for what it needs on its
own screen, the same shape as the roll's photo-access gate, and the tagging toggle asks the moment
it is switched on.

**Trashing a multi-format capture left its other files behind.** Delete a RAW+JPEG photograph and
only the JPEG went: the negative then became the group's best remaining file and the "deleted"
photograph reappeared on the roll. The trash now takes every file of every selected capture into
the one system dialog, so the count it shows is the count of files.

**RAW captures were never tagged**, so they could never appear on the map. Same class as the
permission fault: silent, and found by reading rather than by shooting.

**The map held every tile it had ever shown.** A long pan across a city accumulated hundreds of
bitmaps with nothing to evict them — the out-of-memory would have arrived an afternoon later, in
whatever allocation happened to be next. Tiles now leave with the viewport; the disk cache makes
panning back instant anyway.

**The Reach back loop never slept.** It read the panel thirty times a second for as long as the
process lived, camera bound or not. It now idles while the camera is down, and empties the ring —
frames from before a pause are of some earlier scene, and "nearest the requested moment" across a
gap would have saved one as though it were now.

**Exposure, flat profile, lens correction and zone focus forgot themselves at every launch.** They
were engine state and nothing wrote them down. They are settings now, restored at start.

**And one promise kept:** the settings have always said the sharpest-of-eight applies to "Simple and
every coarse filter", and Reach back said the same — but Simple read the panel directly, so neither
did anything in the mode most people shoot in. Both work in Simple now.

**Known limit, stated rather than hidden:** the priority exposure modes balance against the meter's
last reading from before you took hold. Walk from sunlight into a room and the held half stays
right while the derived half stays where the daylight put it — switch through Auto to re-meter.

## Roll v2.67 — the shutter is quick again, and four fixes

**Saving got slow, and that was a regression I introduced.** Location tagging wrote the coordinate
by rewriting the whole JPEG — `saveAttributes` does not poke a tag into a header, it copies the
entire file to insert an EXIF segment — and it was awaited inline on every save with tagging on by
default. It now runs after the photograph is already on disk, where nothing is waiting for it. The
same reasoning was already written down in Simple for the date back; I did not apply it.

Simple was never tagged at all before, so nothing shot in it reached the map. It is now, on the
same off-the-press path.

**The viewfinder no longer freezes while a photograph is taken.** The held frame was grabbed
*before* the capture completed, so it was always slightly ahead of the frame the sensor returned —
and with Reach back on it is provably a different moment, since that feature exists to save a frame
from before the press. A still picture of the wrong instant, sat over the viewfinder for a second
and a half, reads as the photograph you got. The progress bar was already independent of it, so it
covers the wait on its own.

**The wheel click was claimed for the whole session.** With the dial lock setting on, the click
belonged to the lock permanently — so anything bound to it, including the new wheel channel, was
unreachable: press it, nothing happens, no way to find out why. It went unnoticed while the click's
only other job was the torch. The lock now claims the click only while the dial is actually asleep,
which is the one transition it ever needed; the locked state was never remembered between launches
anyway. Once woken, the click is its binding again.

**Zone focus shows where it is focused.** It shipped reporting only through the notice a wheel turn
raises, so with the wheel pointed at anything else the distance never appeared at all — a manual
focus with no distance on screen, which is the thing it exists to replace. It is now in the status
line with the zoom and exposure, along with the manual exposure pair and what the wheel is holding.

**The wheel skipped filters on the channel binding.** Arming — whether overflow notches are
swallowed — was read off the binding, and a channel binding does not say whether it is currently a
filter or a value. It now follows what the wheel actually does.

## Roll v2.66 — fixes the crash on launch in 2.64 and 2.65

**Roll crashed the moment it opened.** If you are on a nightly, this is the one to take.

`init` starts a collector on the pre-roll setting; `viewModelScope` runs on the immediate main
dispatcher and a `StateFlow` hands over its current value as soon as it is collected — so that
collector ran *during construction*, before any property declared below `init` had been
initialised. The ring buffer was declared next to the shutter code that uses it, four hundred lines
further down, and clearing a field that was still null took the app down on the splash screen.

Every unit test passed and CI was green, because the fault is in construction order rather than in
anything a test exercised. There is now a check that reads the source and fails the build if
anything `init` can reach is declared below it.

## Roll v2.65 — the roll, on a map

**A map is a scope, not a place.** It sits beside Camera and Starred at the top of the roll, the
same photographs are underneath it, and a tap opens the same viewer the grid does. Marks that hold
several photographs zoom in rather than guessing which one you meant.

**Photographs are tagged with where you were**, on by default, using the position the phone last
had rather than a fresh fix — a camera must not wait for GPS, and a press whose whole argument is
that it happens now cannot spend seconds on a radio. Where there is no recent position the
photograph simply has none.

Worth knowing, and said in the settings rather than buried: a coordinate lives inside the file and
travels with it, so a photograph you send through the picker carries where you were. Turning it off
stops new photographs carrying one; the ones already taken keep theirs.

**Reading them back needs a second permission.** Since Android 10 MediaStore removes GPS from
anything it hands an app unless the original is asked for, so `ACCESS_MEDIA_LOCATION` is what makes
the map anything other than empty — including for photographs this app stamped a second earlier.

**Tiles come from OpenStreetMap, and this is the only part of Roll that fetches anything.** The
app's network story has been one sentence — it opens a connection when you send a bug report you
wrote yourself — and a map cannot honour that, so the exception is contained: tiles are fetched only
while the map is open, cached on disk for good, and the credit is on the screen because those
servers are donated.

Clustering happens in pixels rather than in degrees, so a city breaks apart into streets as you zoom
rather than clustering differently depending on which latitude you were standing at.

## Roll v2.64 — focus peaking, and a shutter that reaches backwards

**Focus peaking.** Zone focus without it asks you to trust a number; with it the edges the lens is
actually resolving are marked on the viewfinder. It comes on with zone focus rather than having a
switch of its own — peaking while autofocus is running is a screen full of marks telling you what
the camera already did.

Peaks are **inverted rather than coloured**, which is the one decision here worth explaining. The
usual answer is a bright colour, and a bright colour has no contrast against a bright subject: a
white shirt peaks white on white. It also assumes colour, and this panel is grey unless Roll has
lifted it. Inverting the pixel guarantees contrast against whatever it lands on.

**Reach back.** Settings -> FRAME -> Shutter. The last few frames are kept as they arrive, so the
shutter can take the one from just before you pressed. You see the expression, then decide, then
your thumb moves, and by then it is a third of a second later and gone.

Off by default and it says why: it costs power the whole time it is on, and it changes *which*
moment you get, which is wrong for anything you are timing deliberately. The frames come off the
panel rather than a second stream off the camera — a second full-rate consumer of the ISP costs
power on every frame whether anything reads it or not, which is why QR mode is the only thing here
that binds one.

**With Reach back and Sharpest of eight both on**, the sharpest of the frames already held is taken.
That is the version of the burst that costs nothing at the press: no quarter of a second spent
collecting eight frames, because they were already there.

## Roll v2.63 — the wheel becomes a real dial

**Manual exposure, and the two modes between manual and automatic.** Auto, Shutter priority, ISO
priority, Manual. Camera2 has no half-manual auto-exposure -- `CONTROL_AE_MODE` is on or off -- so
the priority modes are built rather than requested: the metered pair is read back out of every
capture result, and the free half is re-derived against it each time the held half moves. Hold the
shutter open and the sensitivity follows it down.

ISO past the sensor's ceiling is applied as gain after the raw readout rather than asked for as a
sensitivity the hardware does not have, which is refused outright and reads on the phone as a
shutter that did nothing.

**The flat profile.** Noise reduction, edge enhancement and the tone curve, off. What comes back is
demosaiced and white-balanced and nothing else. White balance is deliberately left alone: a frame
with none is not flat, it is green. It also makes the eighteen filters better, because a shader was
otherwise grading a grade.

Lens correction is now its own switch, defaulted on, rather than something the flat profile turns
off behind your back -- on a wide phone lens that is a bowed horizon in every frame.

**Zone focus, with the distances worked out from this lens.** Hyperfocal falls out of focal length,
aperture and sensor size, all of which the camera will tell you; a constant is right for one phone
and an assertion everywhere else, and plainly wrong on the selfie camera. The readout says what is
sharp -- `3.4 m · sharp 1.7 m–∞` -- because zone focusing without that is guessing. The dial rests
at 0.3, 0.5, 1, 2, 5 metres, hyperfocal and infinity, and never offers a stop closer than the lens
can actually focus.

**The wheel holds one channel at a time.** Filter, EV, shutter, ISO, focus, zoom -- named on the
band, stepped by a click. Bind it in Settings -> KEYS; the torch keeps the click by default, because
a camera whose light you cannot reach is a worse camera. Channels appear only when they mean
something: there is no shutter dial in Auto, because the camera owns the shutter there.

**Nothing changed live rebinds the camera.** All of this goes through the running capture session,
not the use case, so a dial is a dial rather than a black viewfinder several times a second.

## Roll v2.62 — RAW

**The negative, from the same exposure as the print.** Turn on RAW in Settings -> FRAME -> Files
and one press writes a DNG and its JPEG together -- not two captures a moment apart, which is the
only version of this worth having. They share a name, so the roll shows them as one photograph.

No filter can reach a RAW file, and that is what it is for: a DNG is the sensor's readout before
the picture is made, so there are no pixels to put a shader on yet. Develop it later, and
differently, which is the whole argument for keeping one.

**With a filter on, the JPEG is developed after the fact.** The one CameraX wrote is read back, put
through the shader and written over in place. That costs a decode the ordinary Pro path does not --
the price of the negative and its print being the same moment.

**The switch says "Unavailable" where the camera means it.** RAW is an optional capability and the
selfie sensor commonly lacks it where the main one has it, so it is asked of the camera on every
bind rather than assumed -- a capability check that never happens always surfaces at the shutter,
which is the worst place for it.

**Simple is untouched.** RAW and lossless are Pro. Asking for a negative used to switch Simple's
ring buffer off as a side effect even though Simple was never going to write one; the format is now
requested only where it is used.

**A corner control in the viewer** names the formats a press produced and picks which one Send uses.
It does not change what is drawn: a JPEG and its lossless twin are the same photograph, and a DNG
has no picture to show until something develops it.

## Roll v2.61 — one press, more than one file

**A photograph can now be written as more than one file, and it stays one photograph on the roll.**
Every other camera on this phone makes you choose a format up front. That is the wrong question:
a lossless copy and a shareable copy are not alternatives to each other, they are alternatives to
having to decide. Turn on the ones you want in Settings -> Shutter -> Files.

**Lossless PNG.** Half of Roll's filters are flat colour beside hard edges -- Dither BW, Dither 16,
Dither 32, Halftone, Game Boy -- and that is the exact signal JPEG is worst at. A dithered photograph
saved as a JPEG comes back with a grey haze around every dot, and the dot pattern *is* the picture.
The PNG comes off the same bitmap the shader produced, before the JPEG encoder ever sees it, which
is the only moment at which lossless means anything. It costs a decode, a second encode and roughly
30MB a shot, and it says so in the settings.

Pro only. Simple writes the sensor's own JPEG untouched and never holds a bitmap, so a PNG there
would be a lossless copy of damage that has already happened -- and the untouched write is the whole
reason Simple's shutter is quick.

**The roll shows one item per press.** A press that wrote two files is one photograph in the grid,
not two near-identical ones side by side. The files are tied together by their name, because
MediaStore has nowhere to record that a relationship exists -- the same trick that already links a
booth strip to its four frames. Nothing is hidden and nothing is a database: the name cannot drift
from the file, because it is the file.

**Filenames now carry milliseconds.** `ROLL_20260827_143210_881.jpg`. Two presses inside one second
would otherwise write the same name and be read back as a single photograph with two of everything.
Older files have no millisecond field, parse as themselves, and stand alone -- which is what they
are.

**Under the hood: CameraX 1.5.3.** Up from 1.4.1, for the DNG capture arriving next.

**Nightlies.** A push to this repository now publishes a `nightly-` prerelease rather than a release
offered to everybody within the hour. Official builds are asked for. If you want the nightlies, turn
them on in BrightMarket; if you don't, nothing changes and you stay on the last official build.

## Roll v2.60 — the filter name now shows in Selfie mode

**The mode-picker band showed "SELFIE" even when the wheel had landed on a named filter — the one
piece of state you couldn't read off the picture.** Pro mode has always put the filter label in
that slot because the wheel cycles through filters and the active one is otherwise invisible. Selfie
has the same wheel and the same filters, but the condition that decided what to print only ever
checked for Photo. Now both modes show the filter name there, the same way Pro always did. Video and
Simple still keep their own labels: they have no dial.

Fixes [light-reports#118] — the mode band printed "SELFIE" over a selected filter.