## Roll v2.54 — the saving bar comes back for flash shots

**Press the shutter with the flash on and the panel said nothing at all.** No frozen frame, no bar,
just a live viewfinder for the second and a half the capture takes, and then a photograph. It read
as a shutter that had not fired.

The bar was nested inside the held-frame branch, so the two arrived and left together. That held
for as long as every still froze the panel — but a flash shot deliberately does not freeze it. The
preview frame grabbed at the press predates the flash, so holding it would show a dark room and
then save a lit one, and the freeze was dropped for that case. The bar went with it. The same gap
opens whenever `previewView.bitmap` hands back null, which it does when the panel is not streaming.

The bar now reads `shooting`, which is latched across the capture and the save, so it draws whether
or not there is a frame over the preview. It is suppressed while a countdown is on screen: the
Purikura strip keeps `shooting` up across all four frames, and the number already says what the
camera is doing.
