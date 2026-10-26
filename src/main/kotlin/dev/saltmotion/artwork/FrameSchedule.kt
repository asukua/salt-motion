package dev.saltmotion.artwork

/** Monotonic deadlines include decoding/upload time; stalls do not cause catch-up bursts. */
internal class FrameSchedule(framesPerSecond: Int, startNanos: Long) {
    init { require(framesPerSecond == 24 || framesPerSecond == 30) }
    private val frequency = framesPerSecond
    private val period = 1_000_000_000L / frequency
    private val remainder = 1_000_000_000L % frequency
    private var deadline = startNanos
    private var fraction = 0L

    fun delayNanos(nowNanos: Long): Long {
        deadline += period
        fraction += remainder
        if (fraction >= frequency) { deadline++; fraction -= frequency }
        if (nowNanos - deadline >= period) {
            deadline = nowNanos
            fraction = 0L
        }
        return (deadline - nowNanos).coerceAtLeast(0L)
    }
}
