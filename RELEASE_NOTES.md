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
