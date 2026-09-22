package com.gios.lightcamera.roll

/**
 * One line of a roll's index, before it is tied to a `File`.
 *
 * The index is the plain-text file beside the frames — see [FilmRoll] — and this is a line of it
 * with nothing from `java.io` attached, so the decision about what a roll's directory *means* can
 * be made and tested without a filesystem.
 */
data class IndexEntry(
    val index: Int,
    val takenAt: Long,
    val filterId: String,
    val width: Int,
    val height: Int,
    val fileName: String,
    /**
     * True for a frame the index never recorded and recovery adopted.
     *
     * Carried as an optional seventh column, so an index written by an older version still reads
     * — the parser takes the first six and ignores the rest — and a frame with no record of its
     * filter or dimensions is at least marked as one.
     */
    val recovered: Boolean = false,
)

/**
 * A file in the roll's directory, as recovery sees it: a name, a size, a modification time and
 * the first few bytes. Nothing else about the file is needed to decide its fate.
 */
data class DiskFrame(
    val name: String,
    val size: Long,
    val modifiedAt: Long,
    val head: ByteArray,
)

/**
 * The decision recovery makes about a roll's directory.
 *
 * **A crash between the frame and its index line used to cost the photograph.** [FilmRoll.expose]
 * writes the JPEG first and the index line second, on purpose: the other order leaves an index
 * pointing at nothing. But the recovery that ran at the next launch treated a frame the index did
 * not know about as rubbish and deleted it — which turned a process death at exactly the wrong
 * millisecond into a lost photograph, silently, with the counter reading one short. The frame was
 * whole. It was the bookkeeping that was behind.
 *
 * So recovery now adopts. A file the index does not know about is taken onto the roll as a frame
 * if it is demonstrably an image, and thrown away only if it demonstrably is not: zero bytes, or a
 * header that is neither JPEG nor PNG. A file that is an image but whose number the index already
 * uses gets the next free number, so nothing is ever overwritten. Adopted frames keep the order
 * they were written in, oldest first, and are marked as recovered because their filter and
 * dimensions are gone with the crash.
 *
 * An index line whose file is missing is dropped, as before. That is the case the write order
 * was chosen to make impossible, and if it happens anyway there is nothing to develop.
 */
object RollIndex {

    /** The frames the roll now has, the ones that were adopted, and the names to delete. */
    data class Reconciled(
        val entries: List<IndexEntry>,
        val adopted: List<IndexEntry>,
        val delete: List<String>,
        /** Index lines whose file was not there. */
        val dropped: Int,
    ) {
        /** True when the index on disk no longer matches [entries] and should be rewritten. */
        val changed: Boolean get() = adopted.isNotEmpty() || dropped > 0
    }

    /** The file that is the index itself, never a frame. */
    const val INDEX_NAME = "index.txt"

    /**
     * Reconcile the index with what is actually in the directory.
     *
     * [files] is every regular file in the roll's directory except the index. The result's
     * [Reconciled.entries] is the roll, in index order; [Reconciled.delete] is what to remove.
     */
    fun reconcile(entries: List<IndexEntry>, files: List<DiskFrame>): Reconciled {
        val onDisk = files.associateBy { it.name }
        // Known frames whose file exists. A line with no file is dropped, not repaired: there is
        // nothing to develop, and the write order means it should not happen.
        val kept = entries.filter { it.fileName != INDEX_NAME && it.fileName in onDisk }
        val dropped = entries.size - kept.size
        val known = kept.map { it.fileName }.toSet()
        val used = kept.map { it.index }.toMutableSet()

        val adopted = ArrayList<IndexEntry>()
        val delete = ArrayList<String>()
        // Oldest first by the clock, then by name for two files in the same millisecond — the
        // order they were written is the order they were taken.
        val strangers = files
            .filter { it.name != INDEX_NAME && it.name !in known }
            .sortedWith(compareBy<DiskFrame> { it.modifiedAt }.thenBy { it.name })
        for (file in strangers) {
            if (!looksLikeImage(file.head, file.size)) {
                delete += file.name
                continue
            }
            val fromName = frameNumber(file.name)
            val index = if (fromName != null && fromName !in used) fromName else nextFree(used)
            used += index
            adopted += IndexEntry(
                index = index,
                takenAt = file.modifiedAt,
                filterId = "",
                width = 0,
                height = 0,
                fileName = file.name,
                recovered = true,
            )
        }
        return Reconciled(
            entries = (kept + adopted).sortedBy { it.index },
            adopted = adopted,
            delete = delete,
            dropped = dropped,
        )
    }

    /**
     * A cheap header check: is this a JPEG or a PNG?
     *
     * Not a decode — a frame is several megabytes and this runs at launch for every file in the
     * directory. The magic bytes are enough to separate a photograph the index forgot from a
     * file the write never finished, which is the only distinction recovery has to make.
     */
    fun looksLikeImage(head: ByteArray, size: Long): Boolean {
        if (size <= 0L) return false
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        ) {
            return true
        }
        return head.size >= 8 && PNG_MAGIC.indices.all { head[it] == PNG_MAGIC[it] }
    }

    /** The number in `frame-07.jpg`, or null for a name that does not carry one. */
    fun frameNumber(name: String): Int? =
        FRAME_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /** One line of the index, as [FilmRoll] writes it. */
    fun formatLine(entry: IndexEntry): String {
        val columns = mutableListOf(
            entry.index.toString(),
            entry.takenAt.toString(),
            entry.filterId,
            entry.width.toString(),
            entry.height.toString(),
            entry.fileName,
        )
        if (entry.recovered) columns += RECOVERED
        return columns.joinToString("|")
    }

    /** One line back, or null for a line that does not parse. */
    fun parseLine(line: String): IndexEntry? {
        val parts = line.split("|")
        if (parts.size < 6) return null
        return IndexEntry(
            index = parts[0].toIntOrNull() ?: return null,
            takenAt = parts[1].toLongOrNull() ?: 0L,
            filterId = parts[2],
            width = parts[3].toIntOrNull() ?: 0,
            height = parts[4].toIntOrNull() ?: 0,
            fileName = parts[5],
            recovered = parts.size > 6 && parts[6] == RECOVERED,
        )
    }

    private fun nextFree(used: Set<Int>): Int = (used.maxOrNull() ?: 0) + 1

    private const val RECOVERED = "recovered"
    private val FRAME_NAME = Regex("""frame-(\d+)\.jpg""")
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
