package dev.saltmotion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.saltmotion.am.AlbumCatalog
import dev.saltmotion.am.AppleHttp
import dev.saltmotion.artwork.MotionSession
import dev.saltmotion.artwork.NativeFrames
import dev.saltmotion.artwork.DecoderPool
import dev.saltmotion.artwork.HlsVariants
import dev.saltmotion.artwork.MotionVideoCache
import dev.saltmotion.artwork.PreparedMotion
import dev.saltmotion.am.AlbumKey
import java.nio.file.Path
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Executors

internal class MotionRuntime @JvmOverloads constructor(val log: PluginLog, initial: MotionSettings, cacheRoot: Path? = null) : AutoCloseable {
    var active by mutableStateOf(true)
        private set
    var settings by mutableStateOf(initial)
        private set
    val http = AppleHttp()
    val catalog = AlbumCatalog(http, log)
    val studio = cacheRoot?.parent?.let { dev.saltmotion.editor.CoverStudio(it, this, log, catalog) }
    private val cache = cacheRoot?.let { MotionVideoCache(it, http, http::download, log) }
    private val sessions = mutableSetOf<MotionSession>()
    private val decoders = DecoderPool(2)
    private val cleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SaltMotion-dispose").apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getPlatformClassLoader()
        }
    }

    @Synchronized fun update(next: MotionSettings) {
        if (active && settings != next) settings = next
    }

    suspend fun prepare(album: AlbumKey, options: MotionSettings): PreparedMotion? = runInterruptible {
        cache?.lookup(album, options)?.let { return@runInterruptible it }
        val motion = catalog.resolve(album, options) ?: return@runInterruptible null
        val stream = HlsVariants.resolveStream(motion.videoUrl, http)
        try { cache?.store(album, options, motion, stream) ?: PreparedMotion(motion, stream, false) }
        catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw error
            log.log("Video cache unavailable; streaming album=${motion.albumId}: ${error.javaClass.simpleName}")
            PreparedMotion(motion, stream, false)
        }
    }

    @Synchronized fun newSession(stream: HlsVariants.Stream): MotionSession? {
        if (!active) return null
        return MotionSession({ url -> decoders.open { NativeFrames(url, stream.width, stream.height) } }) {
            cleanup.execute(it)
        }.also(sessions::add)
    }

    @Synchronized fun remove(session: MotionSession) {
        session.close()
        sessions.remove(session)
    }

    @Synchronized override fun close() {
        if (!active) return
        active = false
        studio?.close()
        cache?.close()
        decoders.close()
        sessions.forEach(MotionSession::close)
        sessions.clear()
        cleanup.shutdown()
        catalog.close()
    }
}
