package com.gios.lightcamera.camera

/**
 * The frames that had already arrived when the button was pressed.
 *
 * **The whole idea is that the interesting frame is usually behind you.** A press happens after
 * the thing that caused it: you see the expression, then you decide, then your thumb moves, and by
 * then it is a third of a second later and gone. A ring of recent frames means the shutter can
 * reach backwards — not as a trick, but because that is where the photograph was.
 *
 * Roll already keeps the *sharpest of eight*, but it takes those eight after the press, which
 * spends a quarter of a second getting them and cannot reach anything earlier than the press. This
 * is the version that costs nothing at the shutter, and the trade it makes instead is power: the
 * frames have to be arriving all the time for any of them to be there when you press.
 *
 * The buffer holds decoded frames, so it is a real memory cost and the size is deliberately small.
 */
class Ring<T>(val capacity: Int, private val onEvict: (T) -> Unit = {}) {

    private val items = ArrayDeque<Entry<T>>()

    class Entry<T>(val value: T, val elapsedMs: Long)

    val size: Int get() = items.size

    /**
     * Add a frame, dropping the oldest if the ring is full.
     *
     * [onEvict] is how the bitmap gets recycled. A ring of eight panel-sized frames is most of a
     * hundred megabytes, and leaving that to the collector is an out-of-memory two shots later.
     */
    fun add(value: T, elapsedMs: Long) {
        if (capacity <= 0) {
            onEvict(value)
            return
        }
        items.addLast(Entry(value, elapsedMs))
        while (items.size > capacity) {
            items.removeFirst().let { onEvict(it.value) }
        }
    }

    fun clear() {
        while (items.isNotEmpty()) onEvict(items.removeFirst().value)
    }

    fun entries(): List<Entry<T>> = items.toList()

    /**
     * The frame nearest a moment [preRollMs] before the press.
     *
     * **Nearest, not "the first one older than".** A ring that has not filled yet, or a stream that
     * stuttered, can leave the requested moment before anything in the buffer — and the honest
     * answer there is the oldest frame held rather than nothing at all. A pre-roll that
     * occasionally returns no photograph is a pre-roll nobody leaves switched on.
     */
    fun nearest(pressedAtMs: Long, preRollMs: Long): T? {
        if (items.isEmpty()) return null
        val target = pressedAtMs - preRollMs
        return items.minByOrNull { kotlin.math.abs(it.elapsedMs - target) }?.value
    }

    /** The newest frame held, which is what a pre-roll of zero means. */
    fun newest(): T? = items.lastOrNull()?.value

    /**
     * Pick with a score, highest wins, and hand back everything else to [onEvict].
     *
     * This is where sharpest-of-ring happens. The losers are released here rather than left in the
     * ring, because the caller is about to encode the winner and the peak is what kills this.
     */
    fun takeBest(score: (T) -> Float): T? {
        if (items.isEmpty()) return null
        var best: Entry<T>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        items.forEach { entry ->
            val value = score(entry.value)
            if (value > bestScore) {
                bestScore = value
                best = entry
            }
        }
        val winner = best
        items.forEach { if (it !== winner) onEvict(it.value) }
        items.clear()
        return winner?.value
    }

    companion object {
        /**
         * Eight frames, which at roughly thirty a second is about a quarter of a second of reach.
         *
         * Longer would be a better pre-roll and a worse camera: these are decoded frames, and the
         * point at which the ring costs a photograph elsewhere is not far past this.
         */
        const val DEFAULT_CAPACITY = 8

        /** The longest reach offered. Past this the frame is of a different moment entirely. */
        const val MAX_PRE_ROLL_MS = 250L
    }
}
