## Nightly: video looks, recorded into the clip

**A nightly, on purpose.** This build changes how Video binds the camera. Nobody has run it on a Light Phone III yet. It becomes official after `docs/RELEASE_CHECKLIST.md` passes on a phone.

**What changed.** In Video, the wheel now picks a look. The look goes into the file. The viewfinder shows the same frames the encoder gets.

Before this build, Video forced every filter off. A photo filter is a `RenderEffect` on the preview view. The recorder reads the camera and never sees that view. A filtered viewfinder over a plain file shows a picture that the clip does not contain.

**How it works.** Video now binds the preview and the recorder as one `UseCaseGroup` with a CameraX `CameraEffect`. The camera draws into a surface that Roll owns. Roll runs the look on the GPU, then draws the result into the preview and into the encoder. Each frame keeps the camera timestamp. This keeps sound and picture in sync.

**The looks.** There are 28 on the Video dial.

- 19 photo filters carry over. Film, Mono, Thermal, X-Ray, Glow, Comic, the two Game Boys, the dithers, Halftone and the distortions all use the photo shader source. `GlslPort` converts each AGSL shader to GLSL ES 3.00 at runtime.
- Preset uses your photo grade. A clip and a photo shot a minute apart get the same color.
- **Super 8** holds each frame for 1/18 s and adds gate weave, flicker, grain, dust, a light leak and rounded gate corners.
- **VHS** blurs color along the line, adds tracking wobble and head-switching noise, and rolls a tracking band down the frame.
- **Trails** blends each frame with the last one and keeps bright points for about a quarter second.
- **Stop Motion** changes the picture 8 times a second, with a small shift and exposure change on each new frame.
- **CCTV** runs at 12 frames a second with a wide-lens bend and a green mono tube. It burns the real date and time into the corner.
- **Motion** shows only what moves, on black, with a faint outline of the scene.
- **Slit-scan** takes each row from a different moment. The top row is now. The bottom row is one second old.
- **Datamosh** runs live. A block search finds where each 16-pixel block came from in the last frame. The look then moves its own last output along those vectors, as a decoder does after a deleted I-frame. Move the phone and the picture smears. Hold still and the picture clears.

Looks with a horizontal line, such as VHS, CCTV and Slit-scan, follow the way you hold the phone. The turn locks when recording starts. Android writes the file rotation at that moment.

**The wheel works while you record.** A look change does not rebind the camera. The next frame uses the new look, so you can cut from Film to VHS inside one clip. The band shows the name of the look.

Purikura stays a photo mode. It needs face positions and a printed frame, and a clip has neither. A photo filter that you take off the photo dial also leaves the Video dial.

**If the looks fail.** A shader that does not compile, or an EGL surface that the driver refuses, turns the looks off for the session. Roll shows a notice. A recording in progress continues without a look. The next bind in Video uses the old two-stream setup. **Looks in video** in Settings (Look tab) turns the processor off.

**One side effect to check on the phone.** With one stream for both outputs, the HAL gets one GPU-texture stream instead of an encoder stream. The encoder stream set is what selected the EIS usecase with the Morpho node that stalls recordings. The new setup can change that selection. Only a device log can show that. Run `adb logcat | grep VideoMorphoEISV3Offline` across a record and a stop.

**Tests.** `VideoShadersTest` sends 62 shaders to the Khronos `glslangValidator`. That covers every video look and every ported photo filter. CI installs `glslang-tools` and fails if the tool is missing. Before this build, no Roll shader got a compile check before it reached a phone. `FxGeometryTest` checks the upright turn corner by corner. It also proves that a wrong sensor rotation cannot turn the clip. `VideoLooksTest` checks the dial. An llvmpipe render found one bug before this push. Datamosh stored a zero motion vector as 128/255, so a still scene blurred a little on every frame.

## Nightly — a crash no longer costs a frame, and the roll goes to a computer on your terms

**A nightly, on purpose.** Nothing here touches the camera, but `main` still carries the FHD
stabilization probe from 3.13.173, which has not passed `docs/RELEASE_CHECKLIST.md` on a phone.
An official release on top of it would promote that probe. This becomes 3.14 official the day
the checklist passes.

**A film-roll frame that survived a crash was being thrown away; a develop could leave half a
photograph; and "Simple" and "Start web server" said what the code did rather than what you
got.**

