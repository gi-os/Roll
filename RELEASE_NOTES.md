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