package dev.saltmotion.artwork

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class NativeFrames(url: String, width: Int, height: Int) : FrameDecoder {
    private val frames: SoftwareRenderFrames
    init {
        ensureHostMpv()
        frames = SoftwareRenderFrames(url, width, height)
    }
    override fun read(): PixelFrame? = frames.read()?.let { PixelFrame(it.width(), it.height(), it.bgra()) }
    override fun readImage(): org.jetbrains.skia.Image? = frames.readImage()
    override fun close() = frames.close()

    companion object {
        private var loaded = false
        @Synchronized private fun ensureHostMpv() {
            if (loaded) return
            // Ask the host loader to expose its bundled DLL; never create a video Surface.
            val factory = Class.forName("org.openani.mediamp.mpv.MpvMediampPlayerFactory").getConstructor().newInstance()
            val player = factory.javaClass.getMethod("create", Any::class.java, CoroutineContext::class.java)
                .invoke(factory, Unit, EmptyCoroutineContext)
            (player as AutoCloseable).close()
            loaded = true
        }
    }
}