The film roll writes each frame before its index line, on purpose — the other order would leave
an index pointing at nothing. The recovery that ran at the next launch then treated any frame
the index did not know about as rubbish and deleted it, which turned a process death at
exactly the wrong millisecond into a lost photograph, silently, with the counter one short.
Recovery now adopts: a frame the index forgot is put back on the roll in the order it was
written, marked as recovered, and only a zero-byte file or one whose first bytes are not an
image is removed. `RollIndex` makes the decision, has no Android in it, and is tested for the
four cases that matter.

The develop pass replaced a photograph by opening its MediaStore row truncating and writing
into it, so for the length of the write the file on disk was a partial JPEG. A full disk or a
failure in that window left it that way. The new bytes are now staged whole in the cache
first, a whole copy of the original is kept beside them, and only then is the row written in
one pass; if that pass fails the original is streamed back. A photograph ends a develop either
filtered or exactly as it was.

The panel-frame queue used to shrink a burst under pressure — half size past two frames, a
quarter past twelve — with nothing on screen saying so. That is a fair trade and not one to
make for you. **Bursts may shrink** is a switch in Settings, off by default: off, every queued
frame keeps its full size and a press past the cap is refused with a notice; on, the ladder is
what it was. And the mode itself is renamed. "Simple" described the interface and hid the
price; it reads **Instant** on the picker and **Instant — smaller photos** in Settings, because
the frame it saves is the panel's, 1080 pixels across, not the sensor's.

**Start web server** is now **Send to computer**, and it asks first. The screen offers the
photographs you had selected — hold one on the roll, then tap **Computer** on the selection bar
— or the entire roll, as two buttons, neither chosen for you; before, everything the roll
showed was on offer the moment the socket opened. The running screen says what it is sending,
the button reads **Stop sending**, and widening to the whole roll later keeps the same address
and PIN. The scope is enforced where ids resolve, so a photograph outside it is a 404 from the
listing, the thumbnail and the file alike.

Also new: `docs/RELEASE_CHECKLIST.md`. A camera change ships as a nightly and stays one until
that list passes on a Light Phone III.

## Nightly — FHD video, to move the stream off the EIS usecase

**Unverified on hardware. This is a probe, not a fix.**

Nightly 3.13.172 asked the camera HAL for stabilization off, on the preview and on the video use
case. The device log answers that question. The HAL built the EIS graph anyway.

CamX picks the usecase when it configures the streams. A per-request key arrives after that
decision. So the standard key is not the lever, and this app never had the lever it looked like.

The same log shows the failure in more detail than before. The Morpho node errors on its first
frame, `Fcode:0x1 frame_id:0`. It keeps taking buffers after that and signals none back.
Thirty-seven requests later the video port pool is empty and the HAL enters recovery. Thirty-seven
is the depth of that pool, not a timeout. Two separate runs stalled on the same number.

This release changes the one input to usecase selection the app controls. That input is the shape
of the stream set. Video recording moves from 720p to 1080p. If CamX has a usecase for that
configuration without the Morpho node, the black viewfinder goes away.

Video files roughly double in size. On its own terms that is a bad trade, on a 3.92 inch screen
that cannot resolve the difference. Against a camera that bricks itself mid-recording it is a fine
one.

If this nightly stalls the same way, the lever is not in the app. The work then moves to surviving
the recovery rather than preventing it.

## Nightly — the HAL is asked for stabilisation off, out loud

**Unverified on hardware. This is a nightly because the question it answers can only be answered
on a phone.**

A device log finally shows what happens between a recorder letting go and a session coming back,
which is what v3.13 said the next attempt had to start from. The camera HAL builds a
`VideoEIS3PreviewEIS2RealTime` usecase and a `VideoMorphoEISV3Offline` stabilisation session for
every video this app has ever recorded — and it does so without being asked. CameraX defaults
stabilisation off and this app has never turned it on; the switch is `persist.vendor.camera.enableEIS`,
a vendor property set on the device that no app can write.

Inside that stabilisation session, a Morpho node stops signalling completion the instant the
recorder detaches — thirty-seven frames left outstanding, the HAL into its own recovery, and a
flush that cannot finish because it is waiting on the very frames that are stuck. That is the black
viewfinder. It is also why v3.12 rebooted a phone: an unbind queued behind a flush that never ends
is not a rebind, it is the camera service going down.

