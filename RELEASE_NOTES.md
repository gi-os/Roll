## Roll v2.54 — a recording saves itself in the background

**Ending a take used to be the most expensive thing this app could do.** It no longer does any of
that work while you are holding the camera.

Video was recorded straight into MediaStore. That is the right answer for a photograph — one write,
CameraX handles `IS_PENDING`, and nothing half-finished is ever visible in another gallery — and it
is the wrong answer for a video, because a video is not one write.

`MediaStoreOutputOptions` hands the muxer a descriptor on a path MediaProvider owns, and keeps it
for the whole take. Since Android 11 those paths are served through MediaProvider's FUSE daemon, so
every write the encoder made went out through another process instead of straight to the
filesystem — which an app's own directory does not do. Then the stop put the moov atom through that
same descriptor and cleared `IS_PENDING`, and clearing it is what makes MediaProvider *scan* the
file: parse the container for its duration and resolution, and build a thumbnail. All of it inside
the `update` call, with the camera session still live and the recorder calling back onto the main
thread. Nothing lowered the recording flag until every bit of that had finished, so the record dot,
the mode strip and the next take were all waiting on it.

What that looks like on this phone is the panel freezing at the moment you press stop, and the
longer the clip the longer it lasts.

Now the recorder writes to a plain file in the app's own storage. Stopping a take is a local muxer
closing a local file, and the camera is idle the instant it does — so **you can start the next
recording immediately**, while the last one is still on its way to the gallery.

Getting it there is a separate job. `ClipSaver` is one queue for the whole process that copies each
clip into `DCIM/Camera` and then deletes the temporary file, **one clip at a time** — two copies at
once is two readers and two writers on the same flash, which is slower in total and is load the
camera would feel. The bytes are streamed through a fixed 64 kB buffer, never read into memory: a
minute of HD is around a hundred megabytes and the heap here is a fraction of that.

The queue belongs to the process rather than to the screen, so leaving the viewfinder does not
cancel a copy. What covers the rest is a sweep: a clip that has not been saved yet is a file sitting
in a directory, so if the system kills the app mid-copy the work is picked up the next time the
camera opens. That is more than a background assertion can promise, since no assertion survives the
process dying. Those files live in `noBackupFilesDir` — the system may clear a cache directory
whenever it wants the space, and this is a video you have just shot.

Two smaller things fall out of it. A clip's timestamp is read out of its file name, which is written
at the press, because the file's own `lastModified` is the moment the take *ended* and on a
two-minute clip that is two minutes late. And the panel now reads `SAVING` beside the record dot
while the queue is busy — a clip is not in the roll until it has been copied, and without a mark
there a take you had just stopped looked like a take that had gone missing. If a copy fails the
panel says so once, and the file is kept for the next sweep to try again.

One thing this note deliberately does not claim: the report that ended a recording also **restarted
the phone**. The freeze above is in the code and this removes it; a restart is the kernel or a
platform watchdog, and nothing in the app can prove or disprove that from here. What can be said is
that the app no longer holds a media descriptor open through another process for minutes at a time,
which is the only part of the old path that had any reach outside it.
