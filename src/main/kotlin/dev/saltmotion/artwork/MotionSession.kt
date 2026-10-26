package dev.saltmotion.artwork

import java.util.concurrent.atomic.AtomicBoolean

internal data class PixelFrame(val width: Int, val height: Int, val bgra: ByteArray)
internal interface FrameDecoder : AutoCloseable {
    fun read(): PixelFrame?
    fun readImage(): org.jetbrains.skia.Image? = read()?.let { frame ->
        org.jetbrains.skia.Image.makeRaster(org.jetbrains.skia.ImageInfo(frame.width, frame.height,
            org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE), frame.bgra, frame.width * 4)
    }
}

/** A close during native initialization retires the late decoder on its own IO thread. */
internal class MotionSession(
    private val factory: (String) -> FrameDecoder,
    private val retire: (() -> Unit) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val opened = AtomicBoolean(false)
    private var decoder: FrameDecoder? = null
    private var closed = false

    fun open(url: String) {
        check(opened.compareAndSet(false, true)) { "Session already opened" }
        synchronized(lock) { if (closed) return }
        val created = factory(url)
        val accepted = synchronized(lock) {
            if (closed) false else { decoder = created; true }
        }
        if (!accepted) created.close()
    }

    fun read(): PixelFrame? = synchronized(lock) { if (closed) null else decoder }?.read()
    fun readImage(): org.jetbrains.skia.Image? = synchronized(lock) { if (closed) null else decoder }?.readImage()

    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            decoder.also { decoder = null }
        }
        previous?.let { value -> retire { value.close() } }
    }
}
