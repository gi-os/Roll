package com.gios.lightcamera.drop

/**
 * What the computer is allowed to see.
 *
 * **The server used to serve the whole roll, and only the whole roll.** Which was fine for the
 * job it was built for — pulling a shoot off the phone — and wrong for the more common one:
 * three photographs from lunch, wanted on the laptop, with the rest of the roll not on offer.
 * There was no way to say so. Every route resolved an id against the list the roll was showing,
 * so anything on the roll was a click away for anyone with the PIN.
 *
 * So the scope is chosen, out loud, on the screen that starts it: the photographs you had
 * selected, or the entire roll. Neither is a default. A whole roll served because nothing was
 * selected is the thing this exists to stop.
 *
 * The scope is checked where the routes resolve ids, the same place the "ids not paths" rule is
 * enforced, so a photograph outside it is a 404 rather than a filtered page — the thumbnail and
 * the file are as unreachable as the listing.
 */
sealed interface DropScope {

    /** Every photograph and clip the roll is showing. */
    data object WholeRoll : DropScope

    /** The ones that were selected when the server was started, by MediaStore row id. */
    data class Selected(val ids: Set<Long>) : DropScope

    /**
     * Whether a group — one press, every file it wrote — is on offer.
     *
     * A group is in a selection if any of its files is: the roll shows one photograph per group,
     * so selecting the JPEG is selecting the press, negative and lossless copy included.
     */
    fun allows(primaryId: Long, memberIds: Collection<Long>): Boolean = when (this) {
        WholeRoll -> true
        is Selected -> primaryId in ids || memberIds.any { it in ids }
    }

    /** How many are on offer, for the screen. Null for the whole roll, which has no fixed count. */
    val count: Int?
        get() = when (this) {
            WholeRoll -> null
            is Selected -> ids.size
        }

    /** What the phone says it is sending: "the entire roll", "1 selected photo", "3 selected photos". */
    fun label(): String = when (this) {
        WholeRoll -> "the entire roll"
        is Selected -> if (ids.size == 1) "1 selected photo" else "${ids.size} selected photos"
    }
}
