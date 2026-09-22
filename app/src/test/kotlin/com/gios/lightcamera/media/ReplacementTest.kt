package com.gios.lightcamera.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The replace sequence against real files, with a target stream that fails on purpose.
 *
 * The property under test is the one sentence in [Replacement]'s contract: after the call the
 * target holds the replacement or the original, never a piece of either.
 */
class ReplacementTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val original = ByteArray(50_000) { (it % 251).toByte() }
    private val replacement = ByteArray(20_000) { (it % 13).toByte() }

    /** A stream that truncates the file and then dies after [after] bytes. */
    private class Dying(private val out: FileOutputStream, private val after: Int) : OutputStream() {
        private var written = 0
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            val allowed = minOf(len, after - written)
            if (allowed > 0) {
                out.write(b, off, allowed)
                written += allowed
            }
            if (written >= after) throw IOException("disk full")
        }
        override fun close() = out.close()
    }

    @Test
    fun `a clean write replaces the bytes and leaves no temporaries`() {
        val target = folder.newFile("photo.jpg").apply { writeBytes(original) }
        val staging = folder.root.resolve("s.tmp")
        val backup = folder.root.resolve("b.tmp")
        val ok = Replacement.replace(
            bytes = replacement,
            staging = staging,
            backup = backup,
            readTarget = { target.inputStream() },
            writeTarget = { FileOutputStream(target, false) },
        )
        assertTrue(ok)
        assertArrayEquals(replacement, target.readBytes())
        assertFalse(staging.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `a write that dies halfway leaves the original`() {
        val target = folder.newFile("photo.jpg").apply { writeBytes(original) }
        var opened = 0
        val ok = Replacement.replace(
            bytes = replacement,
            staging = folder.root.resolve("s.tmp"),
            backup = folder.root.resolve("b.tmp"),
            readTarget = { target.inputStream() },
            writeTarget = {
                opened += 1
                // The first open — the replacement — dies after a few kilobytes with the file
                // already truncated. The second open is the restore, and it works.
                if (opened == 1) Dying(FileOutputStream(target, false), 4_096)
                else FileOutputStream(target, false)
            },
        )
        assertFalse(ok)
        assertArrayEquals(original, target.readBytes())
    }

    @Test
    fun `a target that will not open for writing is untouched`() {
        val target = folder.newFile("photo.jpg").apply { writeBytes(original) }
        val ok = Replacement.replace(
            bytes = replacement,
            staging = folder.root.resolve("s.tmp"),
            backup = folder.root.resolve("b.tmp"),
            readTarget = { target.inputStream() },
            writeTarget = { null },
        )
        assertFalse(ok)
        assertArrayEquals(original, target.readBytes())
    }

    @Test
    fun `a target that cannot be read back is never written`() {
        val target = folder.newFile("photo.jpg").apply { writeBytes(original) }
        var writes = 0
        val ok = Replacement.replace(
            bytes = replacement,
            staging = folder.root.resolve("s.tmp"),
            backup = folder.root.resolve("b.tmp"),
            readTarget = { null },
            writeTarget = { writes += 1; FileOutputStream(target, false) },
        )
        assertFalse(ok)
        assertTrue(writes == 0)
        assertArrayEquals(original, target.readBytes())
    }
}
