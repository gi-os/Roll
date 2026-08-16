## Roll v2.48 — Datamosh leaves a photograph behind, and the date reads on black and white

**Datamosh returned confetti.** Not a glitched photograph — coloured static, with nothing
underneath it you could recognise as the thing you had pointed the camera at. It had been that way
since v2.46 swapped the old shader for a real databend of the encoded file, and it was reported
three times before anyone could say what "doesn't work" actually looked like.

Two of the five ported operations were doing it, and both for the same reason: they damaged the
whole frame uniformly instead of tearing part of it.

`rotateHuffman` rotated AC symbols inside each magnitude group of the Huffman tables. The
reasoning written above it was half right — rotating within one magnitude does keep every code
length valid, so the file stays in sync and decodes. But a symbol's low nibble is the
coefficient's *size* and its high nibble is the *run of zeroes before it*, so rotating within a
size group rewrites the run. Every coefficient in every block in the image lands at a different
frequency. That is not a tear that drags sideways, it is a global reshuffle, and it was already
past the point of no return at the lightest intensity the app can produce — so the exposure-driven
intensity control had nothing left to control.

`amplifyChroma` wrote 162–180 into the chroma table's DC slot, where a real table holds 17–99. A
chroma DC quantiser that large snaps each block's average colour to a wildly wrong value, which is
where the flat acid green and magenta came from.

Both are gone. What remains is `rotateZigzag`, `erodeQuant` and `transplantScan` — the last of
which is the actual mosh, and always was: it overwrites runs of the entropy stream with bytes from
elsewhere in the same scan, so the decoder loses block alignment and the DC difference chain
inherits an error that drags sideways through the rest of the frame. The face survives, the tears
are visible, the colour still goes. Two tests hold the line: the Huffman tables must come out
byte-identical, and every quantisation table must keep its DC term through the whole treatment.

**Datamosh has also moved on the dial.** It was the last filter in the list, and because stepping
wraps — a physical dial should never dead-end — last is exactly one notch *backwards* from Preset.
Reaching for the plain photograph and overshooting by a single click landed on the one filter that
deliberately damages the file, which is how it kept getting switched on by accident. It now sits
eleventh of twenty-one, which is as far from Preset as any entry can be in either direction. The
wrap is untouched; nothing else moved.

**And the date stamp stops printing in amber on a black-and-white photograph.** The stamp is drawn
*after* the filter on purpose — a date back printed through the film gate puts the date on the
emulsion rather than under it, and dithering the digits along with the picture turns them into
confetti — so it never sees what it lands on. On Mono, Dither BW, 1-Bit and Halftone it was landing
at full colour.

Those four filters now say so, and the stamp asks. The mono palette is not the amber desaturated:
take the hue away and contrast is the only channel left, and a 1-Bit frame is nothing but white and
black, so a light-grey date would vanish over half of it. The bloom inverts instead — near-black,
under near-white lamps — which leaves a dark keyline around every lit dot. On white the keyline
reads the digits; on black the lamps do. All three styles keep their shape, and Game Boy and X-Ray
are deliberately not included: they have a colour of their own, and a white date on a Game Boy's
green is not more correct than an amber one, only different.

Fixes [light-reports#23], [light-reports#29] and the first half of [light-reports#27] — Datamosh
returned confetti rather than a glitched photograph.
Fixes the second half of [light-reports#27] — Datamosh sat one notch backwards from Preset and was
being selected by accident.
Fixes [light-reports#25] — the date stamp printed in amber on black-and-white frames.
