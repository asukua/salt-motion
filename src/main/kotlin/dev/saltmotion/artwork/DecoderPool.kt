package dev.saltmotion.artwork

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reserve decoder slots on the IO thread, after old composition effects can retire. */
internal class DecoderPool(limit: Int) : AutoCloseable {
    private val permits = Semaphore(limit, true)
    private val closed = AtomicBoolean(false)
    fun open(factory: () -> FrameDecoder): FrameDecoder {
        check(!closed.get())
        check(permits.tryAcquire(10, TimeUnit.SECONDS)) { "Motion decoder budget exhausted" }
        try {
            check(!closed.get())
            val delegate = factory()
            if (closed.get()) { delegate.close(); error("Motion runtime stopped during initialization") }
            return object : FrameDecoder {
                private val retired = AtomicBoolean(false)
                override fun read(): PixelFrame? = if (retired.get()) null else delegate.read()
                override fun readImage(): org.jetbrains.skia.Image? = if (retired.get()) null else delegate.readImage()
                override fun close() {
                    if (!retired.compareAndSet(false, true)) return
                    try { delegate.close() } finally { permits.release() }
                }
            }
        } catch (error: Throwable) { permits.release(); throw error }
    }
    override fun close() { closed.set(true) }
}