Not asking is not the same as asking. This puts `CONTROL_VIDEO_STABILIZATION_MODE` explicitly to
off in the session parameters, on both the preview and the video use case, where the HAL's usecase
selection reads it. Whether it is read early enough to keep the stabilisation graph from being
built at all is exactly what this build is for.

The watchdog is untouched. v3.13's revert stands.

## Roll v3.13 — the camera recovery from v3.12 is withdrawn

**v3.12 rebooted a phone, and this takes it straight back out.**

v3.12 taught the watchdog to recover from three states it had been blind to, including a camera
that never produces a first frame after a recording — with a deadline of 1.2 seconds. The
diagnosis stands. The remedy was wrong in a way that matters far more than the bug it was for: a
rebind is an `unbindAll` and a `bindToLifecycle` in the same tick, and this file has said for
several releases that two of those back to back on this hardware is the thing everything else in
it is written to avoid. A short deadline plus a rebind that fails plus a retry is exactly that
pattern on a loop, and on a HAL still tearing down a recording session it does not recover the
camera — it takes cameraserver with it, and on this phone that is a reboot.

So v3.12's recovery changes are reverted entire: the first-frame deadlines, the retry of a failed
bind, the `cameraState` observer, the re-armed heartbeat at finalize, and the back-off that kept
trying once a minute. The watchdog is exactly what it was in v3.11.

**That leaves the original fault unfixed, and it should be named rather than quietly dropped.** A
recording can still hand back a black viewfinder that nothing restarts. It is the less damaging of
the two behaviours by a wide margin, and the next attempt at it has to start from a device log
rather than from reasoning — the failure is in what the HAL does between a recorder letting go and
a session coming back, and that is not visible from the source.

The web server and everything else in v3.12 are untouched.

## Roll v3.12 — the camera always comes back

**Filming left the viewfinder black, and nothing brought it back.** Three separate states the
watchdog could not recover from, and stopping a recording tended to produce all three.

**A bind that never delivers a first frame.** Every recovery this app has ever made was from a
camera that produced frames and then stopped — the watchdog reads the gap since the last one.
A fresh bind starts that clock at zero on purpose, because a stale stamp from before a release
would convict a healthy camera, and zero is read as "no data yet". Nothing ever put a deadline on
*yet*. So a session that came up dead — which is exactly what the camera looks like after the
recorder has finished with it — sat at zero for the rest of the process, with the watchdog
declining to look at it. There is a first-frame deadline now: four seconds for a cold camera,
1.2 seconds straight after a recording, where the session is already up and the surface already
attached and seconds of nothing has only one likely meaning.

**A bind that failed outright.** The watchdog's first line was a check on whether the camera was
ready, so the single state the app could not get out of by itself was the single state it refused
to examine: `bindToLifecycle` throws, ready goes false, and Roll sits on a black rectangle until
it is force-quit. It retries now.

**A finalize that handed back a dead camera.** v3.1 stamped the heartbeat to *now* when a
recording finalized, which correctly stopped a false death verdict and also hid the true one — a
camera the recorder had killed looked exactly as healthy as one about to resume. The heartbeat is
cleared and re-armed instead, so the preview owes a frame and has a short moment to produce it.

**And the camera is asked, not only inferred from.** CameraX reports a camera error through
`cameraState` the moment the device disconnects, a session cannot be configured or the HAL returns
something fatal. That is now observed, and it arrives in milliseconds rather than after a stale
limit. It clears the heartbeat rather than rebinding directly — a configuration the HAL refuses
would error again the instant it was rebound, and that is a loop with no counter — so the
watchdog does the work and everything already built around it comes along: the cap on restarts,
the zero-shutter-lag quarantine, the fault report with the state attached.

Three restarts inside a minute used to stand the watchdog down for the life of the process. The
cap is right — a preview that dies again immediately is allergic to something a rebind faithfully
reproduces, and hammering it heats the phone and fixes nothing — but giving up entirely left a
phone whose camera could not return without a force-quit, which is the thing the watchdog is for.
It backs off to once a minute now and keeps trying, quietly.

## Roll v3.12 — the web server is one tap from the roll, and its page filters

**Starting the server was two taps into the send picker, behind a contacts permission it has
nothing to do with.** Share a photograph, scroll past the address book, find the computer. That is
a strange place for it: reaching your own laptop has nothing to do with who a photograph is for,
and somebody who declined contacts could not get to it at all.

