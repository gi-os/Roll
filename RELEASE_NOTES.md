## Roll v2.52 — Datamosh is the motion, not the file

**Datamosh has been chasing the wrong mechanism for six releases, and this changes it.** It is a
shader now: it draws the smear instead of breaking the JPEG, it looks like the thing people mean by
datamoshing, and for the first time it previews in the viewfinder.

The old one edited the encoded file — quantisation tables, Huffman tables, the entropy stream — on
a principle written into it that "you cannot get them by drawing, only by breaking". That is true
of *JPEG* artifacts. It is not true of datamoshing, and the two had been conflated the whole time.

Datamoshing is a **video** technique. You delete an I-frame, and the P-frames after it — which
carry only *motion*, "this macroblock came from over there" — get applied to whatever pixels were
left in the reference buffer. The picture melts along the motion of a scene it does not belong to.
Every tool that does this works on video, for that reason.

A JPEG has no motion vectors. Nothing in the file says where a block came from, so there is nothing
to misapply, and no amount of breaking the entropy stream can produce the effect. What it produces
instead is a broken **DC difference chain**: each block's average is stored relative to the one
before it, so a single bad seam recolours everything below it in raster order. Flat coloured bands
over an otherwise untouched photograph — which is exactly what kept being reported, and what v2.48
through v2.51 kept re-tuning without ever being able to fix, because the tuning was on the wrong
axis.

So the motion is drawn. The frame is cut into horizontal runs a few macroblocks long, a different
length on every row; about a third of them take a vector, and every macroblock inside a run is
painted from the *same* source block. That is what an un-reset motion vector does, and it is why
the result drags and repeats sideways rather than shuffling. The channels are pulled by slightly
different amounts, so edges fringe the way a chroma-subsampled frame does when its blocks stop
lining up. A trace of the original stays underneath, which is what leaves a subject standing in the
middle of it. It is sized off `unitPx` like every other pattern here, so a macroblock is the same
fraction of the picture in a 340px preview and a 4000px capture.

**It previews.** The old one could not, by construction — there was no compressed file to damage
until the shutter had already gone, so you framed blind and found out afterwards. This is an
ordinary shader on the ordinary path, so what you see is what you get, and it can no longer produce
a file a decoder refuses.

`Databend.kt` and its tests are gone with it. The port was faithful and the tests were good; the
approach could not reach the target. Worth saying plainly rather than leaving it in the tree for
somebody to wire back up.

Fixes [light-reports#29] — Datamosh did not look like datamoshing.
