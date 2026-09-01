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
