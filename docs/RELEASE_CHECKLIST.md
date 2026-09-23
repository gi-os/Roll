# Release checklist

**A nightly stays a nightly until this passes on hardware.** Every push to `main` builds a
nightly; an official release is asked for with `[release]` in the commit subject, or by running
the build workflow by hand and picking `official`. Nothing that touches the camera — the engine,
the watchdog, the recorder, anything that binds or unbinds — gets that word until someone has
run the list below on a Light Phone III and every line held. The sandbox cannot run the HAL, and
the faults that matter here have never once been visible from the source: a recording that hands
back a black viewfinder, a rebind that takes cameraserver down, a flash that lights and does not
fire. They happen on the phone or not at all.

Run it on the nightly build, installed over whatever was there before, with the phone as you
carry it: LightControl running, Wi-Fi on, the roll not empty. Do not clear app data first. A
fresh install passes tests a real phone fails.

## Before you start

- [ ] Note the build number from Settings › About and the fault chip count in the corner of the
      viewfinder. Tap the chip if it shows, so the run starts from `!0`.
- [ ] Note the free space. A phone with under a gigabyte free fails the 50MP and RAW lines for a
      reason that is not the camera's.

## The cycles

Ten times, without restarting the app between them:

- [ ] Record a clip of at least five seconds, stop it, and confirm the viewfinder comes back
      live — moving, not a held frame — within two seconds.
- [ ] Take a photograph, straight after the recording, before doing anything else. This is the
      line that finds a camera the recorder handed back dead.
- [ ] Open the roll. The clip and the photograph are both there, in that order, with the clip
      showing a duration.
- [ ] Return to the camera. The viewfinder is live.

If any cycle fails, stop counting and write down which one and what it looked like. Ten passes
after a failure is not a pass.

## One of each

- [ ] A photograph after each of the ten videos above (already counted; listed so nobody skips
      it by taking the ten photographs at the end).
- [ ] A 50MP still, in Pro. It is slow. It should not be more than about two seconds slow, and
      it should be 8160 pixels across on the roll.
- [ ] A burst: hold the camera button in Instant, or on a coarse filter, for three seconds. Every
      frame lands on the roll at the size the viewfinder showed, unless **Bursts may shrink** is
      on, in which case the later ones are smaller and the fault chip stays at zero either way.
- [ ] A RAW: Pro, RAW on in Files, one press. The JPEG and the DNG appear as one photograph on
      the roll and both download from Send to computer.
- [ ] A flash shot, in the dark, in Pro with the flash set to On. The room is lit in the
      photograph. Then the same in Instant; the panel flash holds the lamp across the grab.
- [ ] A front-lens photograph, in Selfie. It is the right way round.
- [ ] Stop and start a recording with the screen off between them (a press of the power button
      during the recording). The clip is whole.

## Video looks

Run the ten cycles above with a look on at least half of them (Film, VHS and Datamosh, say), and
then these:

- [ ] The looks are on: in Video, click the wheel, pick FILTER, and turn. The band names each
      look, and the viewfinder changes with it.
- [ ] Record ten seconds on VHS, turning to Super 8 halfway. The clip on the roll shows both, the
      cut lands where you turned, and the sound matches the picture at the end.
- [ ] Record on CCTV with the phone held sideways, the way the band expects. The caption is at
      the top left of the clip when it plays, and it shows the right date and time.
- [ ] The same CCTV clip on the front lens, held sideways both ways. The caption is at the top
      left each time. The front lens turns the other way to the back, which is the case the
      first build got wrong.
- [ ] `adb logcat -s VideoFx` shows one `input ... rotation=` line per bind. Write down the
      rotation and the sensor value beside it. The looks assume the input is in the sensor's
      orientation.
- [ ] Datamosh: hold still for two seconds (clean picture), pan, then stop. The pan smears the
      old picture, and it clears a block at a time about 1.5 s after you stop.
- [ ] Record on Slit-scan and on Datamosh for twenty seconds each while panning. The viewfinder
      keeps up (no held frames) and the clip plays at full speed.
- [ ] Record on Preset with nothing set. The clip looks the same as it did in the last official
      release.
- [ ] The EIS question: run `adb logcat | grep -E "VideoMorphoEISV3Offline|VideoFx"` across one
      record and stop, and write down whether the Morpho session is still built.
- [ ] Settings › Look › **Looks in video** off, record one clip, and turn it back on. Both clips
      play.

## Afterwards

- [ ] The fault chip is still at zero. Any number here is a fail, whatever it says when tapped:
      a nightly that raised a fault it cannot name is a nightly.
- [ ] Shake the phone twice and send the report anyway, with "checklist pass" or "checklist
      fail at <line>" as the note, so the build number and the tally are on record.
- [ ] Reboot the phone and open Roll once more. The viewfinder comes up. A boot that does not
      bring the camera back is the worst thing a camera change has done here, and it is the
      one thing nothing above would catch.

Then, and only then, `[release]`.
