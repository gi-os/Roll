package com.gios.lightcamera.filter

import kotlin.random.Random

/**
 * Datamoshing, done to the actual bytes.
 *
 * **A JPEG is not pixels, and this file is the only place in the app that acts like it.** Every
 * other filter is a shader: it reads colours and writes colours. This one runs *after* the encoder,
 * on the compressed file, and edits the structures the decoder will use to rebuild the image. That
 * is what databending is, and it is why the artifacts look the way they do — you cannot get them by
 * drawing, only by breaking.
 *
 * Ported from cebola4444's cybershot-cam (`src/main.cpp`), which does this on an ESP32-S3 to its own
 * camera's output. Five operations, in the order they are applied:
 *
 * | Function | Marker | What it edits | What you see |
 * | --- | --- | --- | --- |
 * | [rotateZigzag] | `FFDB` | circular-rotates the 63 AC quantisation values | frequencies swap roles — emboss, relief |
 * | [erodeQuant] | `FFDB` | low frequencies to 1, high to 255 | detail erased, blocks posterise |
 * | [amplifyChroma] | `FFDB` (table 1) | chroma table's DC and mid AC only | colour explodes, luminance stays sharp |
 * | [rotateHuffman] | `FFC4` | rotates AC symbols within each magnitude group | coefficients land in the wrong slots |
 * | [transplantScan] | `FFDA`→`FFD9` | overwrites runs of the entropy stream | **Huffman desync — the smear** |
 *
 * The last one is the actual mosh and the reason the others are here at all. JPEG packs every block
 * into one continuous bitstream with no byte alignment between blocks, and each block's DC
 * coefficient is stored as a *difference* from the previous block's. So overwriting a run of scan
 * bytes does two things at once: the reader loses block alignment, and the DC difference chain
 * inherits an error that every subsequent block adds to. The error drags sideways, because blocks
 * are written in raster order. That drag is what people mean by datamoshing.
 *
 * **One photograph, no burst.** The transplant's donor is another region of the *same* scan, which is
 * what the reference does and is enough: the bytes are valid entropy-coded data, so the decoder keeps
 * decoding, but they describe a different part of the frame and every block downstream inherits the
 * resulting DC error. Pulling donors from a second captured frame was tried and removed — it needed a
 * three-second burst for an effect the single-frame transplant already produces, and it made the
 * shutter three seconds slower for it.
 *
 * Every function here is pure, takes its randomness from an injected [Random], and mutates a byte
 * array in place. None of them can throw on malformed input — a truncated or marker-less JPEG simply
 * comes back unchanged, because the one thing this must never do is cost somebody the photograph.
 */
object Databend {

    /* ---------------- markers ---------------- */

    private const val MARKER = 0xFF
    private const val DQT = 0xDB
    private const val DHT = 0xC4
    private const val SOS = 0xDA
    private const val EOI = 0xD9

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    private fun ByteArray.segmentLength(at: Int): Int = (u(at + 2) shl 8) or u(at + 3)

    /**
     * Every offset in [buf] where a segment with this marker starts.
     *
     * Walks segment to segment rather than scanning for the byte pair, because `FF DB` occurs inside
     * compressed data all the time and a naive search would happily "find" a quantisation table in
     * the middle of somebody's hair.
     */
    private fun segments(buf: ByteArray, marker: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var i = 2 // past SOI
        while (i + 4 <= buf.size) {
            if (buf.u(i) != MARKER) return out
            val kind = buf.u(i + 1)
            if (kind == SOS || kind == EOI) return out
            val len = buf.segmentLength(i)
            if (len < 2 || i + 2 + len > buf.size) return out
            if (kind == marker) out += i
            i += 2 + len
        }
        return out
    }

    /* ---------------- DQT: the quantisation tables ---------------- */

    /**
     * Walk each table in a DQT segment, handing the callback the table's id and the offset of its
     * first value. `precision` is 0 for 8-bit values and 1 for 16-bit ones, which changes the stride.
     */
    private inline fun eachQuantTable(
        buf: ByteArray,
        crossinline body: (id: Int, precision: Int, at: Int) -> Unit,
    ) {
        segments(buf, DQT).forEach { seg ->
            val end = seg + 2 + buf.segmentLength(seg)
            var pos = seg + 4
            while (pos < end - 1) {
                val precision = (buf.u(pos) shr 4) and 0x0F
                val id = buf.u(pos) and 0x0F
                val bytes = if (precision != 0) 128 else 64
                pos++
                if (pos + bytes > end) return@forEach
                body(id, precision, pos)
                pos += bytes
            }
        }
    }

    private fun writeQuant(buf: ByteArray, at: Int, precision: Int, k: Int, value: Int) {
        if (precision != 0) {
            buf[at + k * 2] = 0
            buf[at + k * 2 + 1] = value.toByte()
        } else {
            buf[at + k] = value.toByte()
        }
    }

