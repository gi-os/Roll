## Roll v3.2 — one press, one photograph

**Press the shutter, get two pictures. Not a bounce and not a bug in the sensor path: the
hold-to-burst clock from v2.9x was set too short for the button it was timing.**

The camera key has two detents. Pressing it through to the bottom and letting it back up is a
deliberate movement, and on this phone it routinely lasts half a second. The burst clock started
firing at 450ms of hold, so an ordinary press made one photograph at the press and a second at
the 450ms mark, then lifted before the third. Every press, exactly two. The threshold is now
900ms — past any single press measured, and still well short of the camera feeling like it
ignored a real hold.

Two things around it were wrong as well and are fixed in the same pass:

- **The burst stopped on the wrong edge.** It waited for the whole button to come up. Easing back
  to the half detent after a shot — the natural thing to do with a two-stage release, and what
  keeps the focus lock — left the bottom key up but the clock running, so the camera kept firing
  while you aimed. `ShutterRelease` now reports the bottom detent letting go as its own event,
  and the burst stops there.
- **Contact chatter could fire twice.** When CAMERA wins the race against FOCUS and bounces
  DOWN-UP-DOWN inside a few milliseconds, the UP in the middle had already settled the press, so
  the second DOWN counted as a new one. A second CAMERA DOWN inside 150ms of the shutter is now
  read as chatter. Two deliberate presses 300ms apart are still two photographs; there is a test
  for both.

Hold-to-burst still works: hold the key down and it fires about three a second until you let go.

## Roll v3.1 — stopping a recording no longer restarts the camera

**Every video ended the same way: viewfinder dark, then "Camera restarted." The recording itself
was fine. The watchdog was reading a clock that nobody had restarted.**

The chain, in order. Stopping a recording asks the muxer to flush, and on this hardware that
flush takes seconds — it stalls the repeating request the whole time, so the preview stops
delivering frames and the frame-stamp clock stops with it. That part is known and handled: the
`finalizing` guard holds the watchdog off for exactly as long as the muxer is writing, because
rebinding mid-finalize takes down the camera service.

What was missed is the handoff at the end. When `Finalize` lands and the guard drops, the last
frame stamp is still from *before* the stop — five, ten, twenty seconds old, already far past the
4-second stale limit. The watchdog's next tick, at most a second and a half later, compared now
against then, declared the camera dead, and rebound it. A false verdict, delivered after every
recording whose flush outran the limit — which on this phone is every recording. Hence the report:
"froze after recording video every time."

It cost more than the restart. The watchdog's first dark-preview response is to quarantine
zero-shutter-lag for the session — the right move against a genuinely dying session, and pure
collateral here. Every video quietly degraded the photo mode you went back to.

One line fixes it: the stamp clock restarts the moment the recorder lets go, before the guard
drops. The preview gets the full stale limit to deliver its first frame after the muxer's stall —
and a session that genuinely died during the flush is still caught, one limit later. Restarted to
now rather than zeroed, deliberately: a zeroed clock never fires, and a camera that died in the
flush would have kept a blind watchdog and a dark viewfinder forever.

Fixes [light-reports#214] — froze after recording video every time.
Fixes [light-reports#213] — Preview went dark. Camera restarted (v3.0.148).
Also explains #207, #192, and the v2.9x "fault chip" family, all closed as duplicates — this code
predates v3.0, which is why the same report arrived from both v2.94 and v3.0.
