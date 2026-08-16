package com.gios.lightcamera.hw

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The focus beep, synthesised.
 *
 * A compact camera confirms focus with two short high blips, and the reason it is worth
 * having here is the same reason it was worth having there: with the phone at arm's length
 * you can hear that the lens has locked without reading the screen for it.
 *
 * Generated rather than shipped as assets — the whole sound set is under a kilobyte of PCM
 * and a WAV in the APK would be a larger file than the code that makes it. Each tone gets a
 * 4 ms raised-cosine fade in and out; without it a 2 kHz sine that starts at full amplitude
 * puts a click on the front of every beep, which on a phone speaker is most of what you hear.
 *
 * `USAGE_ASSISTANCE_SONIFICATION` is the honest classification: this is interface feedback,
 * so it ducks under music instead of fighting it and follows the notification volume rather
 * than the media one.
 */
class Beeps(context: Context) {

    private val appContext = context.applicationContext

    private val confirm by lazy { track(confirmPcm()) }
    private val warn by lazy { track(warnPcm()) }
    private val click by lazy { track(clickPcm()) }
    private val savedTone by lazy { track(savedPcm()) }

    /** Focus acquired. Two blips, the way every digicam has done it since 1996. */
    fun focusLocked() = play(confirm)

    /** Focus gave up. One flat note, lower — audibly not the confirmation. */
    fun focusFailed() = play(warn)

    /** The shutter. A tick rather than a mirror slap; there is no mirror. */
    fun shutter() = play(click)

    /**
     * The photograph exists. Two notes rising, quiet.
     *
     * Deliberately *unlike* the focus confirmation, which is two of the same note: rising says finished
     * rather than ready. It plays when the file is on disk, so the ear brackets the wait — click when you
     * press, this when it lands — and a second and a half becomes a process with two ends instead of a
     * delay with one.
     */
    fun saved() = play(savedTone)

    fun release() {
        listOf(confirm, warn, click, savedTone).forEach { t ->
            runCatching {
                t?.stop()
                t?.release()
            }
        }
    }

    private fun play(t: AudioTrack?) {
        val track = t ?: return
        if (silenced()) return
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            // The documented way to replay a MODE_STATIC buffer. Without it the second beep
            // plays nothing at all, because the head is sitting at the end of the sample.
            track.reloadStaticData()
            track.play()
        }
    }

    /**
     * Whether the phone is muted, asked properly.
     *
     * **The ringer mode alone was not enough, and that was the bug.** `RINGER_MODE_SILENT` is only
     * set by the silent *switch*; a phone muted by holding volume-down until the bar is empty
     * frequently stays in `RINGER_MODE_NORMAL` with the stream sitting at zero, and on LightOS —
     * which has no ringer switch to flick — that is the only way to mute anything. So the shutter
     * kept clicking on a phone the user had unambiguously silenced.
     *
     * `USAGE_ASSISTANCE_SONIFICATION` is routed to `STREAM_SYSTEM`, so that is the stream that
     * decides. `STREAM_NOTIFICATION` and `STREAM_RING` are checked too because they are what most
     * launchers actually move when you press the key, and on devices that alias the three together
     * reading only one of them can report a volume nothing is playing at.
     *
     * `isStreamMute` is checked separately from the level: a stream can be muted by a policy while
     * its remembered volume is still non-zero, and that is a phone the user has silenced as well.
     */
    private fun silenced(): Boolean {
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return false
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
        return MUTABLE_STREAMS.any { stream ->
            runCatching {
                audio.isStreamMute(stream) || audio.getStreamVolume(stream) == 0
            }.getOrDefault(false)
        }
    }

    private fun track(pcm: ShortArray): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }.getOrNull()

    /* ---------------- the samples ---------------- */

    private fun confirmPcm(): ShortArray {
        val blip = tone(freq = 1900.0, ms = 30, amplitude = 0.22)
        val gap = ShortArray(msToFrames(38))
        return blip + gap + blip
    }

    private fun warnPcm(): ShortArray = tone(freq = 640.0, ms = 110, amplitude = 0.20)

    /** A fifth up, softer than the focus blips: finished, not ready. */
    private fun savedPcm(): ShortArray =
        tone(freq = 1180.0, ms = 34, amplitude = 0.16) + tone(freq = 1760.0, ms = 46, amplitude = 0.14)

    /**
     * The shutter tick.
     *
     * Two octaves struck together and decaying inside twenty milliseconds, which reads as a
     * mechanism rather than as a note. A pure sine at this length just sounds like a bleep.
     */
    private fun clickPcm(): ShortArray {
        val frames = msToFrames(22)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / SAMPLE_RATE
            val decay = Math.exp(-t * 260.0)
            val body = sin(2 * PI * 2400.0 * t) * 0.6 + sin(2 * PI * 4800.0 * t) * 0.4
            val v = body * decay * 0.28 * fade(i, frames)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun tone(freq: Double, ms: Int, amplitude: Double): ShortArray {
        val frames = msToFrames(ms)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / SAMPLE_RATE
            val v = sin(2 * PI * freq * t) * amplitude * fade(i, frames)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Raised-cosine ends, so nothing starts or stops on a discontinuity. */
    private fun fade(i: Int, frames: Int): Double {
        val edge = msToFrames(4).coerceAtMost(frames / 2).coerceAtLeast(1)
        val into = i.toDouble() / edge
        val outOf = (frames - 1 - i).toDouble() / edge
        val ramp = minOf(into, outOf, 1.0).coerceAtLeast(0.0)
        return 0.5 - 0.5 * cos(PI * ramp)
    }

    private fun msToFrames(ms: Int): Int = SAMPLE_RATE * ms / 1000

    private companion object {
        const val SAMPLE_RATE = 44_100

        /** The streams a muted phone can express itself through. Any one at zero means quiet. */
        val MUTABLE_STREAMS = intArrayOf(
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING,
        )
    }
}