It is at the top left of the roll now, where the screen's title used to be. "ROLL" named a screen
you were already looking at, which is the least useful thing a bar can say, and getting
photographs onto a computer is the one job the roll cannot do by itself. It reads **Start web
server**, or **Web server on** while it is running — the state is in the label rather than in a
separate mark, because a server running with nothing on screen saying so is the failure worth
designing out. Tapping it while it runs shows the address and PIN again instead of starting a
second one.

**On the page itself, three things.** A filter — All, Photos, Videos — because a laptop open to
pull one clip off should not be a wall of stills, and the arrow keys respect it rather than
wandering back into the kind you filtered out. The close control moved from the bottom bar to the
top, where every window anybody has ever shut keeps it, and away from the download buttons it was
sitting beside. And a full-size frame now fades in behind a Loading label instead of snapping out
of black when the last byte lands — a frame off the phone takes a moment over Wi-Fi, and the snap
read as a glitch, worst of all when stepping through with the arrows.

## Roll v3.11 — recording works again

**v3.10 broke video, and this puts it back.** Press record on v3.10 and the recording died where
it started, leaving a black viewfinder. One line did it.

v3.10 capped the encoder at 6 Mbit/s. The reasoning behind that was sound — the muxer flush, the
scoped-storage write and MediaProvider's pass over the finished file are all linear in file size,
and all three run before the record button comes back, so halving the bytes halves the wait. The
lever was wrong. `setTargetVideoEncodingBitRate` does not set a bitrate; it sets a constraint the
recorder then has to satisfy against the encoder this particular phone has. When the number falls
outside the range the encoder advertises, the configuration cannot be resolved, the recording
finalizes with an error the instant it begins, and the camera is left holding a session that never
started. Six megabits is an ordinary 720p bitrate, which is why this was invisible without the
hardware in hand.

The device chooses again. Anything that tries this a second time has to ask the encoder what it
will accept — `VideoCapabilities.getBitrateRange()` — and clamp into it rather than assert a
number, and it has to be run on a phone before it is run on everybody's phone.

The other two changes from v3.10 stay, because neither goes anywhere near the encoder: a clip now
carries a `DATE_TAKEN`, and the finished file is re-scanned once after the finalize so the object
a computer is handed over USB has a real size and duration. The Wi-Fi drop is untouched.

## Roll v3.10 — the roll opens in a browser on your laptop

**There has never been an easy way to get a video off this phone.** The share sheet resolves to
messaging apps, and MMS caps out around three megabytes — four seconds of clip. USB works and is
not easy: the phone's USB mode resets to charging on every unplug, macOS needs third-party
software now that Android File Transfer is gone, and a photo importer speaks PTP, which is the
still-image protocol and does not carry video. That last one is why a clip so often lands on a
computer as a thumbnail nothing will open.

So the phone serves the roll itself. Open the send picker and the first destination is **a
computer on this Wi-Fi**. The phone shows an address and four digits; type them into a browser on
a laptop on the same network and there is the roll — day headings, thumbnails, a player that
scrubs properly, every file one press wrote (the JPEG, the lossless copy, the negative) and a
select-several-and-download mode for taking a whole shoot at once. Closing the screen leaves it
running, so you can keep shooting while the laptop downloads.

**The shape of it is deliberately small.** Four reads and no writes: no upload, no delete, no
rename. The PIN is new every time the server starts and five wrong guesses stop it answering.
Every route resolves a MediaStore row id against the list the roll is already showing, so there is
no point anywhere in it where a string off the network becomes part of a filename —
`/file/../../etc/passwd` is a 404 because it is not a number, not because a check caught it. It
serves only what the roll is showing, it stops after ten minutes with nobody asking for anything,
and nothing leaves the network.

Ranges are honoured, which is the difference between a video that plays and a video you can
scrub: a `<video>` element does not download a file, it asks for the index and then for the part
it wants, and a server that answers each of those with the whole file gives you a dead scrub bar.

### Also in v3.10 — a recording stops sooner, and a clip arrives on a computer as a file

**Two complaints about video, and underneath they are the same fact: a clip is bytes, and every
wait it causes is proportional to how many.**

