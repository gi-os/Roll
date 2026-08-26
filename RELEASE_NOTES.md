## Roll v2.61 — reporting moved into light-common

Nothing about the camera changed. Shake-to-report — the sensor, the corner chip, the crash-log
offer, the screenshot, the queue and the sheet — was six files, a UI sheet of its own and a
hundred-odd lines of `MainActivity`. It is one `ReportOverlay()` now, and it is the same code
BrightChat, BrightTip and eleven others have been running.

Roll is where `Screenshot.kt` was written, and the reason all three of the apps still carrying a
local copy could not move: swapping without it would have quietly dropped the picture from every
issue they file. It went into light-common 1.4.0 unchanged — `PixelCopy` off our own window, no
permission, greyscaled down a 360/280/200px ladder into the issue body — and is now the version
every app uses.

New here as a result:

- **BUG or IDEA.** A shake can file a feature request, not only a fault.
- **An optional phone number**, remembered between reports, so an unreproducible one can be
  answered with a question.
- **A report says which phone filed it**, as an eight-hex install id and a `mine`/`field` label.

The chip is still bottom-start, off the shutter and the album. The accelerometer still runs only
while Roll is in front, which matters more here than anywhere: a camera is carried, pointed and
moved for a living, so the four-second chip is what keeps a misread gesture cheap.

## Roll v2.60 — the colour notice reads the whole setting

**Roll opened with "Colour needs an adb grant — see settings" on a phone that was sitting there in
full colour.** BrightControl was holding the daltonizer off monochromacy, the viewfinder came up in
colour exactly as it should, and Roll still announced that colour was unavailable. Settings › Look ›
Colour printed the adb line underneath, which made the notice look like the truth rather than a bug.

The check was reading half of the setting. Android's colour correction is two secure settings, not
one: `accessibility_display_daltonizer_enabled`, a flag, and `accessibility_display_daltonizer`, a
mode. The daltonizer's own off is mode **-1**. Mode **0** is *simulate monochromacy* — a correction
that is switched on and takes all the colour out — and enabled 1 with mode 0 is the pair LightOS
pins the phone to. `ColorMode.phoneIsColour` looked only at the flag, so it answered "grey" for any
state with the flag set, including the one where something else had left the flag alone and moved
the mode instead. That is the ordinary way to override the daltonizer, and it is the way
BrightControl does it.

`phoneIsColour` now reads both and means what its name says: the screen is in colour if the
correction is off, or if it is on and set to anything that is not monochromacy. Reading a secure
setting has never needed a permission — only writing does — so the answer is honest whether or not
the adb grant was ever given. The same predicate now backs the Settings › Look › Colour note, which
had been branching on the grant alone and so kept printing an adb line at somebody reading it on a
colour screen. A unit test pins each state, the report's included. Lift and restore are untouched:
they already stepped aside for a phone that was in colour when Roll arrived, and still do.

Fixes [light-reports#54] — Roll asked for an adb grant on a phone already in colour.
