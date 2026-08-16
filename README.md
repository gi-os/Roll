# Roll

**A replacement camera and gallery for the Light Phone III.**
Filters, film-roll mode, QR scanning, hardware face detection, and the wheel as a lens ring.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/Roll.png" alt="Scan to open Roll in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open Roll there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[gi-os.github.io/brightmarket-index/browse.html](https://gi-os.github.io/brightmarket-index/browse.html)**.

[**⬇ Download the latest APK**](https://github.com/gi-os/LightCamera/releases/latest) · free,
open source, no account. The app opens a network connection only when you send a bug report.

<table>
<tr>
<td width="33%"><img src="docs/screenshots/01-viewfinder.jpg" alt="The viewfinder, with the control band written sideways down the left edge"></td>
<td width="33%"><img src="docs/screenshots/02-mode-picker.jpg" alt="The mode picker: PRO, VIDEO, SELFIE, QR, FILTERS, settings"></td>
<td width="33%"><img src="docs/screenshots/03-roll.jpg" alt="The roll: a contact sheet of photos above the viewfinder"></td>
</tr>
<tr>
<td><img src="docs/screenshots/04-bulge.jpg" alt="The Bulge filter, with a date back stamped in the corner"></td>
<td><img src="docs/screenshots/05-dither.jpg" alt="A dithered filter at full resolution"></td>
<td><img src="docs/screenshots/06-film.jpg" alt="The Film filter with a Dots date back"></td>
</tr>
</table>

<sup>Shot on an LPIII. The panel is grey in normal use. Roll lifts the grey while you look
through it, which is why these are in colour. See
[The viewfinder is in colour](#the-viewfinder-is-in-colour).</sup>

The roll sits **above** the viewfinder. Pull down on the camera and your photographs come into
view. Every photo on the phone is there, newest first, against the top-right corner of the frame.
Older ones run up and leftward behind it, the way a contact sheet reads. Flick up from anywhere
and you are back at the shutter. That is the whole navigation model, and the rest of the app is
built around it.

Roll replaces both the stock Camera and the stock Album. You can set it as the phone's default
camera, so the hardware camera button opens it. It is not a fork of the stock app. It is a full
rewrite, and it has shipped more releases than any other app in this collection.

**Light did not make this app.** It is a community app. The Light Phone does not endorse it or
support it. It installs as an ordinary APK. It needs no root and no unlocked bootloader.
Uninstall it and the stock camera is exactly as it was.

### What you get over the stock camera

| | |
|---|---|
| **A shutter that fires** | The stock one-to-three-second delay is the sensor reading out at 50MP. Roll caps capture at 12MP, so the photograph happens when you press the button. Select 50MP if you want it. |
| **18 live filters** | Real fragment shaders, on the viewfinder *and* on the saved file. Film, Dither BW, Dither 16, Dither 32, Halftone, Game Boy, Thermal, Purikura, and the Photo Booth distortions. Turn the wheel to change filter. |
| **The wheel does things** | A bare turn steps filters. Held and turned, it sets exposure. A click gives you the torch. It also scrolls the roll. No service, no permission. |
| **A two-stage shutter** | The half press locks focus. The full press shoots. LightOS itself uses only the second detent. |
| **Face detection** | From the camera's own hardware detector, not a bundled model. Focus follows the face the lens works on. |
| **A gallery worth using** | Every photo on the phone, day headings, multi-select, and trash. The send button picks a person, not an app. |
| **Film-roll mode** | Load 12, 24 or 36 frames. No preview and no review, just a counter and a click, until you develop the roll. |
| **QR scanning** | A camera mode rather than a separate app. Nothing opens by itself. |
| **Date backs** | Three of them, drawn the way the originals worked: an LED dot matrix, a seven-segment quartz back, and a camcorder character generator. |
| **Colour while you shoot** | The panel is a full-colour AMOLED. Light's grey is a setting, and Roll lifts it while the viewfinder is up, then puts it straight back. |

## Install it

You need a computer with `adb` on it and a USB-C cable. You do not need root, an unlocked
bootloader, or a factory reset.

**1. Turn on USB debugging on the phone.**

1. Open Settings → About.
2. Tap **Build number** seven times.
3. Open Settings → Developer options.
4. Turn on **USB debugging**.
5. Connect the phone to the computer.
6. Accept the "Allow USB debugging?" prompt on the phone.

**2. Get `adb`.**

`adb` comes in Android's
[platform-tools](https://developer.android.com/tools/releases/platform-tools). Download the zip
file, unzip it, and run `adb` from that folder. On a Mac, run
`brew install android-platform-tools` instead.

**3. Install the APK.**

1. Download `LightCamera-v<version>.apk` from
   [Releases](https://github.com/gi-os/LightCamera/releases/latest).
2. Install it:

```sh
adb install -r LightCamera-v2.38.55.apk
```

The app appears in the launcher as **Roll**. On first use, grant it access to the camera and to
your photos. Grant contacts access as well if you want the send picker.

**4. Optional. Turn on colour in the viewfinder.**

```sh
adb shell pm grant com.gios.lightcamera android.permission.WRITE_SECURE_SETTINGS
```

Half the filters are about colour. Without this grant the viewfinder stays grey while the file
does not. Nothing breaks if you skip this step. For the reason, see
[The viewfinder is in colour](#the-viewfinder-is-in-colour).

**5. Optional. Make the hardware camera button open Roll.**

Press the camera button after you install. A chooser appears with an **always** option. Select
Roll there. If the stock camera already holds the default, clear it first in **Settings → Apps →
Camera → Open by default → Clear defaults**. Android has no adb command for the default camera,
only for the default launcher. With
[LightControl](https://github.com/gi-os/LightControl) installed you can skip all of this and bind
the camera button straight to `com.gios.lightcamera`.

### Updating

CI signs every build with the same committed key. The fingerprint is in
`signing-fingerprint.txt`, and CI checks it. A later release therefore installs over an earlier
one with `adb install -r`, and it keeps your settings. You can also point
[Obtainium](https://github.com/ImranR98/Obtainium) at this repo. It then offers you each new
release, which on most days means a new one.

### Build it yourself

```sh
git clone https://github.com/gi-os/LightCamera.git
cd LightCamera
./gradlew :app:assembleRelease
```

You need JDK 17. `minSdk` is 33 because every filter is an
[AGSL](https://developer.android.com/develop/ui/views/graphics/agsl) fragment shader, and AGSL is
API 33.

**Current version:** `versionName` in `app/build.gradle.kts` is `2.51.0`. CI adds the run number
as the patch, so the release from the current `main` is `v2.51.x`. See
[Version history](#version-history) for the full run from `v1.0.1`.

## Controls

The camera button has two detents and reports them as two separate keys. `FOCUS` arrives at the
half press and `CAMERA` at the bottom. Nothing in stock LightOS uses the first one.

| Control | Does |
|---|---|
| Camera button, half press | Focuses and locks on the nearest face, or on the centre |
| Camera button, pressed through | Shutter. In QR mode, opens the code on screen |
| Either volume key | Shutter, as a fallback |
| Turn the wheel | The next filter |
| Hold the wheel in and turn | Exposure compensation, in thirds of a stop |
| Click the wheel | Torch |
| Tap the frame | Focus there |
| Double tap the frame | Switch lens |
| Pinch the frame | Zoom. Two fingers claim the gesture, so it cannot read as a filter swipe |
| Swipe the frame sideways | Next filter |
| Swipe down | The roll |
| Long-press a frame in the roll | Multi-select, to send or delete several at once |

There is **no shutter button on screen**, and that is deliberate. The phone has one on its side.
A circle on the glass costs image area and teaches the wrong gesture.

If the camera button does nothing, an accessibility service is taking the key. The likely cause
is an old [LightControl](https://github.com/gi-os/LightControl), which used to keep that key for
itself. From v1.1.6 it hands both stages to whatever camera is in front, so update it.

The two keys arrive in an unpredictable order. The release is therefore a state machine
(`hw/ShutterRelease.kt`) rather than a pair of key handlers. The tests cover the cases that
matter.

Faces come from the **camera's own hardware detector**, not from a bundled ML model. Roll reads
them out of each capture result over Camera2 interop. Every face gets a box, and the face the
lens works on gets the focus mark.

## The wheel needs nothing else installed

The wheel owns zoom, exposure, the torch, and scrolling. The roll and the settings page both
move under it. Since v1.6 a bare turn steps through the filters instead of zooming. The LPIII has
no optical zoom and the stock camera offers none, so a dial spent on digital crop was a dial
spent on nothing. None sits three notches wide on the track, so a stray turn lands somewhere
harmless.

None of this needs a service, a permission or root. Light patched
`/system/usr/keylayout/Generic.kl`, so a notch arrives as an ordinary key event, delivered to
whichever app has focus. Roll reads those keys itself. Install the APK and the wheel is a lens
ring.

[LightControl](https://github.com/gi-os/LightControl) is optional. What it adds is the rest of
the wheel, everywhere else on the phone. Hold the wheel in and turn for brightness. Tap it for
the flashlight. Press the camera button to open a camera. You can rebind each of those, tap and
hold separately, to any installed app. It also gives brightness, or a synthetic-swipe scroll, to
apps that carry no wheel code of their own.

Installing it does not take scrolling away here. LightControl passes bare turns through to
`com.gios.*`, `com.lightfastread` and `com.lightrss.reader` on purpose.

The camera button is the part worth stating plainly, because a global key service is exactly what
would eat a two-stage release. LightControl hands that button to whichever camera is in front,
meaning anything registered for `STILL_IMAGE_CAMERA`, rather than to a package it remembers. Roll
therefore keeps its own half press and its own shutter, and LightControl never sees either. That
is a deliberate carve-out, not luck.

```bash
# Optional: LightControl, for brightness, the flashlight and the camera button
adb install -r LightControl-v1.0.x.apk

# The key service. NOTE: this setting is a list, and this command REPLACES it.
# If you also run LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# Brightness, and the level readout + opening apps from the service
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

The latest build is at <https://github.com/gi-os/LightControl/releases/latest>.

## The focus marks are LightOS's own

The stock camera ships two drawables. `ic_camera_focus_locking` is four corner brackets, and
`ic_camera_focus_locked` is a closed square. That is its entire focus vocabulary, so it is this
app's vocabulary too: **brackets while the lens hunts, closing into a box the moment it locks**.
Roll tweens that over the 90 ms the lens actually takes.

`AF-S` and `AF-C` sit in the corner of the image. The badge inverts the moment focus locks, so
white on black becomes black on white. Autofocus is then visibly on rather than something you
infer.

The app also beeps. Two short blips and a buzz mean the lens landed. One lower note means it gave
up. Roll synthesises the PCM rather than shipping audio files, plays it on the sonification
stream, and stays silent when the phone is silent. The beep fires off the camera's own
`CONTROL_AF_STATE`, so it means the lens has focus, not that Roll sent a request.

## Sideways chrome, upright picture

The stock camera makes a split that a screengrab shows and a photograph does not. The control
band is **written sideways** down the left edge, with `PHOTO ⌄` reading down it. The viewfinder
image stays **upright in the phone's own frame**.

That is the right division. The band is sideways because you hold the phone like a camera to
shoot. Turn it anticlockwise and the band runs along the bottom, with the camera key up top where
a shutter release belongs. The image stays upright because it is the image.

So Roll rotates only the strips of chrome (`HeldSideways` in `ui/Common.kt`). Every other
screen is an ordinary portrait screen: the roll, the viewer, the settings. An earlier version
rotated the whole app. The picture spun with it, and the swipe down to the roll became a sideways
swipe.

The band is the stock four items in the stock order and spacing: **album, the mode slot, flash,
brightness**. All of it is measured off photographs of the real thing. Nothing floats over the
picture. The strips take their own width out of the left-hand side, which is why this app has no
gradients anywhere.

Since v1.9 every readout on the band sits two steps higher on the type scale. The 8-design-pixel
`Micro` variant came out at about six points on this panel, too small to read at arm's length.
Nothing uses it any more.

### The mode slot

The slot reads `PHOTO ⌄`, `VIDEO ⌄` or `SELFIE ⌄`, and the chevron opens the picker, as it does
on the stock camera.

- **Selfie** is the front lens and nothing else, which is what it is on the stock camera too. A
  double tap on the image switches lens as well.
- **Video** records HD into `DCIM/Camera` through CameraX's `VideoCapture`. Roll binds it
  *instead of* `ImageCapture` rather than alongside it, because only `LEVEL_3` hardware
  guarantees all three use cases at once. Audio arrives when the permission does, and Roll asks
  on entering the mode rather than at the moment you press record. Filters stay off in video: a
  `RenderEffect` belongs to the view and never reaches the recorded stream, so a filtered preview
  would promise something the file cannot deliver.
- Filters and settings sit on the end of the same strip, so the band stays at four items.

Roll hides the system bars, so the picture starts at the panel's edge. The image itself carries
only three marks: the focus mark, the `AF-S` or `AF-C` badge (a record dot and a timer while
filming), and a horizon line while the phone is crooked. Roll measures that line off the
**nearest quarter turn**, so it is square whether you hold the phone upright or sideways.

## The viewfinder is in colour

The panel is a **full-colour AMOLED**. Light's black and white is Android's accessibility
daltonizer pinned to monochromacy, which is a secure setting and a SurfaceFlinger colour matrix.
It therefore lifts at once and needs no restart. LightOS does the same thing itself for photos
and video.

Roll lifts the grey while the camera or a photograph is on screen. It puts the grey back the
moment the app is no longer in front, because the rest of the phone is your setting and not the
camera's. Half the filters here are about colour. Thermal is a false-colour ramp and Dither 16 is
a sixteen-colour palette, so a grey viewfinder misrepresented the photograph.

One adb grant, once:

```sh
adb shell pm grant com.gios.lightcamera android.permission.WRITE_SECURE_SETTINGS
```

The permission is `signature|privileged|development`. That is why adb can give it and the
installer cannot. Without the grant, Android refuses the write, nothing breaks, and everything
stays grey. Settings → Colour selects the viewfinder only, the whole app, or off.

## Filters are AGSL, and the photo matches the frame

Eighteen filters, each one a fragment shader. Roll runs the same shader source twice: as a
`RenderEffect` on the live preview, and over a `BitmapShader` when it writes the photograph.
There is therefore one definition of what Halftone looks like, and the file you get is the frame
you saw.

- **Film** shows grain modulated by the midtones, halation on the highlights, and a vignette. The
  grain moves.
- **Dither 16** is the EGA palette, ordered-dithered with a Bayer matrix. Very dithered, very
  sixteen colours.
- **Dither BW** is sixteen ordered-dithered greys, and it is the phone's own idea of a
  photograph. **Dither 32** adds a thirty-two colour palette that spends its extra entries on
  greys and shadow.
- **Halftone** is a rotated dot screen, one read per cell, so the dots stay round.
- **Game Boy** and **GB Color** put the DMG and GBC palettes through a Bayer threshold. The other
  half of that look is the grid: both quantise the image onto 128 cells across the short edge,
  which is the Game Boy Camera's real sensor width, and sample once per cell.
- **Purikura** is the Japanese photo booth, as a mode rather than a filter. It gives you a frame,
  two kinds of sticker, a date, skin and eye work, and a four-shot strip on a countdown. Its menu
  is in the viewfinder, under **PURI**. Roll rolls every setting at random when the app starts.
- **Mono, Comic, Thermal, X-Ray** and **Glow**, plus the Photo Booth distortions: **Twirl,
  Bulge, Mirror, Kaleido** and **Tunnel**.

Roll sizes patterns in design pixels rather than device pixels. The dither in a photograph
therefore looks like the dither in the preview, instead of dissolving into noise.

The mode strip's **Filters** entry opens the grid: every filter running live on what the camera
points at, all at once, the way Photo Booth used to do it. Sideways swipes on the image step
through them too, and so does the wheel. Since v1.6 the wheel steps filters by default
rather than zooming.

On the wheel's track, **None is three notches wide**. The most common setting is therefore the
easy one to land on, and a deliberate three notches to leave. Every notch buzzes, so the dial
never reads as dead. Landing on None also catches for 1.5 seconds. Roll feels the notches inside
that window and discards them, so a fast spin cannot skate past None.

### Applying a shader to a still is not three lines

`RuntimeShader` compiles for the GPU and cannot draw onto a software `Canvas`. Paint it over a
bitmap and you silently get nothing. So `filter/ShaderRuntime.kt` drives a `HardwareRenderer`
into an `ImageReader` and reads the result back through a `HardwareBuffer`. That is the supported
way to get a hardware-accelerated draw with no view on screen. The alternative was a second, CPU
implementation of every filter, which would have drifted from the shader within a week.

Because it is the GPU, it can decline. A driver has a maximum texture size, a compile can fail,
and a full-resolution still is a much larger ask than a preview. The still path therefore fails
**soft**. `ShaderRuntime.applyToBitmap` hands back the bitmap it was given rather than throwing.
`Frames.process` catches everything, out-of-memory included, and writes the sensor's own frame. A
filter that cannot run costs you the filter. It must never cost you the photograph.

## QR is a mode, not an app

Select **QR** from the mode slot and the viewfinder starts decoding. Point it at a code and the
payload appears on screen. The camera button, or OPEN, then does the obvious thing with it.

**Nothing opens by itself, and that is the whole design.** Every other scanner opens the link the
moment it reads one. On a phone whose camera is also its default camera, that is a trap. You point
it at a table to photograph the food, it reads the code on the menu, and a browser covers the
picture you were about to take. So a scan holds. Roll sets the host large, puts the raw payload
under it, and leaves the decision to you. That is also the only defence anyone has against a
sticker pasted over the QR code on a parking meter.

Roll hands only seven schemes to the system:

```
http  https  tel  mailto  sms  smsto  geo
```

Anything else is text you can copy and nothing more, and that includes `intent:` URIs. A QR code
is a string a stranger printed on a wall. Calling `startActivity` on an arbitrary scheme is how a
poster gets to reach whatever handlers happen to be installed. The list lives in
`Codes.openable`, which has no Android imports. The tests cover it.

Roll takes Wi-Fi codes apart rather than showing them raw. `WIFI:S:…;T:WPA;P:…;;` arrives as a
network name, a password large enough to read off the screen while you type it into a laptop, and
a COPY PASSWORD row. The password is the only part of that payload anybody wants. The parser
honours the format's backslash escapes, which a `split(';')` does not. A password containing a
semicolon is exactly the kind of password people use.

The result sheet **turns with the phone**, and it is the only thing in the viewfinder that does.
Roll pins everything else sideways on purpose, because you turn the phone anticlockwise to shoot
and the chrome comes with it. A scan result is not chrome. It is a paragraph you stopped to read,
in whatever pose you held when you pointed the camera at the code. So it is portrait when you are
portrait and sideways when you are sideways, with enough hysteresis to stop it flipping halfway
through a URL.

The mode is back-lens only, with no filters, no grid and no film counter. The filter rule is
worth stating. The decoder reads the camera's own frames, which a `RenderEffect` never touches. A
Game Boy viewfinder would therefore carry on scanning perfectly while showing you something
unreadable, which is a viewfinder that lies about why it failed. The flash slot becomes the
torch, because a flash mode is a property of a capture and this mode takes none.

Decoding uses [ZXing](https://github.com/zxing/zxing), pure Java, inside the APK. ML Kit is the
obvious choice on any other Android phone and is unavailable here. Play Services delivers its
barcode model, LightOS does not ship Play Services, and the app would install, bind, and never
return a result.

Under the sheet, Roll binds an `ImageAnalysis` at 1280×720 in place of the shutter, and binds it
**only** in this mode. An analysis stream is a second full-rate consumer of the ISP, so leaving
one attached in Photo would cost every shot for a feature nobody switched on. Roll drops frames
rather than queueing them when a decode runs long. Queued frames would stutter the preview
whenever you pointed the camera at something busy.

## Film-roll mode

Load a roll of 12, 24 or 36. Photographs go into app-private storage instead of the gallery.
There is no preview and no review. The only feedback is a counter and a click.

The roll develops when it finishes, or when you decide. Roll then writes every frame into
`DCIM/Camera` at once, each frame keeping the time you actually took it, and shows you a contact
sheet of photographs you have not seen yet.

The point is not nostalgia. Checking the screen after every shot changes what you photograph.

A loaded roll shows itself in the black band under the picture. There is a strip of sprocket
holes that steps along with each frame, and a counter beside it. The shutter release also turns
from a circle into a square, so one glance tells you the photograph is going onto film.

## The frame

4:3, 3:2, 16:9 or 1:1, applied as a centre crop when Roll writes the photograph. The viewfinder
does **not** letterbox itself to match. It fills the screen, so the file keeps a little more than
you saw at the top and bottom of the frame. An earlier version drew the exact save aspect as a
bordered box with the controls in the margins. It was honest about cropping and horrible to look
through.

## Date backs

Three of them, off by default, in Settings. Roll draws them three different ways because they
were three different machines.

- **Dots** is a compact camera's LED matrix. It is a 5x7 bitmask, and every lit cell is a circle
  at 0.42 of the cell, so the hairline gaps show. Each row leans, so the digits staircase.
  Amber-green, space-padded: `11  5 '21`.
- **Quartz** is a film SLR's back. Seven segments as sheared parallelograms, orange-red, year
  first and zero-padded: `'99 12 29`.
- **Camcorder** is a character generator, so it is the one style where a real typeface is
  correct: upright bold `08/31/2015` with a black stroke keyline.

Roll sizes the cells and the text as a fraction of the image rather than in pixels, so the stamp
looks the same at 2MP and at 50MP. A stamp costs one decode and one re-encode on a photograph
that Roll would otherwise save byte-for-byte off the sensor.

## Sending a photo

The send button asks **who the photograph is for**, not which app it should go through. A share
sheet lists every app that ever registered for an image, and that is the one place a Light Phone
stops feeling like a Light Phone. On a phone with three messaging apps installed, "which app" is
a question with an obvious answer wrapped in a grid of icons.

So the button opens the address book instead. Recent recipients come first, then your contacts,
and you can search by name or number. Select someone and Roll hands the photograph to a
messaging app already addressed to them. Long-press a frame in the roll to select several and
send them together.

With [LightChat](https://github.com/gi-os/LightChat) v1.2 or newer installed, your group threads
sit above the contacts, with their names resolved the same way they read over there. An address
book knows about people, and a group iMessage is a chat room rather than a person, so Roll has to
offer it deliberately. Groups go to LightChat alone. A single person can fall through to any
messaging app that understands the standard extra.

## Setting it as the default camera

The app claims `STILL_IMAGE_CAMERA`, `IMAGE_CAPTURE`, `CAMERA_BUTTON` and the `_SECURE` variants.
It also honours `EXTRA_OUTPUT`, so another app's "take a photo" works properly. See
[Install it](#install-it) above for how to set the default.

Launched for `IMAGE_CAPTURE`, the app shows only the viewfinder. There is no roll and no
settings. It takes one photograph, writes it where the caller asked, and finishes.

## Configuration

Everything is in the in-app Settings screen. There is no config file and nothing to edit by hand.

| Setting | Options | Notes |
|---|---|---|
| Colour | Viewfinder only / whole app / off | Needs the `WRITE_SECURE_SETTINGS` grant above. Without it, everything stays grey. |
| Frame | 4:3, 3:2, 16:9, 1:1 | Applied as a centre crop at save time. The live viewfinder always fills the screen. |
| Size | 50MP / 12MP / 5MP / 2MP / Screen | 12MP by default, because the sensor's native 50MP readout *is* the shutter lag. **Screen** never calls the shutter at all. It keeps the frame you were looking at, instantly, filter included. |
| Date | Off / Dots / Quartz / Camcorder | Also selects whether the stamp goes on plain photos, on filtered ones, or on the coarse filters where it would be unreadable. |
| Film | Off / 12 / 24 / 36 frames | Switches the shutter release from a circle to a square. Develops on completion or on demand. |
| Wheel | Filters / EV | What a bare turn does. |
| Focus | Single / Continuous, faces on or off, priority | Also holds the focus beep. |
| Grid | Off / Thirds | |
| Self timer | Off / seconds | |
| Sounds | On / off | Follows the ringer either way. A silent phone is a silent camera. |
| Sending | Recents, and clearing them | The send picker's six-slot recent-recipient list. |
| Simple mode | On / off | Hides the parts of the viewfinder you do not use. |
| Purikura | — | Has its own menu in the viewfinder. Select Purikura on the wheel, then tap **PURI** in the band. Frame, stickers, date and the four-shot strip sit next to the picture they change. Roll rolls every choice at random when the app starts, because a booth does not remember what you picked last week. |
| About | Last crash | The first lines of the last uncaught exception, worth pasting into an issue. |

## Layout

```
camera/     CameraX, hardware face detection, AF, capture, EXIF, cropping, date backs
filter/     the AGSL sources and the two ways Roll runs them
hw/         the wheel, the two-stage camera button, the synthesised beeps
media/      MediaStore reads and writes, thumbnails
qr/         QR mode: the ZXing analyser, and the payload rules it applies
report/     the shake gesture and the issue it files
roll/       film-roll mode
send/       the address book, the group provider, and the addressed intents
ui/         the two pages, the viewfinder chrome, the filter grid
```

## If something goes wrong

**Shake the phone twice.** A `SEND ERROR?` chip appears in the corner for four seconds. Tap it,
write a line about what happened, and Roll files a report against the tracker.

The report carries the symptom, your note, the build and the firmware, free space, and the last
crash log if there is one. It also carries a screenshot from the moment you shook the phone, and
you can untick that row. Ignore the chip and it fades, and deletes nothing. This is the fastest
way to get something fixed, because it carries details a description never does.

You can also [open an issue](https://github.com/gi-os/LightCamera/issues). Settings shows the
first lines of the last crash, which is worth pasting in. A sideloaded app on the LPIII is
otherwise a black box.

## Questions people ask

**Does it need root, or an unlocked bootloader?** No. It is an ordinary APK with an ordinary key.
You install it over adb and uninstall it the way you uninstall any app.

**Will it break my phone, or a LightOS update?** No. It does not touch the system partition. It
changes exactly one system setting, the accessibility daltonizer that makes Light's grey grey,
and only while you look through the viewfinder. It restores the setting when you leave. If the
app dies with the viewfinder open, the phone can stay in colour. Open and close Roll again to
restore it, or reboot. A LightOS update may remove sideloaded apps. Reinstall the APK if it does.

**Does it phone home?** No. The whole app opens one socket, in `report/Reports.kt`, when you tap
SEND on a bug report you wrote yourself. Never on launch, never in the background, never on a
timer. There are no analytics. Your photographs stay on the phone.

**Does it replace the stock camera and album?** It can be the default camera, so the hardware
button opens it. The stock apps stay installed and untouched. There is nothing to migrate,
because Roll reads and writes the same MediaStore your photos are already in.

**Where do the photos go?** Into `DCIM/Camera`, as normal JPEGs with normal EXIF, so anything
else on the phone or on your computer reads them. Film-roll frames sit in app-private storage
until you develop them, and then land in `DCIM/Camera` too, each keeping the time you took it.

**Is the wheel really the filter dial and not zoom?** Yes, since v1.6. The LPIII has no optical
zoom and the stock camera offers none, so a dial spent on digital crop was a dial spent on
nothing. Pinch with two fingers if you want to zoom.

**Do I need LightControl?** No. The wheel, the camera button and the volume keys all work in Roll
with nothing else installed. LightControl adds the wheel *everywhere else on the phone*, and it
deliberately hands the camera button to whichever camera is in front, so the two do not fight. If
the camera button does nothing in Roll, you are on a LightControl older than v1.1.6. Update it.

**What does it cost in battery?** The expensive parts are the ones you look at. A live shader
costs GPU while the viewfinder is open, and QR mode binds a second analysis stream, which is why
Roll binds it only in that mode. Nothing runs when the app is closed.

**Does it work on the Light Phone II?** No. It is a full-colour AMOLED, CameraX and AGSL app for
the LPIII.

## Contributing

- New filters go in `filter/`. Write the AGSL source once, and make it run through both call
  sites in `filter/ShaderRuntime.kt`: the live `RenderEffect` and the still-capture
  `BitmapShader`. A filter that works only on the preview is a bug, not a feature.
- Camera-button and wheel changes touch `hw/ShutterRelease.kt` and `hw/Wheel.kt`. Tests cover
  both key arrival order and rapid notches, because both have shipped broken before. See
  [Version history](#version-history). Extend those tests rather than testing on a device alone.
- CI (`.github/workflows/build.yml`) gates every push to `main` on four checks: a signed build, a
  certificate fingerprint against `signing-fingerprint.txt`, a launcher icon, and the camera
  intent filters. A push that fails any of the four never reaches Releases. `check.yml` runs the
  same build without the release steps on any other branch, so open a branch first if you want CI
  feedback before a push cuts a release.
- Raise `versionName`'s `major.minor` in `app/build.gradle.kts` when a release deserves it. CI
  stamps the run number on as the patch and tags `vMAJOR.MINOR.RUN` on every push to `main`.
  There is no separate tagging step.

## Version history

Each row below is the release note as it shipped at that tag. `RELEASE_NOTES.md` holds the full
entry for the current release, so read that file at any tag for the whole reasoning behind a
change.

| Version | Date | Notes |
|---|---|---|
| `v2.51.x` | this commit | **Datamosh tears the photograph instead of recolouring it.** It had been coming back as three or four flat coloured bars over an otherwise intact picture. Two constants were never scaled to the file: the port came from an ESP32 working on a thumbnail, where two to four transplants of up to 4096 bytes is most of the frame, and on a 12MP scan of several megabytes the same numbers are four tears of under a thousandth of the file each. A tear that short desynchronises the decoder for a few blocks and then it re-syncs, so the smear never starts — what survives is the DC difference chain inheriting one error per tear, and since that chain runs in raster order every block below a tear takes the same wrong average colour. Four tears, four bands. Intensity could not help, because it drove only the quantisation tables, which flatten detail and made the bands look painted on. The run is now a fraction of the scan and the count rises with intensity, so the tears overlap and the block displacement is visible across the frame. Verified off-device against a 12MP frame at every intensity `bend` produces, with forty seeds at maximum still inventing no end-of-image marker; a test pins that the damage scales with the file rather than sitting at a constant. |
| `v2.50.x` | 144358f | **The dial lock is a setting, and it is off.** v2.49 could leave the filter dial locked with nothing on the phone able to open it. The lock's only escape was a click on the wheel, and the check that was meant to prevent a trap asked whether something was *bound* to the lock rather than whether a wheel click actually reaches the app — on a phone running LightControl, which binds the wheel system-wide, it may not. Turns arrived so the dial reported itself locked; clicks did not, so nothing could unlock it, and Settings is reached through the mode picker, which is reached with the wheel. The master switch is now Settings › Keys › Dial lock, off by default, and a row you tap rather than anything that depends on the wheel; turning it off wakes the dial immediately. With it on the behaviour is unchanged from v2.49 — asleep at every launch, a click wakes it, a second click puts it back, press-and-turn and an open strip still exempt — and the notice now names the settings row as well as the click. The torch is back on the wheel click: the lock claims the click while it is on rather than owning the binding, the same way a playing clip borrows the volume keys, so switching it off hands the click back on the next press. The lock is no longer offered in the key picker, which stops it being put on the last shutter or taken off the click. |
| `v2.49.x` | 5c5ec3f | **The filter dial boots locked, and a click on the wheel is what opens it.** The wheel is the phone's rather than Roll's -- shared with everything else on it, and it turns in a pocket -- so a camera being carried around was changing which filter the next photograph would be taken through. The dial now starts locked at every launch; a click unlocks it, a second click locks it again, and a bare turn while it is locked moves nothing and puts a line on the panel naming the control that would open it. The state is not remembered, which is the point of it: recalling that you unlocked it yesterday would hand the pocket the dial back the moment you picked the phone up. Press-and-turn and an open strip both ignore the lock, because neither gesture can be made by accident -- exposure is exactly where it was. The click was the torch until now, which is a changed default worth knowing rather than discovering: the torch will take either volume key. The lock is a binding like any other, so it can move to a volume key, and pointing nothing at it switches the lock off entirely -- the dial boots locked, so a mapping with no way to unlock would be a wheel that never turned anything again, and rather than refuse that mapping the feature stands down. Binding the lock over the last shutter is still refused. |
| `v2.48.x` | c8c3459 | **Datamosh leaves a photograph behind, and the date stamp reads on black and white.** Datamosh had returned coloured static since v2.46 swapped the shader for a real databend, and two of the five ported operations were the reason — both damaged the whole frame uniformly rather than tearing part of it. `rotateHuffman` rotated AC symbols within a magnitude group, which keeps every code length valid so the file still decodes, but a symbol's low nibble is the coefficient's size and its high nibble is the run of zeroes before it, so rotating within a size group rewrote the run and landed every coefficient in the image at a different frequency — a global reshuffle rather than a tear, and already past saving at the lightest intensity the app can produce. `amplifyChroma` wrote 162-180 into the chroma table's DC slot where a real table holds 17-99, which snaps each block's average colour to nonsense and gave the flat acid green. Both are gone; `transplantScan` was always the actual mosh and still is, so the tears drag sideways over a photograph you can recognise. Tests now pin that the Huffman tables come out byte-identical and that every quantisation table keeps its DC term. Datamosh has also **moved on the dial**: it was last in the list, and because stepping wraps, last is one notch backwards from Preset — overshooting the plain photograph by a click selected it by accident. It now sits eleventh of twenty-one, as far from Preset as the dial allows, with the wrap untouched. Separately, the **date stamp goes neutral on black-and-white frames**: it is drawn after the filter on purpose, so it never sees what it lands on, and Mono, Dither BW, 1-Bit and Halftone were getting a full-colour amber date. Those four now declare themselves, and the mono palette is not the amber desaturated — with no hue left, contrast is the only channel, and a 1-Bit frame is nothing but white and black, so the bloom inverts to near-black under near-white lamps and every dot carries a dark keyline. Game Boy and X-Ray are deliberately excluded: they have a colour of their own. |
| `v2.47.x` | 2026-08-16 | **A clip in the viewer shows its first frame, and its volume can be changed while it plays.** The viewer decodes frames with `BitmapFactory`, which reads image files and nothing else, so a video came back null and drew as a black rectangle with a play triangle on it — a clip looked like a frame that had failed to load. Poster frames now come from `loadThumbnail`, the same call the roll grid already makes, which is usually cached and applies the clip's rotation so the still and the video that follows it are the same way up; a clip MediaStore has no thumbnail for falls back to decoding the opening keyframe. Separately, both volume keys are a shutter by default and Roll takes them in `dispatchKeyEvent` before anything else can, so a playing clip's volume could not be moved at all. While one plays they report as unbound, which already meant "hand the key back to the phone" — no new control, and the flag is cleared on playback ending, on swiping away and on closing the viewer, because leaving it set would take the fallback shutter with it. |
| `v2.38.x` | 2026-08-04 | **You can send a photo to a group chat.** Group threads now sit at the top of the send picker, above your contacts — five at rest, all of them once you type a name — and a photo sent to one lands in that thread. Needs [LightChat](https://github.com/gi-os/LightChat) v1.2, which is where both halves of this live: it publishes your group threads for other apps to read (name, member count, last activity, names resolved through the BlueBubbles address book), and it is the only app Roll will hand a group photo to. It needed a release because a person and a group are not the same kind of destination: a contact has a phone number, which goes into a standard Android intent any messaging app understands, while a group is a thread on your Mac identified by a string like `iMessage;+;chat684…` with no contact row and no number — so there was nothing to select and nothing in the intent to carry. A group now travels in a `chat_guid` extra to LightChat and **nowhere else**, with no chooser fallback, because any other receiver would ignore the extra and drop the photos into whatever thread it had open. Sending to a person is unchanged. Two small consequences: typing a digit hides the groups (digits search phone numbers, and a group has none), and groups aren't recorded as recents (they already sort by last activity, and each one in recents would push a contact out). Without LightChat, or on an older build, there are no groups and the picker is exactly what it was. |
| `v2.37.x` | 480e30d | **The camera opens plain.** The filter was persisted and seeded from Film, so whatever you last chose was still on when you next reached for the shutter — and a filter is a decision about one photograph, not a setting. Shoot a roll through Game Boy on Tuesday and on Thursday you get a 160-cell dither of something worth photographing, with no undo; the reverse mistake costs one turn of the wheel. Roll opens on Pro with no filter now, for the same reason it already opened on Pro rather than in Video. **"Opens" is the process starting, not every glance at the app** — the dial holds for as long as Roll is alive, so pulling down to the roll, opening the shot you just took and coming back changes nothing, because resetting on resume would take the filter away at the moment you are most likely to want another frame of the same thing. Also: **the QR result reads the way you are holding the phone.** The scan sheet was wrapped in `HeldSideways` like every other panel in the viewfinder, which pins content sideways because you turn the phone anticlockwise to shoot — correct for chrome, wrong for this. A scan result is a paragraph you stopped to read, and you read it in whatever pose you were in when you pointed the camera at the code, which for a poster or a menu or a parking meter is upright. It now uses `RotatedToDevice` off the accelerometer: portrait at 0, exactly what it used to be at 90, with the existing 60° of hysteresis stopping it flipping halfway through a URL. Nothing else in the viewfinder changes — the band stays pinned, because the band is chrome. |
| `v2.36.x` | fed3163 | **QR scanning is a camera mode.** Pick QR from the mode slot and the viewfinder starts reading codes; point it at one and the payload appears with OPEN and COPY under it. [LightQR](https://github.com/gi-os/LightQR) folded into the camera, because it was one launcher entry and one cold start away from a viewfinder that is already open. **Nothing opens by itself** — most scanners launch the link the instant they read one, which is wrong on a phone whose camera is also its default camera, so the destination is legible before anything is launched. Only seven schemes are ever handed on (`http`, `https`, `tel`, `mailto`, `sms`, `smsto`, `geo`); anything else, including `intent:` URIs, is text you can copy. Wi-Fi codes are parsed into a network name and a password with its own COPY row, honouring the format's backslash escapes — a `split(';')` cuts a password containing a semicolon in half. Two bugs came over from LightQR and are fixed: the analyser ignored the luminance plane's `rowStride`, so at any padded resolution the decoder saw a sheared image and never found a code; and `Patterns.WEB_URL` was deciding what a link was, which takes `1.2` and `v2.0` as web addresses. The camera button is the accept key rather than a scan trigger, the flash slot becomes the torch, and the mode is back-lens only with no filters, no grid and no film counter. `ImageAnalysis` at 1280×720 in place of the shutter, bound only in this mode; ZXing rather than ML Kit, which needs Play Services this phone does not have. |
| `v2.35.x` | ff584c3 | **Shake the phone twice and a SEND ERROR? chip appears in the corner; tap it and Roll files a GitHub issue against the private tracker.** The report carries the symptom, an optional note, the build and firmware, free space and heap, the last crash log if there is one, and — only while the row stays ticked — a screenshot taken at the moment of the shake. Ported unchanged from [LightNotebook](https://github.com/gi-os/LightNotebook), deliberately: this is diagnostic UI, not product surface, so it should be one learned gesture across every app rather than four. **Roll gains the `INTERNET` permission, which it never had before** — worth noticing rather than discovering, in an app that holds the camera, the microphone, your contacts and your whole photo library. It opens a socket in exactly one place, `report/Reports.kt`, after you tap SEND on a report you wrote yourself; never in the background, on launch, or on a timer. The key it posts with can only file issues on one private repo — it cannot read that repo's contents or touch any other, which is why the screenshot travels as base64 in the issue body rather than as a committed file. The gesture counts *reversals* rather than force (four past 0.46g), which is what separates it from a camera being carried, pointed and set down all day; being wrong is cheap, because the chip fades after four seconds and deletes nothing. |
| `v2.35.x` | 2026-07-31 | **A booth strip holds one orientation, and two fingers zoom.** The way up was read per frame inside the countdown loop, so turning the phone between shots gave four photographs of different shapes — and the sheet is measured from the first of them, with `drawBitmap` stretching the rest into cells built for it. The orientation and the aspect ratio are now read once, before the first frame, and held for the whole sequence; turning the phone mid-countdown changes nothing. `PuriStrip.sourceCrop` is the second line of that defence: a frame that differs anyway is centre-cropped to the cell's shape rather than distorted. Separately, the viewfinder only ever followed the first pointer, so a pinch arrived as one finger wandering sideways and **stepped the filter instead of zooming** — two fingers now claim the gesture outright, scaling from the zoom the pinch started at, and nothing else can fire from it. Double tap to swap lenses was already there and is no longer competing with a half-read pinch. |
| `v2.34.50` | 2026-07-31 | **Filtered selfies come out the right way up, and the shutter can no longer fail in silence.** `Frames` mirrored the frame *before* turning it upright, and those two do not commute — a mirror followed by a quarter turn is the same transform as the quarter turn followed by a vertical flip, so every filtered photograph off the front lens was saved upside down and mirrored the wrong way, while an unfiltered one, written as the sensor's own bytes, never came through that code at all. The flipped EXIF orientations (`TRANSVERSE` and friends) are understood now too, so a HAL that has already declared the frame mirrored is not mirrored a second time. Alongside it, four ways the shutter could produce nothing at all: `takePicture` now has a twelve-second deadline, because a capture whose callback never arrived left `_shooting` latched and every later press was dropped without a word; a capture that misses the deadline saves the viewfinder frame rather than nothing; a filter that the GPU refuses on a full-resolution still now writes the unfiltered photograph instead of throwing out of the coroutine and killing the process; and every shooting routine catches everything and names it on the viewfinder. Also: a 50MP filtered capture no longer decodes 200MB of ARGB before scaling it straight back down, and pressing the shutter with the camera unbound says so instead of looking ordinary. |
| `v2.34.x` (pending) | — | Serves the starred list to the rest of the collection, read-only, over `com.gios.lightcamera.stars`. A star is the one fact about a photograph only this app knows — everything else another app wants is already in MediaStore — and `IS_FAVORITE` is effectively writable only by the system gallery, so it has to be offered deliberately. Names, not ids: an id is a row number that changes on a rescan. [LightNotebook](https://github.com/gi-os/LightNotebook) uses it to pick which photograph goes behind a day on its planner. |
| `v2.33.x` (pending) | — | Another app's `IMAGE_CAPTURE` request is now served **plain**, whatever the filter dial says, unless the caller passes `EXTRA_ALLOW_FILTER`. A filter is something the user chose for their own roll, and silently applying it to a photograph another app asked for breaks that app in a way neither of them can see — [LightNotebook](https://github.com/gi-os/LightNotebook) hands a photographed page to Claude to read, and a dithered page is illegible, so "Roll had Game Boy selected three days ago" surfaced there as "the notebook can't read my handwriting". Opt-in rather than opt-out, so a caller written before this gets the safe behaviour. |
| `v1.9.x` (pending) | — | Drops zero shutter lag (it was the cause of "Shutter failed", not a fix for it — CameraX accepted the mode but refused the first captures while its buffer filled); moves every readout up two type-scale steps off the unreadable `Micro` size; fixes the roll grid so the newest photo lands bottom-right, not bottom-left, matching a real contact sheet. |
| `v1.8.11` | 2026-07-30 | Capture resolution capped at 4000×3000 (down from the sensor's native 50MP) — the single biggest fix for the "one to three seconds" shutter lag every review complains about. Adds `CAPTURE_MODE_ZERO_SHUTTER_LAG` where supported (later found to cause its own failures, dropped in v1.9). Filtered stills now downsample with `inSampleSize` instead of decoding the full 50MP JPEG; JPEG quality 92 not 95. |
| `v1.7.9` | 2026-07-30 | Two Game Boy Camera filters (DMG and GBC palettes, both quantised onto a 128-cell grid matching the real Game Boy Camera sensor). Every wheel notch now buzzes; landing on None catches for 1.5s instead of spanning three notches of track. The viewer's send button, gated on Settings → Sending → Use LightChat. |
| `v1.6.8` | 2026-07-30 | The wheel's bare turn now steps through filters instead of zooming — the LPIII has no optical zoom, so a dial spent on digital crop was a dial spent on nothing. |
| `v1.5.7` | 2026-07-30 | Fixed the v1.5.6 instant-crash-on-launch: a `viewModelScope` collector in an `init` block ran synchronously in the constructor and read a field declared below it. Adds an on-device crash log, shown in Settings. |
| `v1.5.6` | 2026-07-29 | Sideways chrome, upright picture: only the control band is rotated, not the whole app, matching what a screengrab of the stock camera actually shows. Adds the Camera/Video/Selfie mode picker. Shipped with the instant crash fixed in v1.5.7 the same day. |
| `v1.4.5` | 2026-07-29 | The whole app briefly drawn a quarter turn (superseded by v1.5.6's more accurate split); the viewfinder lifts LightOS's forced greyscale while a camera or photo is on screen. |
| `v1.2.4` | 2026-07-29 | The roll scrolls the way the wheel is turned. |
| `v1.2.3` | 2026-07-29 | Camera band laid out like LightOS's own: album / lens / mode / flash / brightness, system bars hidden, no on-screen shutter. |
| `v1.1.2` | 2026-07-29 | Unobstructed viewfinder: the bordered-crop preview from v1.0 is gone, replaced by a full-bleed preview with chrome floating in the system-bar insets. Adds the stock focus brackets and the focus beep. |
| `v1.0.1` | 2026-07-29 | First release. Two-stage shutter release, hardware face detection over Camera2 interop, the roll as a reversed grid above the viewfinder. |

## Credits

The icons and the design tokens come from
[`lightphone/light-sdk`](https://github.com/lightphone/light-sdk), MIT, © The Light Phone. That
covers the 27x31 grid, the type scale and the haptics. See `LICENSE-light-sdk`.
