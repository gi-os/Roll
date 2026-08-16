## Roll v2.47 — a clip looks like a photograph again, and you can turn it down

**A video in the viewer was a black rectangle with a play triangle floating in the middle of it.**
Every other page in that pager is a picture, so a clip did not read as a clip — it read as a frame
that had failed to load, and the only way to find out what was on it was to press play.

The viewer decodes its full-screen frames with `BitmapFactory`, which reads image files and nothing
else. Handed a video it returns null, every time, silently. The roll grid never showed the problem
because it asks MediaStore for a thumbnail instead, and MediaStore is happy to produce one for a
clip. So the fix is to ask the same question in the viewer: a clip's poster frame now comes from
`loadThumbnail`, which is usually already cached and applies the clip's rotation on the way out, so
the still you see before you press play is the same way up as the video that follows it. A clip
MediaStore has no thumbnail for falls back to a single-frame decode of the opening keyframe.

**And the volume keys work while a clip is playing.** Both of them are a shutter by default — the
phone has no shutter button on its screen, so that mapping is load-bearing — and Roll takes them in
`dispatchKeyEvent`, before the view hierarchy and before the system. That is right in front of a
viewfinder and wrong in front of a video: a clip would play at whatever the phone's media volume
happened to be, with no way to move it, because the keys were being spent on a shutter belonging to
a screen that was not even visible.

While a clip plays, and only then, the volume keys report as unbound. That already had a defined
meaning — an unbound key is handed back to the phone rather than swallowed — so the keys do what
they do everywhere else, and nothing new appears on the panel. The flag is cleared when playback
ends, when you swipe to another frame, and when the viewer closes, because a flag left set would
take away the fallback shutter with nothing on screen to explain it. A test pins both directions.

Fixes [light-reports#24] — a video showed no first frame and its volume could not be changed.
