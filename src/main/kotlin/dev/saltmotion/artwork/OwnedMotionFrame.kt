package dev.saltmotion.artwork

/** Replacement, drawing and disposal share a lock; never free a raster during a draw. */
internal class OwnedMotionFrame<T : AutoCloseable> : AutoCloseable {
    private var current: T? = null
    private var closed = false
    @Synchronized fun replace(frame: T): Boolean {
        if (closed) { frame.close(); return false }
        val previous = current
        current = frame
        previous?.close()
        return true
    }
    @Synchronized fun draw(draw: (T) -> Unit): Boolean {
        val frame = current ?: return false
        draw(frame)
        return true
    }
    @Synchronized fun clear() {
        current?.close()
        current = null
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        current?.close()
        current = null
    }
}
