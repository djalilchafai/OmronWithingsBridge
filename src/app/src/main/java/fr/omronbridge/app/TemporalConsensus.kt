package fr.omronbridge.app

/** Small rolling consensus for one numerical field in the live CameraX view. */
class TemporalConsensus(
    private val window: Int = 5,
    private val required: Int = 3,
) {
    data class State(
        val candidate: Int?,
        val stable: Boolean,
        val votes: Int,
        val seen: Int,
    )

    private val values = ArrayDeque<Int?>()

    fun push(value: Int?): State {
        values.addLast(value)
        while (values.size > window) values.removeFirst()

        val counts = HashMap<Int, Int>()
        for (v in values) {
            if (v != null) counts[v] = (counts[v] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }
            ?: return State(null, false, 0, values.size)

        return State(
            candidate = best.key,
            stable = best.value >= required,
            votes = best.value,
            seen = values.size,
        )
    }

    fun clear() = values.clear()
}
