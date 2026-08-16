## Roll v2.46 — Adjust with the picture in front of you

A grade is not something you can reason about from its numbers. "Warmth +2" means nothing until you
see it on the thing you are pointing at, and v2.45 put the ten adjustments behind a menu that
covered the frame — so you set a value, closed the menu, looked, opened it again.

The adjustments now live in a narrow column down one side with the **viewfinder live beside them**.
Only the column takes touches: the shutter, the wheel and the half press all still work with it
open, so you adjust and shoot without closing anything. Names are shortened to fit and the
explanatory line is gone, because with the picture right there neither was doing any work.

It is the first thing in the band now, where the album icon used to be. The album was spending a
permanent slot on a shortcut to a swipe you already had; the adjustments had nowhere at all. The
roll is still one swipe from the viewfinder, exactly as before.

**The mode chip says PHOTO again.** In Pro it names the filter, because which filter is on is the
one piece of state you cannot read off the picture — but the first slot is Preset, and "PRESET"
sitting in the band told you nothing. Whether anything is actually set is the Adjust control's job.
FILM, MONO and the rest still name themselves.

Your adjustments persist across restarts, as they did before — one preference key per adjustment,
and there is now a test pinning those key names, because renaming one would silently reset
everybody's preset to zero on upgrade with no error anywhere.

### Datamosh is real now

v2.45's Datamosh was a shader that simulated the *causes* of JPEG corruption. It looked convincing
and it was still a drawing of a broken photograph rather than a broken photograph.

This one does what cebola4444's cybershot-cam does, ported to Kotlin and pointed at the file the
encoder just produced. Five operations on the actual bytes:

| | Marker | What it edits |
| --- | --- | --- |
| Zigzag rotation | `FFDB` | circular-rotates the 63 AC quantisation values, so frequencies swap roles |
| Frequency erosion | `FFDB` | low frequencies to 1, high to 255 — detail erased, blocks posterise |
| Chroma amplification | `FFDB` table 1 | chroma table only, so colour explodes while luminance stays sharp |
| Huffman rotation | `FFC4` | rotates AC symbols within each magnitude group |
| **Scan transplant** | `FFDA`→`FFD9` | overwrites runs of the entropy-coded stream |

The last one is the mosh. JPEG packs every block into one continuous bitstream with no byte
alignment between blocks, and each block's DC coefficient is stored as a *difference* from the one
before it. So overwriting a run of scan bytes does two things at once: the reader loses block
alignment, and the DC difference chain inherits an error that every later block adds to. Because
blocks are written left to right, that error drags sideways. That drag is datamoshing.

Intensity scales with how dark the frame is, which is the reference's behaviour rather than an
arbitrary choice — it drives the glitch off sensor gain, and gain is high exactly when the
photograph is already noisy.

**There is no preview, and that is honest rather than lazy.** The damage is done to a compressed
file, and no compressed file exists until you press the shutter. A shader approximation would show
you a different set of artifacts from the ones you get.

**One deliberate departure from the reference, four bytes wide.** Inside valid scan data an `FF` is
always followed by `00` or a restart marker, so a transplanted run stays legal in its middle — the
only place it can accidentally spell `FF D9` is where it butts against the bytes it replaced. `FF D9`
is end-of-image: every decoder stops there and the photograph becomes whatever was above the tear
plus a grey rectangle. Databenders enjoy that; a camera cannot ship it. Both seams are checked and a
stray `FF` is nudged to `FE`, which costs one coefficient in one block.

Being plain bytes rather than a shader, this is also the first filter in the app that can be tested
properly without a phone. Twelve tests cover it: the file keeps its markers and its length, the DC
quantiser survives, rotations permute without inventing or dropping symbols, malformed input comes
back untouched instead of throwing, and forty seeded runs are checked for invented markers.
