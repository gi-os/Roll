## Roll v2.55 — the readouts turn with the phone, and 3.5x stops lying

**`TORCH`, `3.5x`, `EV +1.0` were sideways every time you shot landscape.** The band is pinned
sideways on purpose — turn the phone anticlockwise and the controls come round to the bottom edge,
where a camera's controls belong. The readouts in the top corner were pinned with it, and they are
not controls. They are words you read, and words on their side are words you tilt your head at.
They now turn off the accelerometer like the scan sheet does, with the same 60° of hysteresis, so
they stay upright whichever way you are holding the camera and do not flip while you compose.

**And the zoom readout was stale.** Come back to the viewfinder from the roll and the lens is at
1x, but the corner still read `3.5x`, and went on reading it until you touched the zoom again. The
ratio was being read back out of CameraX's `zoomState`, which lives on a `CameraInfo` that survives
the unbind — so just after a rebind it still reports what the zoom was before, while the control
underneath has already gone wide. Every path through that code is one where the zoom resets anyway,
so it no longer asks: it sets 1x, on the state and on the control together, and the label goes away
with the zoom.
