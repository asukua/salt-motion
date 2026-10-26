package dev.saltmotion.artwork

/** Calls are protected by the cover node lock. Repeated draws of one submitted frame count once. */
internal class FrameRateStats(private val target: Int, startNanos: Long) {
    data class Sample(val target: Int, val submitted: Double, val drawn: Double)
    private var start = startNanos
    private var sequence = 0L
    private var lastDrawn = 0L
    private var submitted = 0
    private var drawn = 0

    fun submitted() { sequence++; submitted++ }
    fun drawn() {
        if (sequence != lastDrawn) { lastDrawn = sequence; drawn++ }
    }
    fun sample(nowNanos: Long): Sample? {
        val elapsed = nowNanos - start
        if (elapsed < 10_000_000_000L) return null
        val seconds = elapsed / 1_000_000_000.0
        val result = Sample(target, submitted / seconds, drawn / seconds)
        start = nowNanos
        submitted = 0; drawn = 0
        return result
    }
}
