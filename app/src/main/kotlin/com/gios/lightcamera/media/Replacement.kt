package com.gios.lightcamera.media

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Replace a file's contents so that the original survives any failure short of the replacement
 * being complete.
 *
 * **The develop pass used to truncate the photograph before it had anything to put in its
 * place.** A `MediaStore` row cannot be renamed over — there is no `renameTo` for a content URI —
 * so the only way to replace its bytes is to open it truncating and write. Between the truncate and
 * the last byte the file on disk is a partial JPEG, and a process death, a full disk or an I/O
 * error in that window left it that way: a photograph that was whole a second earlier, now a file
 * every gallery draws as a grey block. The write is a few megabytes and usually takes tens of
 * milliseconds, which is why it took a while to be seen, and why it was worth fixing anyway — the
 * darkroom runs after the shutter has already promised the photograph is saved.
 *
 * Three steps, each complete before the next begins. The replacement is written whole to
 * [staging], so the bytes exist on disk before the original is touched and a failure here costs
 * nothing. The original is copied whole to [backup], so there is a complete copy of it that no
 * write to the target can damage. Only then is the target truncated and the staging file streamed
 * into it in one pass — and if that pass fails, the backup is streamed back the same way, so the
 * target ends the call holding either the replacement or the original, never a piece of one.
 *
 * `java.io` only. The content-resolver half — how the target is opened — is passed in, so the
 * sequence can be tested with plain files and a stream that fails on purpose.
 */
object Replacement {

    /**
     * @param bytes the replacement, complete.
     * @param staging a file this call may create and will delete; same filesystem as [backup].
     * @param backup a file this call may create and will delete.
     * @param readTarget opens the target for reading, or null if it cannot be read.
     * @param writeTarget opens the target for writing, truncating, or null if it cannot be.
     * @return true when the target holds [bytes]. False when it holds the original, untouched or
     *   restored. A false return with the original gone is the state this exists to rule out, and
     *   the only way to reach it is a failure inside the restore itself.
     */
    fun replace(
        bytes: ByteArray,
        staging: File,
        backup: File,
        readTarget: () -> InputStream?,
        writeTarget: () -> OutputStream?,
    ): Boolean {
        try {
            // 1. The replacement, whole, on disk. Checked by length rather than trusted: a
            //    cache directory on a full disk writes short without throwing on some kernels.
            staging.writeBytes(bytes)
            if (staging.length() != bytes.size.toLong()) return false

            // 2. The original, whole, on disk. No copy means no write: a photograph that cannot
            //    be read back is not one to gamble on.
            val original = readTarget() ?: return false
            original.use { input -> backup.outputStream().use { out -> input.copyTo(out) } }

            // 3. The one destructive step, with a way back.
            val written = runCatching {
                val out = writeTarget() ?: error("target would not open for writing")
                out.use { staging.inputStream().use { input -> input.copyTo(it) } }
            }.isSuccess
            if (written) return true

            // The write failed with the target already truncated. Put the original back.
            runCatching {
                val out = writeTarget() ?: error("target would not reopen for the restore")
                out.use { backup.inputStream().use { input -> input.copyTo(it) } }
            }
            return false
        } finally {
            staging.delete()
            backup.delete()
        }
    }
}
