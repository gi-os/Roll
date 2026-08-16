## Roll v2.51 — Datamosh tears the photograph instead of recolouring it

**Datamosh was coming back as a few flat coloured bars.** The photograph underneath was largely
intact and largely untouched; what you got was three or four horizontal bands, each a wrong solid
colour, and none of the smeared, dragged, repeated look the filter is for.

The cause is two numbers that were never scaled to the file, and it is why the last release looked
right in the evidence and wrong on the phone. The port came from an ESP32 camera working on a
thumbnail, where **two to four transplants of up to 4096 bytes** is most of the frame. A 12MP
capture's entropy-coded scan is several megabytes, so the same constants produced four tears of
under a thousandth of the file each.

A tear that short desynchronises the decoder for a handful of blocks and then it re-syncs. The
smear never gets going. What does survive is the *DC difference chain* — every block's brightness
and colour is stored as a difference from the block before it — inheriting one error at the tear.
That chain runs in raster order, so every block below the tear carries the same wrong average
colour to the bottom of the image. Four tears, four flat bands. Turning the intensity up did
nothing, because intensity never touched either number: it drove the quantisation tables, which
flatten detail, which is exactly what made the bands look painted on.

Both numbers scale now. The run is a fraction of the scan rather than a byte count, and the count
rises with intensity instead of sitting at two-to-four. The tears overlap, the decoder never
settles, and the block displacement — the part that reads as a mosh — is visible across the frame
rather than being a colour shift at four seams.

Checked off the phone against a 12-megapixel frame, at every intensity `bend` can produce: the
subject stays recognisable at all of them, the tears drag and repeat, and forty seeds at maximum
intensity still invent no end-of-image marker — which remains the one thing this filter must never
do, because half a photograph and a grey rectangle is a bug rather than a filter. A test now pins
the property that was missing: the damage has to be a fraction of the file, not a fixed number of
bytes, checked at both ends of the size range. A constant looks perfectly correct at whichever
size it was chosen for, which is how this survived a release.

Fixes [light-reports#29] — Datamosh returned five coloured bars rather than a glitched photograph.