**The stop.** Pressing stop does not end a recording; it asks for one to end. The muxer still has
to flush, rewrite the moov atom and hand the file back, and CameraX then clears `IS_PENDING` —
which sends MediaProvider over the whole file before `Finalize` is allowed to fire. All three of
those are linear in file size, and all three happen while the button says SAVING and the
viewfinder is frozen. Roll was letting the device choose the bitrate, and `Quality.HD` on this
phone takes it from a camcorder profile tuned for a screen this phone does not have. It is now
capped at 6 Mbit/s. A minute of clip lands around 45 MB instead of 90, the three waits halve with
it, and at 720p on a 3.92" panel — or on a laptop — there is nothing to see. The size was never
the complaint. The time was.

**The computer.** A laptop plugged into this phone never reads its filesystem. MTP and PTP both
serve an object list that MediaProvider builds out of its own database, so a clip is only as
visible from a computer as the last scan of it made it — and a row with a size of zero is exactly
what a host shows as a thumbnail it will not open or copy. Clearing `IS_PENDING` normally sends
MediaProvider to go and look, and normally that is enough. When it is not, nothing ever asked
again. Roll now asks, once, after the file is closed and off the path the stop waits on. The scan
is idempotent, so the ordinary case costs a query and a no-op.

**And a clip finally carries the time it was shot.** Stills have written `DATE_TAKEN` since the
first release. A recording went in with a name, a type and a folder, and every reader that files
by capture time — this app's own sort, other galleries, the metadata a computer is handed over
USB — had to fall back to when the row was made.

**What this does not fix.** If videos show on your computer as thumbnails you cannot open while
photos come across fine, the phone is almost certainly handing the computer PTP rather than MTP.
PTP is the still-image protocol; it is what Image Capture on a Mac speaks, and it does not carry
video. On the Light Phone III, set *Settings › Preferences › USB Preferences* to **Media
Transfer** — it resets to charging every time you unplug — and browse the phone with a file
manager rather than a photo importer.

## Roll v3.9 — turning the screen off no longer costs you the photograph

**Take a picture, press the power key while it is still saving, and what landed in the roll was a
black rectangle.** Two faults behind it, and they are opposite in kind: one where the app kept
working and should not have, one where it stopped working and should not have.

**The viewfinder does not fail by returning nothing.** Half of Roll's capture paths take the frame
that is already on the panel — Simple, the Screen size, every coarse filter, and the rescue that
runs when a sensor capture fails. When the window stops drawing, which is what the screen going off
means, `PreviewView.getBitmap()` does not return null. It hands back a bitmap of the right size,
full of zeroes. Every check on that path was a null check, so the black rectangle went through the
shader, through the encoder and into the camera roll with a correct timestamp on it.

The rescue is where it hurt most, and it is worth spelling out because it reads like malice. Turning
the screen off stops an in-flight sensor capture. The catch around that reaches for the viewfinder
frame instead, on the reasoning that a panel-resolution photograph beats no photograph. But the
viewfinder had gone dark for the same reason the capture died, in the same instant — so the
consolation prize for a lost picture was a black file, announced as a success.

A frame is now asked whether it is a picture, not merely whether it is there. Sixty-four points
spread across it: a dead readback is the same number at every one of them, and a real photograph —
even one taken in the dark, even with a hand over the lens — has sensor noise, so it never is. Fail
the test and nothing is saved and the notice says so. The test is deliberately the strictest one
available, because the two mistakes do not cost the same: refusing a real photograph loses a picture
somebody cannot take again, while accepting a dead one writes a black file they have to go and find.

**The other half is that the save now finishes.** The shutter hands every capture to the darkroom
and returns — that is what makes it quick — and the decode, the shader pass, the encode and the
write all happen behind the viewfinder, most of a second for one frame and several for a queue.
Turning the screen off inside that window let the processor suspend with the photograph half
written. It resumed whenever something next woke the phone, which could be minutes, and never at all
if the app was killed first. Roll now holds a partial wake lock while the darkroom has work and
drops it the moment the queue drains: the screen still goes off immediately, the processor stays up
just long enough to finish the file. There is a three-minute ceiling under it so a bug in that
bookkeeping cannot flatten a battery.

What this does not change: a sensor capture that was in flight when the screen went off is gone.
The camera is unbound the moment the app stops and there is no getting that frame back. The
difference is that Roll now says "Screen went dark mid-shot. Nothing saved" instead of writing a
black file and calling it a photograph.

## Roll v3.8 — the flash fires

**Reported plainly: "the flash never fires when taking a photo."** It never did. Not in Pro, not in
Simple, not on Auto, not on a filter. The icon lit, the chip cycled through its three states, and
the lamp stayed dark in every mode the app has. The torch kept working the whole time, which is why
this looked like a setting that had not taken rather than a camera that could not fire.

