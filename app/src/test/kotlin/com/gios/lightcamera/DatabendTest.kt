package com.gios.lightcamera

import com.gios.lightcamera.filter.Databend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The databend, off-device.
 *
 * This is the one filter that can be tested properly without a phone, because it is pure byte
 * manipulation rather than a shader — so the tests here are worth more than the ones for anything
 * else in `filter/`. What they are guarding is narrow and specific: **a databend must damage the
 * image without destroying the file.** Every assertion below is some version of that.
 */
class DatabendTest {

    /**
     * A JPEG skeleton: SOI, a DQT with two tables, a DHT with an AC table, an SOS, a body, EOI.
     *
     * Synthetic rather than a real photograph, because a fixture that big does not belong in a repo
     * and because a hand-built one can be made to contain exactly the structures under test.
     */
    private fun jpeg(scanBytes: Int = 4096): ByteArray {
        val out = ArrayList<Byte>(scanBytes + 512)
        fun w(vararg v: Int) = v.forEach { out += it.toByte() }

        w(0xFF, 0xD8) // SOI

        // DQT: two 8-bit tables, ids 0 and 1. Length = 2 + 2 * (1 + 64).
        w(0xFF, 0xDB, 0x00, 0x84)
        for (id in 0..1) {
            w(id)
            for (k in 0 until 64) w((k * 2 + 16) and 0xFF)
        }

        // DHT: one AC table (class 1, id 0) with 12 symbols spread over the length counts.
        val counts = IntArray(16)
        counts[0] = 4
        counts[1] = 8
        val symbols = IntArray(12) { (it % 4 shl 4) or ((it % 6) + 1) }
        w(0xFF, 0xC4, 0x00, (2 + 1 + 16 + symbols.size))
        w(0x10)
        counts.forEach { w(it) }
        symbols.forEach { w(it) }

        // SOF0, so the file declares a size.
        w(0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x40, 0x00, 0x40, 0x01, 0x01, 0x11, 0x00)

        w(0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00) // SOS

        // Scan body. Legal entropy data: every 0xFF is followed by 0x00 stuffing.
        var i = 0
        while (i < scanBytes) {
            val b = (i * 37 + 11) and 0xFF
            if (b == 0xFF) {
                w(0xFF, 0x00)
                i += 2
            } else {
                w(b)
                i++
            }
        }

        w(0xFF, 0xD9) // EOI
        return out.toByteArray()
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    /** Offset of the first 8-bit quantisation table's values. */
    private fun quantAt(buf: ByteArray) = buf.indexOfMarker(0xDB) + 5

    private fun ByteArray.indexOfMarker(marker: Int): Int {
        var i = 2
        while (i + 4 <= size) {
            if (u(i) != 0xFF) return -1
            if (u(i + 1) == marker) return i
            if (u(i + 1) == 0xDA || u(i + 1) == 0xD9) return -1
            i += 2 + ((u(i + 2) shl 8) or u(i + 3))
        }
        return -1
    }

    @Test
    fun `the file keeps its shape`() {
        val out = Databend.apply(jpeg(), intensity = 0.9f, random = Random(1))
        assertEquals("lost SOI", 0xFF, out.u(0))
        assertEquals("lost SOI", 0xD8, out.u(1))
        assertEquals("lost EOI", 0xFF, out.u(out.size - 2))
        assertEquals("lost EOI", 0xD9, out.u(out.size - 1))
        assertEquals("length must not change", jpeg().size, out.size)
    }

    @Test
    fun `the input is never modified`() {
        val original = jpeg()
        val copy = original.copyOf()
        Databend.apply(original, intensity = 0.9f, random = Random(2))
        assertArrayEquals("apply() must not mutate its argument", copy, original)
    }

    @Test
    fun `something actually changed`() {
        val original = jpeg()
        val out = Databend.apply(original, intensity = 0.7f, random = Random(3))
        assertFalse("a databend that changes nothing is a no-op", original.contentEquals(out))
    }

    @Test
    fun `no marker is invented inside the scan`() {
        // The failure this guards against is the expensive one: an FF D9 in the middle of the scan
        // is an end-of-image, and every decoder stops there. The photograph becomes whatever was
        // above the tear plus a grey rectangle.
        repeat(40) { seed ->
            val out = Databend.apply(jpeg(), intensity = 0.95f, random = Random(seed))
            val sos = out.indexOfMarker(0xDA)
            val scanStart = sos + 2 + ((out.u(sos + 2) shl 8) or out.u(sos + 3))
            for (i in scanStart until out.size - 2) {
                if (out.u(i) != 0xFF) continue
                val next = out.u(i + 1)
                assertTrue(
                    "seed $seed invented marker FF${next.toString(16)} at $i",
                    next == 0x00 || next in 0xD0..0xD7,
                )
            }
        }
    }

    @Test
    fun `the DC quantiser is preserved`() {
        // k=0 carries the block's average brightness. Flatten it and the photograph becomes noise
        // rather than a glitch — there is nothing left to recognise.
        val at = quantAt(jpeg())
        val before = jpeg().u(at)
        val out = jpeg().also { Databend.erodeQuant(it, 0.95f) }
        assertEquals("erodeQuant must leave the DC term alone", before, out.u(at))
    }

    @Test
    fun `zigzag rotation permutes rather than destroys`() {
        val out = jpeg().also { Databend.rotateZigzag(it, 0.8f) }
        val at = quantAt(out)
        val original = jpeg()
        val before = (1..63).map { original.u(at + it) }.sorted()
        val after = (1..63).map { out.u(at + it) }.sorted()
        assertEquals("a rotation must keep every value it started with", before, after)
        assertNotEquals("nothing moved", (1..63).map { original.u(at + it) }, (1..63).map { out.u(at + it) })
    }

    @Test
    fun `the Huffman tables are never touched at all`() {
        // v2.48 removed `rotateHuffman`. It rotated AC symbols within a magnitude group, which kept
        // every code length valid — the file decoded — but rewrote each symbol's zero-run, so every
        // coefficient in the image landed at a different frequency. The result was confetti with no
        // photograph under it, which is what light-reports#29 was.
        //
        // Guarded as "the whole DHT segment is byte-identical" rather than as the absence of a
        // function, because the next person to reach for a Huffman-table edit will write a new
        // function and this should stop them there.
        val original = jpeg()
        val out = Databend.apply(original, intensity = 0.95f, random = Random(11))
        val dht = original.indexOfMarker(0xC4)
        val end = dht + 2 + ((original.u(dht + 2) shl 8) or original.u(dht + 3))
        for (i in dht until end) {
            assertEquals("the databend rewrote a Huffman table byte at $i", original.u(i), out.u(i))
        }
    }

    @Test
    fun `every table keeps its DC quantiser through the whole pipeline`() {
        // The other half of light-reports#29. `amplifyChroma` wrote 162-180 into the chroma table's
        // DC slot, where a real table holds 17-99, and a chroma DC quantiser that large snaps each
        // block's average colour to a wildly wrong value — the flat acid green and magenta.
        //
        // `erodeQuant` has always preserved k=0 and `rotateZigzag` only permutes k=1..63, so with
        // the chroma edit gone this now holds for the whole treatment and not just one function.
        val original = jpeg()
        val out = Databend.apply(original, intensity = 0.95f, random = Random(13))
        // Both tables: id 0 at the segment's first value, id 1 one table-plus-id-byte further on.
        val first = quantAt(original)
        listOf(first, first + 65).forEach { at ->
            assertEquals(
                "the DC quantiser at $at moved — colour will snap to nonsense",
                original.u(at),
                out.u(at),
            )
        }
    }

    @Test
    fun `garbage in is garbage out rather than a crash`() {
        // Every one of these reaches Databend only if something upstream has already gone wrong.
        // The contract is that it hands the bytes back rather than throwing on the shutter's path.
        listOf(
            ByteArray(0),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            ByteArray(64) { 0 },
            ByteArray(600) { 0xFF.toByte() },
            jpeg(scanBytes = 16),
        ).forEachIndexed { i, bad ->
            val out = Databend.apply(bad, intensity = 0.9f, random = Random(i))
            assertEquals("case $i changed length", bad.size, out.size)
        }
    }

    @Test
    fun `intensity is clamped so a caller cannot ask for nonsense`() {
        listOf(-5f, 0f, 1f, 99f, Float.NaN).forEach { value ->
            val out = Databend.apply(jpeg(), intensity = value, random = Random(7))
            assertEquals(jpeg().size, out.size)
        }
    }

    @Test
    fun `the same seed gives the same photograph twice`() {
        val a = Databend.apply(jpeg(), intensity = 0.6f, random = Random(42))
        val b = Databend.apply(jpeg(), intensity = 0.6f, random = Random(42))
        assertArrayEquals("the databend must be reproducible for a given seed", a, b)
    }

    @Test
    fun `different seeds give different damage`() {
        val a = Databend.apply(jpeg(), intensity = 0.6f, random = Random(1))
        val b = Databend.apply(jpeg(), intensity = 0.6f, random = Random(2))
        assertFalse("every shot coming out identical would defeat the point", a.contentEquals(b))
    }
}