    private fun readQuant(buf: ByteArray, at: Int, precision: Int, k: Int): Int =
        if (precision != 0) buf.u(at + k * 2 + 1) else buf.u(at + k)

    /**
     * Low frequencies amplified to quant=1, high frequencies erased to quant=255.
     *
     * The DC term at k=0 is deliberately preserved. It carries the block's average brightness, and
     * flattening it turns the photograph into noise rather than into a glitch — there would be
     * nothing left to recognise.
     */
    fun erodeQuant(buf: ByteArray, intensity: Float) {
        val cutLow = (intensity * 19f).toInt()
        val cutHi = 63 - (intensity * 31f).toInt()
        eachQuantTable(buf) { _, precision, at ->
            for (k in 1 until 64) {
                val v = when {
                    k <= cutLow -> 1
                    k >= cutHi -> 255
                    else -> continue
                }
                writeQuant(buf, at, precision, k, v)
            }
        }
    }

    /**
     * Circular-rotate the 63 AC quantisation values.
     *
     * The table is read in zigzag order, so moving values along it hands each frequency the
     * divisor that belonged to a different one. Fine detail gets quantised as if it were coarse and
     * the other way round, which reads as posterisation with an embossed edge.
     */
    fun rotateZigzag(buf: ByteArray, intensity: Float) {
        val rotation = 4 + (intensity * 37f).toInt()
        eachQuantTable(buf) { _, precision, at ->
            val ac = IntArray(63) { readQuant(buf, at, precision, it + 1) }
            for (k in 0 until 63) {
                writeQuant(buf, at, precision, k + 1, ac[(k + rotation) % 63])
            }
        }
    }

    /**
     * Blow out colour without touching sharpness.
     *
     * Only table id 1, the chrominance one — luminance keeps its own table untouched, so edges and
     * detail survive intact while the colour goes wild. If the encoder emitted no separate chroma
     * table (it shares one), every table is treated instead, because the alternative is doing
     * nothing at all.
     */
    fun amplifyChroma(buf: ByteArray, intensity: Float) {
        var hasChroma = false
        eachQuantTable(buf) { id, _, _ -> if (id == 1) hasChroma = true }

        val midHi = 18 + (intensity * 10f).toInt()
        val dc = 162 + (intensity * 18f).toInt()
        val ac = 198 + (intensity * 32f).toInt()

        eachQuantTable(buf) { id, precision, at ->
            if (hasChroma && id != 1) return@eachQuantTable
            writeQuant(buf, at, precision, 0, dc)
            for (k in 4..midHi.coerceAtMost(63)) writeQuant(buf, at, precision, k, ac)
        }
    }

    /**
     * Rotate the AC Huffman symbols inside each magnitude group.
     *
     * **AC tables only — rotating a DC table desynchronises the stream immediately** and the image
     * does not survive it. A symbol's low nibble is the coefficient's magnitude and the high nibble
     * is the run of zeroes before it; shuffling symbols within one magnitude keeps every code length
     * valid, so the file still decodes, but each coefficient lands at the wrong position in its
     * block. Higher magnitudes are rotated further, so fine detail distorts more than structure.
     */
    fun rotateHuffman(buf: ByteArray, intensity: Float) {
        val rotLuma = (1 + (intensity * 5f).toInt()).coerceIn(1, 6)
        val rotChroma = (rotLuma - 1).coerceIn(1, 3)

        segments(buf, DHT).forEach { seg ->
            val end = seg + 2 + buf.segmentLength(seg)
            var pos = seg + 4
            while (pos < end) {
                val tableClass = (buf.u(pos) shr 4) and 0x0F
                val tableId = buf.u(pos) and 0x0F
                pos++
                if (pos + 16 > end) return@forEach
                var symbols = 0
                for (k in 0 until 16) symbols += buf.u(pos + k)
                pos += 16
                if (pos + symbols > end) return@forEach

                if (tableClass == 1 && symbols > 1) {
                    val rotation = if (tableId == 0) rotLuma else rotChroma
                    for (size in 1..10) {
                        val idx = ArrayList<Int>(16)
                        for (k in 0 until symbols) {
                            if ((buf.u(pos + k) and 0x0F) == size) {
                                idx += k
                                if (idx.size >= 16) break
                            }
                        }
                        if (idx.size < 2) continue
                        val values = IntArray(idx.size) { buf.u(pos + idx[it]) }
                        val shift = ((rotation * size) / 5).coerceIn(1, idx.size - 1)
                        for (k in idx.indices) {
                            buf[pos + idx[k]] = values[(k + shift) % idx.size].toByte()
                        }
                    }
                }
                pos += symbols
            }
        }
    }