Three separate faults, and each one would have been enough on its own.

**Auto exposure was overwriting the order to fire.** On Camera2 a flash is not its own control. It
*is* the auto-exposure mode: CameraX turns `FLASH_MODE_ON` into `CONTROL_AE_MODE_ON_ALWAYS_FLASH`
and `FLASH_MODE_AUTO` into `CONTROL_AE_MODE_ON_AUTO_FLASH`, and asks the camera for that on the
still. Roll's manual-exposure code sets `CONTROL_AE_MODE_OFF` when you hold a shutter speed, and the
other half of that branch set `CONTROL_AE_MODE_ON` when you do not — which reads like the harmless
opposite and is not, because options set that way take priority over the ones CameraX sets. So every
capture in the app went out carrying plain auto exposure with the flash instruction stripped off it.
Auto exposure now says nothing there, which hands the decision back to CameraX. Coming out of manual
still restores metering, by the same mechanism as before.

**Half of Roll's photographs never asked the sensor for a frame.** Simple shoots the picture that is
already on the screen. So does the Screen size, and so does every coarse filter whatever the size
says — that is the whole reason those paths are fast. A flash mode belongs to a capture, and on
those three there is no capture, so the flash setting reached nothing at all. A grab has no exposure
to synchronize with, so what it gets now is the lamp held on across it. On a phone that is what a
flash already is: an LED, not a xenon tube, and the only difference is that it stays lit for a third
of a second instead of a hundredth.

The lamp is held for 320 ms before the frame is taken, and the wait is the meter rather than the
light. An LED is at full output immediately. The preview is still metered for the room as it was a
moment ago, and a frame grabbed the instant the light arrives is that old metering applied to a
newly lit scene, which is a face burned to white against a black room. Every camera runs a preflash
for this reason. Roll runs it on the preview stream, because on these paths the preview stream is
the photograph.

**Auto now means something in Simple, and stops being switched off behind your back.** Simple used
to force Auto down to Off on the way in, because Auto costs a metering pass even when it declines to
fire. That cost is real on the path that calls the sensor and Simple never calls it, so the rule was
changing a setting to avoid a bill the mode was not being sent. Auto on a panel grab is one
downscaled brightness reading of the frame you are already looking at, taken only when Auto is
selected and the camera has a lamp.

**A tap on the flash chip no longer expires.** Every recovery path in the camera rebinds with the
flash the session was last *bound* with — the stale-preview watchdog, the zero-shutter-lag abandon,
the bind owed after a recording, the flat and lens-correction toggles. The chip wrote the new mode
onto the live use case and nowhere else, so the next rebind built a fresh one from a value that
predated your tap and the flash quietly went back to off with the icon still lit. Since the setting
had not changed, nothing put it back: the only way out was cycling the chip twice. The chip now
writes the mode the rebinds read.

Three smaller notes. A torch you switched on by hand is left alone — it already lights the scene,
and a lamp cycle would end by turning your torch off as a side effect of taking a picture. The lamp
goes out in a `finally`, so a canceled press or a failed grab cannot leave a camera that has quietly
become a flashlight. And a Purikura strip flashes on all four panels or none, because four frames
lit three different ways is a strip that does not stack.

## Roll v3.7 — a scanned code goes to Web Tools

**Open, on a scanned link, now names Web Tools.** The Light Phone III ships with no browser at all,
so `ACTION_VIEW` on an `https` address resolved to nothing and the row did nothing — and on the day
a second app answers for web addresses, an unnamed intent puts a list of apps in front of someone
standing at a poster. Web Tools is asked for by name, and the general intent is still what runs on a
phone that does not have it.

**A Web Tools code is its own kind of code now.** The companion page at gi-os.github.io/WebTools
writes a shelf tool as a line of JSON. Point the camera at one and until now you got TEXT, the raw
payload, and a COPY — a code whose only purpose is to be installed, and no way to install it. The
sheet reads WEB TOOL, leads with the tool's name or the site it opens, and the row says ADD TO WEB
TOOLS. A code too big for one image says which part of it you just read.

**Roll reads the envelope and does not open the letter.** The payload goes across whole. Whether the
JSON holds together, which sites the wall ends up covering and whether a login unpacks are Web Tools'
questions, and it already has the parser and the words for every way they fail — so a bad code gets
its answer rather than a second opinion formed in the camera. It also means the two apps cannot drift
apart as the companion page changes.

