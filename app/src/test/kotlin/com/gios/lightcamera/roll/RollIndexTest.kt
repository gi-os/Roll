package com.gios.lightcamera.roll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What recovery does with a roll's directory, decided without a filesystem.
 *
 * The case that matters is the second one: a frame on disk the index never recorded is a
 * photograph that survived a crash, and for a long time recovery deleted it.
 */
class RollIndexTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun entry(index: Int, name: String = "frame-%02d.jpg".format(index)) =
        IndexEntry(index, 1_000L * index, "none", 4000, 3000, name)

    private fun frame(name: String, at: Long, size: Long = 2_000_000L, head: ByteArray = jpeg) =
        DiskFrame(name, size, at, head)

    @Test
    fun `index and disk agree, nothing changes`() {
        val entries = listOf(entry(1), entry(2), entry(3))
        val files = listOf(frame("frame-01.jpg", 10), frame("frame-02.jpg", 20), frame("frame-03.jpg", 30))
        val out = RollIndex.reconcile(entries, files)
        assertEquals(entries, out.entries)
        assertTrue(out.adopted.isEmpty())
        assertTrue(out.delete.isEmpty())
        assertEquals(0, out.dropped)
        assertFalse(out.changed)
    }

    @Test
    fun `an orphan on disk is adopted, not deleted`() {
        val entries = listOf(entry(1), entry(2))
        val files = listOf(
            frame("frame-01.jpg", 10),
            frame("frame-02.jpg", 20),
            frame("frame-03.jpg", 30),
        )
        val out = RollIndex.reconcile(entries, files)
        assertTrue(out.delete.isEmpty())
        assertEquals(3, out.entries.size)
        val adopted = out.adopted.single()
        assertEquals(3, adopted.index)
        assertEquals("frame-03.jpg", adopted.fileName)
        assertEquals(30L, adopted.takenAt)
        assertTrue(adopted.recovered)
        assertTrue(out.changed)
        // The adopted line survives a round trip through the file format, flag and all.
        assertEquals(adopted, RollIndex.parseLine(RollIndex.formatLine(adopted)))
    }

    @Test
    fun `an orphan whose number is taken gets the next free one`() {
        // The index says frame 2 is `frame-02.jpg`, but a stranger called `extra.jpg` is also
        // here. It cannot be 2; it becomes 3.
        val entries = listOf(entry(1), entry(2))
        val files = listOf(frame("frame-01.jpg", 10), frame("frame-02.jpg", 20), frame("extra.jpg", 25))
        val out = RollIndex.reconcile(entries, files)
        assertEquals(listOf(1, 2, 3), out.entries.map { it.index })
        assertEquals("extra.jpg", out.entries.last().fileName)
    }

    @Test
    fun `orphans are adopted oldest first`() {
        val files = listOf(
            frame("b.jpg", 200),
            frame("a.jpg", 100),
            frame("c.jpg", 300, head = png),
        )
        val out = RollIndex.reconcile(emptyList(), files)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), out.entries.map { it.fileName })
        assertEquals(listOf(1, 2, 3), out.entries.map { it.index })
    }

    @Test
    fun `an index entry missing on disk is dropped`() {
        val entries = listOf(entry(1), entry(2), entry(3))
        val files = listOf(frame("frame-01.jpg", 10), frame("frame-03.jpg", 30))
        val out = RollIndex.reconcile(entries, files)
        assertEquals(listOf(1, 3), out.entries.map { it.index })
        assertEquals(1, out.dropped)
        assertTrue(out.changed)
        assertTrue(out.delete.isEmpty())
    }

    @Test
    fun `a zero-byte file is deleted`() {
        val entries = listOf(entry(1))
        val files = listOf(frame("frame-01.jpg", 10), frame("frame-02.jpg", 20, size = 0L, head = ByteArray(0)))
        val out = RollIndex.reconcile(entries, files)
        assertEquals(listOf("frame-02.jpg"), out.delete)
        assertEquals(listOf(1), out.entries.map { it.index })
        assertTrue(out.adopted.isEmpty())
    }

    @Test
    fun `a file that is not an image is deleted`() {
        val files = listOf(frame("frame-01.jpg", 10, head = "hello".toByteArray()))
        val out = RollIndex.reconcile(emptyList(), files)
        assertEquals(listOf("frame-01.jpg"), out.delete)
        assertTrue(out.entries.isEmpty())
    }

    @Test
    fun `header check`() {
        assertTrue(RollIndex.looksLikeImage(jpeg, 100))
        assertTrue(RollIndex.looksLikeImage(png, 100))
        assertFalse(RollIndex.looksLikeImage(jpeg, 0))
        assertFalse(RollIndex.looksLikeImage(ByteArray(0), 100))
        assertFalse(RollIndex.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), 100))
    }

    @Test
    fun `old six-column lines still parse and new ones round-trip`() {
        val old = RollIndex.parseLine("4|1700000000000|film|4000|3000|frame-04.jpg")
        assertEquals(entry(4).copy(takenAt = 1700000000000L, filterId = "film"), old)
        assertFalse(old!!.recovered)
        assertNull(RollIndex.parseLine("garbage"))
        assertNull(RollIndex.parseLine("x|1|a|2|3|f.jpg"))
        assertEquals(7, RollIndex.frameNumber("frame-07.jpg"))
        assertNull(RollIndex.frameNumber("extra.jpg"))
    }
}