    /* ---------------- the scan: where the mosh actually happens ---------------- */

    /** Start and end of the entropy-coded data, or null if this isn't a JPEG we understand. */
    private fun scanRange(buf: ByteArray): IntRange? {
        var i = 2
        var start = -1
        while (i + 4 <= buf.size) {
            if (buf.u(i) != MARKER) break
            val kind = buf.u(i + 1)
            if (kind == SOS) {
                start = i + 2 + buf.segmentLength(i)
                break
            }
            if (kind == EOI) break
            val len = buf.segmentLength(i)
            if (len < 2) break
            i += 2 + len
        }
        if (start < 0 || start >= buf.size) return null
        var end = buf.size - 2
        var j = buf.size - 2
        while (j >= start) {
            if (buf.u(j) == MARKER && buf.u(j + 1) == EOI) {
                end = j
                break
            }
            j--
        }
        return if (end - start < 512) null else start until end
    }

    /**
     * Overwrite runs of the scan with bytes from elsewhere in the same scan. **This is the mosh.**
     *
     * Straight from `applyGlitchScan`: two to four transplants, each a run of scan length over a
     * random divisor, sourced from 25–50% further along. The transplanted bytes are themselves valid
     * entropy-coded data, so the decoder does not stop — it carries on reading block boundaries in
     * the wrong place, producing blocks that belong somewhere else and a DC difference chain that is
     * now wrong for the rest of the row.
     */
    fun transplantScan(buf: ByteArray, intensity: Float, random: Random) {
        val scan = scanRange(buf) ?: return
        val start = scan.first
        val length = scan.last - scan.first + 1

        val transplants = 2 + random.nextInt(3)
        val divisor = 8 + random.nextInt(11)
        val donorPercent = 25 + random.nextInt(26)
        val runLength = (length / divisor).coerceIn(64, 4096)
        val donorOffset = length * donorPercent / 100

        for (t in 0 until transplants) {
            // The reference biases each transplant's position by the exposure reading so a dark
            // frame tears in different places from a bright one. Same idea, driven by [intensity].
            val base = (t.toFloat() / transplants) + intensity * (0.6f / transplants)
            val at = start + (base * (length - runLength)).toInt().coerceIn(0, length - runLength)
            val from = start + ((at - start + donorOffset) % length)
            if (from + runLength > scan.last) continue
            System.arraycopy(buf, from, buf, at, runLength)
            healSeam(buf, at, scan)
            healSeam(buf, at + runLength, scan)
        }
    }

    /**
     * Check the two bytes either side of a transplant's edge for an invented marker.
     *
     * **The one deliberate departure from the reference, and it is four bytes wide.** Inside valid
     * scan data an `FF` is always followed by `00` or a restart marker, so a run copied wholesale
     * stays legal in its middle — the only place a transplant can accidentally spell `FF D9` is
     * where it butts against the bytes it replaced. An `FF D9` is end-of-image: every decoder stops
     * there, and the photograph becomes whatever was above the tear plus a grey rectangle. That is a
     * result databenders enjoy and a camera cannot ship, because the difference between a glitched
     * photograph and half a photograph is the difference between a filter and a bug.
     *
     * Nudging the `FF` to `FE` costs one coefficient in one block and nothing else.
     */
    private fun healSeam(buf: ByteArray, seam: Int, scan: IntRange) {
        for (i in (seam - 1).coerceAtLeast(scan.first) until (seam + 1).coerceAtMost(scan.last)) {
            if (buf.u(i) != MARKER) continue
            val next = buf.u(i + 1)
            // 0x00 is byte stuffing and legal; RSTn (D0..D7) are legal restart markers.
            if (next != 0x00 && (next < 0xD0 || next > 0xD7)) buf[i] = 0xFE.toByte()
        }
    }

    /**
     * The whole treatment, in the order the reference applies it.
     *
     * Table edits first and the scan transplant last, which matters: the transplant is the only step
     * that can desynchronise the bitstream, and the table walkers navigate by segment length from the
     * front of the file, so running them afterwards would have them parsing a structure that a
     * transplant may have moved out from under them.
     *
     * Returns a new array; [jpeg] is not modified.
     */
    fun apply(
        jpeg: ByteArray,
        intensity: Float = 0.6f,
        random: Random = Random.Default,
    ): ByteArray {
        val out = jpeg.copyOf()
        val amount = intensity.coerceIn(0.05f, 0.95f)
        runCatching {
            rotateZigzag(out, amount)
            erodeQuant(out, amount)
            amplifyChroma(out, amount)
            rotateHuffman(out, amount)
            transplantScan(out, amount, random)
        }.onFailure { return jpeg }
        return out
    }
}
