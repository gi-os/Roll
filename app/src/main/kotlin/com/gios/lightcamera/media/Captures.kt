package com.gios.lightcamera.media

/**
 * The formats one press can write, and what each is for.
 *
 * **The order is the cycle order in the viewer**, so it runs from the file most people want to
 * look at to the one fewest do.
 */
enum class CaptureFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    /** The shareable one. What every other camera on the phone writes. */
    Jpeg("JPG", "jpg", "image/jpeg"),

    /**
     * **Lossless, and filtered on purpose.**
     *
     * This is not "JPEG but bigger". Half of Roll's filters are flat-colour — Dither BW, Dither 16,
     * Dither 32, Halftone, Game Boy — and flat colour beside hard edges is the exact signal JPEG's
     * chroma subsampling and ringing destroy: a dithered photograph saved as JPEG comes back with a
     * grey haze around every dot, and the dot pattern *is* the picture. PNG keeps the pixels the
     * shader produced.
     */
    Png("PNG", "png", "image/png"),

    /**
     * **The negative. Never filtered, and that is not a limitation.**
     *
     * A DNG is the sensor's own readout before demosaic, so there are no pixels to put a shader on
     * yet. Filtering one would mean developing it first, which is the thing a raw file exists to let
     * you do later and differently.
     */
    Dng("RAW", "dng", "image/x-adobe-dng"),
    ;

    /** RAW is the one format the shader pipeline has nothing to say about. */
    val filtered: Boolean get() = this != Dng

    companion object {
        fun byExtension(extension: String?): CaptureFormat? {
            val want = extension?.lowercase()?.removePrefix(".") ?: return null
            return entries.firstOrNull { it.extension == want }
        }

        /** `ROLL_20260827_143210_881.png` -> [Png]. Null for anything this app did not write. */
        fun ofFile(name: String?): CaptureFormat? =
            byExtension(name?.substringAfterLast('.', ""))
    }
}

/**
 * One file, reduced to the three things grouping cares about.
 *
 * **This is the seam that keeps grouping checkable.** [Photo] carries a `Uri`, and a `Uri` is
 * Android, and Android in a data class is a unit test that needs a device or a mocking framework
 * to run at all. `Frames.uprightFor` made the same split for the same reason: the part with no
 * platform in it is the part that can be proved on a JVM, and grouping is exactly the kind of
 * logic that fails without crashing — a stem parsed wrongly merges two photographs into one and
 * says nothing.
 */
data class Slot(
    val id: Long,
    val name: String,
    val isVideo: Boolean,
)

/** [Slot] plus its resolved format. Null format means a file this app did not write. */
data class PlannedMember(
    val slot: Slot,
    val format: CaptureFormat?,
)

/** One press, as plain data. */
data class Plan(
    val stem: String,
    val members: List<PlannedMember>,
)

/**
 * A single press, and every file it produced.
 *
 * [primary] is what the roll draws and what a tap opens: the best-looking representation that
 * exists, which is JPEG if there is one, then PNG, then the negative on its own.
 */
data class CaptureGroup(
    val stem: String,
    val members: List<CaptureMember>,
) {
    val primary: CaptureMember get() = members.first()

    /** True when there is anything to toggle between — the corner control hides otherwise. */
    val hasAlternatives: Boolean get() = members.size > 1

    fun formats(): List<CaptureFormat> = members.mapNotNull { it.format }

    /** The next representation round the ring, so one control can cycle all of them. */
    fun after(current: CaptureMember): CaptureMember {
        val at = members.indexOf(current)
        if (at < 0) return primary
        return members[(at + 1) % members.size]
    }
}

/**
 * One file inside a group.
 *
 * [format] is null for anything this app did not write in one of its own formats — a clip, a
 * screenshot, a download. Those are always groups of one, so there is nothing for the toggle to
 * cycle and nothing that needs naming.
 */
data class CaptureMember(
    val photo: Photo,
    val format: CaptureFormat?,
)

/**
 * Turning a flat list of files into one item per press.
 *
 * **Grouped by name, because there is nowhere else to put the relationship.** MediaStore has no
 * concept of "these three rows are one photograph", and this app already relies on that fact
 * elsewhere: a Purikura strip finds its four frames by matching `ROLL_<stamp>` too. A sidecar
 * database would be a second source of truth that the filesystem is free to contradict — delete a
 * DNG in Files, or restore a backup, and the index is wrong in a way nothing can detect. The name
 * cannot drift from the file, because it *is* the file.
 *
 * **The stamp carries milliseconds for exactly one reason.** Two presses inside the same second
 * would otherwise write the same stem and be merged into one photograph that never existed.
 * Files written before that change have no millisecond field, parse as their own stem, and group
 * as singletons — which is what they are.
 *
 * Anything that is not one of this app's own captures — a screenshot, a download, a photo from the
 * stock camera — has no recognised stem and comes back as a group of one. The roll is still every
 * photo on the phone.
 */
object Captures {

    private const val PREFIX = "ROLL_"

    /**
     * The part of a filename that identifies the press.
     *
     * Everything up to the extension, minus a trailing role suffix this app appends for its own
     * purposes (`_strip`, and `_1` to `_4` behind a booth print). Those suffixes mark *different
     * photographs* from the same visit, not different formats of one.
     */
    fun stemOf(name: String): String {
        val base = name.substringBeforeLast('.', name)
        if (!base.startsWith(PREFIX)) return base
        val parts = base.split('_')
        // ROLL_<date>_<time>[_<millis>][_<role>]
        if (parts.size < 3) return base
        val head = listOf(parts[0], parts[1], parts[2])
        val rest = parts.drop(3)
        // A pure three-digit field straight after the time is the millisecond, part of the
        // identity. Anything else is a role and belongs to a different photograph.
        val millis = rest.firstOrNull()?.takeIf { it.length == 3 && it.all(Char::isDigit) }
        return (head + listOfNotNull(millis)).joinToString("_")
    }

    /**
     * The grouping itself, with no Android in it.
     *
     * The incoming list is already sorted newest-first by the caller, and that order is preserved:
     * a group takes the position of its first member, so nothing jumps up the roll because a
     * negative happened to be written a few milliseconds after its JPEG.
     */
    fun plan(slots: List<Slot>): List<Plan> {
        val order = ArrayList<String>()
        val byStem = LinkedHashMap<String, MutableList<PlannedMember>>()
        slots.forEach { slot ->
            val stem = stemOf(slot.name)
            val format = CaptureFormat.ofFile(slot.name)
            // A clip is never part of a still's group even if the names line up, and a file with
            // an extension this app does not write is its own item.
            if (slot.isVideo || format == null) {
                val unique = "$stem#${slot.id}"
                order += unique
                byStem[unique] = mutableListOf(PlannedMember(slot, format))
                return@forEach
            }
            val bucket = byStem.getOrPut(stem) { order += stem; mutableListOf() }
            bucket += PlannedMember(slot, format)
        }
        return order.map { key ->
            Plan(
                stem = key.substringBefore('#'),
                members = byStem.getValue(key).sortedBy { it.format?.ordinal ?: Int.MAX_VALUE },
            )
        }
    }

    /** [plan], with the photos put back. */
    fun of(photos: List<Photo>): List<CaptureGroup> {
        val byId = photos.associateBy { it.id }
        return plan(photos.map { Slot(it.id, it.name, it.isVideo) }).map { planned ->
            CaptureGroup(
                stem = planned.stem,
                members = planned.members.mapNotNull { member ->
                    byId[member.slot.id]?.let { CaptureMember(it, member.format) }
                },
            )
        }.filter { it.members.isNotEmpty() }
    }
}