## Roll v3.6 — video no longer locks the camera shut

**Record a clip and the camera freezes on one frame, then stays black.** Reported plainly: "recording
video freezes the camera and leaves the camera with a black screen." Two separate faults, and the
second one is why it never came back on its own.

**The freeze is the save, and the app never said so.** Stopping a recording does not end it. The
muxer still has to flush its buffers and rewrite the file's index, which on this phone takes several
seconds, and while it runs it stalls the camera's repeating request — so the viewfinder holds the
last frame it got. The app knew only that it was recording, so it went on drawing a record dot and a
counting clock over a picture that had stopped moving. That is a crashed camera, as far as anyone
holding it is concerned. The badge now reads `SAVING` against a hollow dot and the clock stops at
the length of the clip, and a second press of the button says "Still saving" instead of being taken
for a request to start again.

**The black screen is a flag that nothing ever lowered.** Every path in the camera that rebinds —
the mode switch, the lens flip, the watchdog — refuses to run while a file is being written, because
tearing the camera out from under a muxer mid-flush takes the camera service down with it. One path
did not refuse: the one the pager drives when you come back to the viewfinder. So the ordinary
gesture — stop filming, flick up to the roll to see the clip, flick back — rebound the camera in the
middle of the flush. The camera died, and because it died the recorder never reported that it had
finished, which left the flag raised. Raised, it shut every rebind path in the class, including the
watchdog whose whole job is to notice a black preview and fix it. Nothing lowered that flag for the
rest of the session.

Four changes, and each one closes the hole on its own:

- Coming back to the viewfinder now waits, the same as everything else does, and the bind it owed is
  paid the moment the file is finished. Leaving mid-recording is handled the same way round.
- The flag has a deadline. If the recorder has not reported in thirty seconds it is gone, and
  holding the camera shut for it only protects the bug — so the flag comes down, the dead handle is
  dropped and the viewfinder is rebound.
- The watchdog checks that deadline before it stands down, so it can never be muzzled for the life
  of the process by something that happened once.
- A stop the recorder refuses outright no longer raises the flag at all.

**Two smaller faults found underneath.** A stop that threw would have locked the camera the same
way, and the wait for a finished file watched the wrong half of the state: it woke only when
recording changed, so the case it exists for — recording already down, file still writing — ran the
full thirty seconds and then reported a recorder that had in fact finished. Both fixed. And two
quick presses of the record button could both start a recording, because the camera does not report
that it is recording until a moment after it begins; the second press threw, and the failure path
pulled the state down under the recording that was running, leaving a button that could neither
start nor stop.

## Roll v3.5 — a dark preview now says what it knew

**Three reports say the preview went dark and the camera restarted, and none of them says anything
a fix could start from.** [light-reports#233], [#293] and [#309], against v3.1, v3.3 and v3.4 — one
of them from somebody who is not the author. The watchdog is working exactly as designed in all
three: it notices the capture stamps have stopped, rebinds, tells the user, and files a fault so a
camera that heals itself is still a camera whose disease gets reported.

The trouble is what the fault carries. One sentence, and the sentence is already known.

**Everything that would narrow it down was in hand and thrown away one line later.** How long the
stamps had been still, what counted as too long at that moment, whether the zero-shutter-lag ring
was still in play, which flash mode the session was holding, whether the exposure was manual — the
watchdog reads all of it to make its decision and then keeps none of it. The report now carries the
lot, along with the mode, the filter, zone focus, and how many captures were in flight.

This matters because the last explanation is spent. The finalize race — the muxer flush stalling the
repeating request so every recording ended in a false death verdict — was the cause of the earlier
dark previews and it is fixed. Whatever is behind #233, #293 and #309 is something else, and there
is nothing in "Preview went dark. Camera restarted." to find it with.

**The title is fixed too.** A fault is headed "Could not <what>", and this one passed a sentence
where a verb phrase belongs, so three issues arrived titled *"Could not Preview went dark. Camera
restarted"*. It now reads "Could not keep the preview alive", and the three-strikes fault beside it
"Could not keep the camera alive — three restarts inside a minute".

Nothing about when the watchdog fires has changed. This release only makes the next one of these
worth reading.

Addresses [light-reports#233], [#293] and [#309] — instrumentation, not a fix. They stay open until
a report on this build says what the numbers are.
