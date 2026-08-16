## Roll v2.45 — Your videos were there the whole time

Every clip this camera has ever recorded was written correctly, finalised correctly and was visible
in every other gallery on the phone. It just never showed up in *this* one, which reads as a
recorder that quietly throws the take away.

MediaStore keeps stills and clips in two separate tables. For eleven versions the roll only ever
asked the first one. Nothing was ever lost; the query simply never looked. It looks now — both
tables, merged newest-first, so a clip and a still taken in the same second sit next to each other
instead of in two blocks. Clips carry their running time in the corner of the cell, because a video
shown as a still of its first frame is indistinguishable from a photograph, and open with a play
triangle over the poster frame.

**Nothing needs to be re-recorded.** Every clip you have ever taken with this app is already in the
roll as of this build.

### Coming back to Photo after filming

`Recording.stop()` is a request, not an event. The muxer still has to flush the file and clear its
pending flag, and only then is the recording actually over — but the camera was allowed to rebind
during that gap, and the app had already committed the mode change before asking whether the camera
had accepted it. So the interface would be drawing Pro, with the filter dial live and the shutter
wired to `takePicture`, while the camera was still bound to the video pipeline and the stills unit
behind it was attached to nothing at all. The next press went straight through the floor.

Three things now, and each of them alone would have been enough:

- Tapping a mode while filming means stop filming. It used to say "Stop recording first" and do
  nothing, which is the app arguing with an unambiguous instruction.
- The switch waits for the file to finish before rebinding, with a two-second ceiling — a phone that
  will not go back to taking photographs is worse than a clip that came out short.
- The shutter refuses to fire at a camera that is mid-rebind, instead of throwing from inside a
  callback where nothing can catch it.

### Half press clears the frame

A finger resting on the first detent is a finger about to take a photograph, so the mode strip, the
filter grid, the exposure and zoom strips and the Purikura menu all close on the half press — before
the lens has finished hunting. A menu you have to dismiss before you can shoot the thing you were
already looking at is a menu in the way.

### A muted phone is a quiet camera

The shutter checked the ringer switch, which is only half the question. A phone muted by holding
volume-down until the bar is empty often stays in the normal ringer mode with the stream sitting at
zero — and on this phone, with no ringer switch to flick, that is the only way to mute anything. So
the shutter, the focus blips and the saved tone kept sounding on a phone that had been unambiguously
silenced. All three now read the system, notification and ring streams as well, including a stream
muted by policy while its remembered volume is still set.

### None is now Preset

The first slot on the dial was None, which is not a look but the absence of one, and it was the slot
most photographs were taken in. It is Preset now: still the plain photograph by default, with
somewhere to put the small corrections a photograph actually wants.

Ten adjustments, behind an **Adjust** chip in the band that also tells you how many are set — which
is the one piece of state you cannot read off the picture, because a photograph a third of a stop
warm still looks like a photograph.

| | |
| --- | --- |
| Exposure | in stops, so the number means something |
| Contrast | pivoted on mid grey, not on the frame's own average |
| Highlights | recover a blown sky, or push it whiter |
| Shadows | open up what is in the dark, or crush it |
| Vibrance | weighted by what is missing colour, and held back on skin |
| Warmth | a real temperature shift: red against blue |
| Tint | the other axis: green against magenta |
| Sharpness | unsharp mask above zero, a plain blur below it |
| Grain | modulated by the midtones, the way Film's is |
| Vignette | darker corners, or brighter ones |

Steppers rather than sliders, eleven positions with a detent at zero, because the two ways you touch
this phone are a thumb on a small panel and a click wheel and neither can land a continuous slider
on a value.

**A Preset with nothing set costs nothing.** It is not an identity shader running thirty times a
second over the viewfinder and once more over the file — the whole GPU path is skipped, and the
shutter writes the sensor's own JPEG exactly as it did before. That is deliberate: this is the
default filter, so it had to stay free.

The order the adjustments run in is a darkroom's, not the list's, because these do not commute.
Detail, then tone, then colour, then the two things laid on top of the finished photograph.
Sharpening after a vignette sharpens the vignette.

### Datamosh

A JPEG is not pixels. The image is cut into 8×8 blocks, each block becomes 64 frequency
coefficients, and the whole lot is packed into one continuous bitstream with no byte alignment
between blocks. Two consequences produce everything this filter does:

**Block averages are stored as differences.** Each block's brightness is written as an offset from
the previous one, so an error is inherited by every block after it — and since blocks are written
left to right, the error drags sideways. That drag is what people mean by datamoshing.

**Losing the bitstream means losing alignment.** From the moment the reader is off, block boundaries
are in the wrong place: blocks land displaced, their detail decodes as noise, and colour — stored at
half resolution — smears twice as far as the shapes it belongs to. It heals at the next restart
marker, which is why the damage comes in bands rather than running the whole height of the frame.

All five of those are what the filter models, plus the quantiser coarsening that gives it its
blocking. It is modelled on cebola4444's cybershot-cam, which does the real thing to real bytes
after its encoder has run. That cannot be a filter here — every filter in this app is one shader
over both the viewfinder and the file, which is what makes the photograph match the frame you were
looking at, and a byte hack applied after encoding has no live form. So the causes are simulated
rather than the bytes, which means you can see it before you press, and it works in the modes a
post-encode hack could not reach.

Animated, so the damage moves — and so no two shots come out the same, which is also true of the
real thing.
