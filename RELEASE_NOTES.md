## Roll v2.57 — a new signing key, and a stranger can no longer choose where a photograph lands

**Uninstall Roll before you install this one.** Every release up to v2.56 was signed with a key
that was committed to this repository with its password written three lines under it. Anybody who
cloned the repo could build an APK that Android would accept as an update to yours, and that is
the whole of the trust model for a sideloaded app. The key has been replaced with a 4096-bit one
that exists only as a CI secret, and the old one is gone.

Android identifies an app by package name *and* signing certificate, so there is no gentle way
through this: v2.57 will not install over v2.56. `adb install -r` and Obtainium both stop with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall once, install v2.57, and updates go back to being
ordinary from here on. Your photographs are in the shared gallery and are not touched by the
uninstall. Roll's settings and any film roll you have not finished are in app storage and are.

**Another app could pick the file Roll wrote to.** Roll answers `IMAGE_CAPTURE`, and the caller
names the destination in `EXTRA_OUTPUT`. Roll took that at its word and opened a stream on it —
with Roll's identity, not the caller's. The activity is exported and shows over the lock screen,
so an app holding no permission at all could point a capture at Roll's own database, at another
app's file, at anything Roll could reach, and have Roll overwrite it with a JPEG.

The destination now has to be a `content:` Uri, it cannot belong to one of Roll's own providers,
and the caller has to have proved it may write there — either by attaching the write grant to the
intent, which is what a well-behaved caller already does, or by holding the grant itself. A
caller that fails all of that gets the photograph filed to the gallery as an ordinary shot rather
than written somewhere it had no business naming.

**Everything CI runs is pinned to a commit.** The build job holds the signing key, so the actions
it calls are pinned by SHA rather than by a tag anyone upstream can move.

## Roll v2.56 — the roll is in colour too

**Colour stopped at the viewfinder.** With the adb grant given, the camera and the full-screen
viewer both lifted LightOS's greyscale, and the roll — a grid of nothing but colour photographs —
was the one picture surface left in monochrome. Swipe up from a frame you had just taken in colour
and the same frame was grey in the grid above it.

The roll now holds colour on the same terms as the viewfinder: it is on whenever the grid is the
page you are looking at, and off the moment you leave the app. `ColorMode` already counted holders
rather than flipping a flag, so the camera and the roll overlapping mid-swipe costs nothing and
there is no flicker at the hand-off.

**The Colour setting says what it now means.** "Viewfinder" is called **Pictures** — the
viewfinder, the roll and the viewer — and **Whole app** is what adds settings and the send picker
on top. Off is unchanged. The stored value did not change, so an existing choice carries over.

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
