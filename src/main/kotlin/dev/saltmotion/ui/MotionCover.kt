package dev.saltmotion.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalWindowInfo
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltmotion.MotionRuntime
import dev.saltmotion.MotionSettings
import dev.saltmotion.PluginLog
import dev.saltmotion.am.AlbumKey
import dev.saltmotion.artwork.MotionSession
import dev.saltmotion.artwork.OwnedMotionFrame
import dev.saltmotion.artwork.FrameSchedule
import dev.saltmotion.artwork.FrameRateStats
import dev.saltmotion.editor.CoverStudio
import dev.saltmotion.editor.SavedCover
import dev.saltmotion.editor.rasterImage
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

private fun currentTrack(log: PluginLog? = null): MediaItem? = runCatching {
    WorkshopApi.playback.getCurrentMediaItem()
}.onFailure { error -> log?.logOnce("metadata-error", "Playback metadata unavailable: ${error.javaClass.name}: ${error.message}") }.getOrNull()

private fun albumOf(item: MediaItem?, cover: SavedCover?): AlbumKey? = item?.let {
    CoverStudio.effectiveAlbum(it.albumArtist.ifBlank { it.artist }, it.album, cover?.motionRef)
}

/** The native Image keeps its own layout, crop, rotation and clip. */
internal class MotionCoverElement(private val runtime: MotionRuntime) : ModifierNodeElement<MotionCoverNode>() {
    override fun create() = MotionCoverNode(runtime)
    override fun update(node: MotionCoverNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "SaltMotionCover" }
    override fun equals(other: Any?) = other is MotionCoverElement && other.runtime === runtime
    override fun hashCode() = System.identityHashCode(runtime)
}

internal class MotionCoverNode(private val runtime: MotionRuntime) : Modifier.Node(),
    DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val lock = Any()
    private var frames = OwnedMotionFrame<org.jetbrains.skia.Image>()
    private val revision = mutableIntStateOf(0)
    private val session = AtomicReference<MotionSession?>(null)
    private var generation = 0L
    private var attached = false
    private var drawingAlbum: AlbumKey? = null
    private var loading: Job? = null
    private var watching: Job? = null
    private var frameRates: FrameRateStats? = null
    // Poster ownership stays on the node dispatcher, independently of the animation session.
    private var posterName: String? = null
    private var posterImage: Image? = null
    private var posterLoad: Job? = null

    override fun onAttach() {
        runtime.log.log("Cover node attached")
        synchronized(lock) { attached = true; frames = OwnedMotionFrame() }
        watching = coroutineScope.launch {
            var request: Pair<AlbumKey, MotionSettings>? = null
            var retryAt = 0L
            var waiting: String? = null
            while (isActive && runtime.active) {
                val track = currentTrack(runtime.log)
                val saved = track?.let { runtime.studio?.store?.find(it.path) }
                updatePoster(saved)
                val album = albumOf(track, saved)
                val options = when {
                    saved == null -> runtime.settings
                    saved.motionRef == null || album == null -> runtime.settings.copy(enabled = false)
                    else -> runtime.settings.copy(bindings = "${album.artist} | ${album.album} = ${saved.motionRef}")
                }
                val focused = currentValueOf(LocalWindowInfo).isWindowFocused
                val reason = when {
                    !options.enabled -> "disabled"
                    !focused -> "window unfocused"
                    album == null -> "no valid album tags"
                    else -> null
                }
                if (reason != waiting) {
                    waiting = reason
                    if (reason != null) runtime.log.log("Cover waiting: $reason")
                }
                val next = if (options.enabled && focused && album != null)
                    album to options else null
                if (next != request || (next != null && loading?.isCompleted == true && System.currentTimeMillis() >= retryAt)) {
                    cancelRequest()
                    request = next
                    retryAt = System.currentTimeMillis() + 60_000L
                    if (next != null) {
                        val ticket = synchronized(lock) { generation }
                        loading = coroutineScope.launch { load(next.first, next.second, ticket) }
                    }
                }
                delay(350)
            }
            cancelRequest()
        }
    }

    private fun updatePoster(saved: SavedCover?) {
        if (posterName == saved?.poster) return
        posterLoad?.cancel()
        posterImage?.close()
        posterImage = null
        posterName = saved?.poster
        revision.intValue++
        val studio = runtime.studio ?: return
        if (saved == null) return
        posterLoad = coroutineScope.launch {
            var opened: Image? = null
            try {
                withContext(Dispatchers.IO) {
                    opened = rasterImage(Files.readAllBytes(studio.store.poster(saved)), 1600)
                }
                ensureActive()
                if (posterName == saved.poster && runtime.active) {
                    posterImage = opened
                    opened = null
                    revision.intValue++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtime.log.logOnce("poster-${saved.poster}", "Custom playback poster unavailable: ${error.javaClass.simpleName}")
            } finally {
                opened?.close()
            }
        }
    }

    private fun cancelRequest() {
        synchronized(lock) {
            generation++
            drawingAlbum = null
            frameRates = null
            frames.clear()
            revision.intValue++
        }
        loading?.cancel()
        loading = null
        session.getAndSet(null)?.let(runtime::remove)
    }

    private suspend fun load(album: AlbumKey, options: MotionSettings, ticket: Long) {
        runtime.log.log("Loading album=${album.artist} | ${album.album}; manual=${options.binding(album).first}")
        var opened: MotionSession? = null
        try {
            withContext(Dispatchers.IO) {
                val prepared = runtime.prepare(album, options) ?: run {
                    runtime.log.log("No motion cover resolved for this album")
                    return@withContext
                }
                ensureActive()
                val motion = prepared.motion
                val stream = prepared.stream
                runtime.log.log("Cover source=${stream.width}x${stream.height} codec=${stream.codec} bitrate=${stream.bandwidth} fps=${stream.framesPerSecond ?: "unknown"}; target=${options.framesPerSecond}")
                ensureActive()
                val worker = runtime.newSession(stream) ?: return@withContext
                opened = worker
                val accepted = synchronized(lock) {
                    if (!attached || generation != ticket) false else { session.set(worker); true }
                }
                if (!accepted) return@withContext
                worker.open(stream.url)
                val deadline = System.nanoTime() + 15_000_000_000L
                var schedule: FrameSchedule? = null
                var received = false
                while (isActive && runtime.active) {
                    val image = worker.readImage()
                    if (image != null) {
                        var handedOff = false
                        try {
                        // Fetch the next native frame immediately, then pace its publication.
                        // Pacing the next read instead can miss a native frame boundary.
                        schedule?.let {
                            val remaining = it.delayNanos(System.nanoTime())
                            if (remaining > 0) delay((remaining + 999_999L) / 1_000_000L)
                        }
                        synchronized(lock) {
                            if (attached && generation == ticket) {
                                drawingAlbum = album
                                handedOff = true // replace owns and closes the image even when it rejects it.
                                if (frames.replace(image)) {
                                    if (!received) {
                                        runtime.log.log("First cover frame ready album=${motion.albumId}; target=${options.framesPerSecond}")
                                        frameRates = FrameRateStats(options.framesPerSecond, System.nanoTime())
                                    }
                                    received = true
                                    frameRates?.submitted()
                                    frameRates?.sample(System.nanoTime())?.let { rate ->
                                        runtime.log.log(String.format(Locale.ROOT,
                                            "Cover fps target=%d submitted=%.2f drawn=%.2f", rate.target, rate.submitted, rate.drawn))
                                    }
                                    revision.intValue++
                                }
                            }
                        }
                        } finally { if (!handedOff) image.close() }
                        if (schedule == null) schedule = FrameSchedule(options.framesPerSecond, System.nanoTime())
                    } else {
                        if (!received && System.nanoTime() > deadline) error("No decoded cover frames")
                        delay(10)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            runtime.log.log("Motion decoding failed; local cover retained: " + error.javaClass.simpleName)
        } catch (error: LinkageError) {
            runtime.log.log("Host native runtime unavailable; local cover retained: " + error.javaClass.simpleName)
        } finally {
            opened?.let { session.compareAndSet(it, null); runtime.remove(it) }
            synchronized(lock) {
                if (attached && generation == ticket) {
                    drawingAlbum = null
                    frameRates = null
                    frames.clear()
                    revision.intValue++
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        runtime.log.logOnce("draw", "Cover node draw reached")
        revision.intValue
        if (runtime.active) {
            val track = currentTrack()
            val saved = track?.let { runtime.studio?.store?.find(it.path) }
            val canAnimate = (saved == null || saved.motionRef != null) && runtime.settings.enabled &&
                currentValueOf(LocalWindowInfo).isWindowFocused
            val rendered = if (canAnimate) synchronized(lock) {
                val album = drawingAlbum
                if (album == null || album.normalized != albumOf(track, saved)?.normalized) false
                else frames.draw { image ->
                    drawContent()
                    frameRates?.drawn()
                    runtime.log.logOnce("paint-${album.normalized}", "Animated cover draw reached")
                    drawCoverImage(image)
                }
            } else false
            if (rendered) return
            val poster = posterImage
            if (saved != null && saved.poster == posterName && poster != null) {
                runtime.log.logOnce("static-playback", "Custom static playback cover drawn")
                drawCoverImage(poster)
                return
            }
        }
        drawContent()
    }

    private fun ContentDrawScope.drawCoverImage(image: Image) {
        val scale = maxOf(size.width / image.width, size.height / image.height)
        val width = image.width * scale
        val height = image.height * scale
        clipRect {
            drawIntoCanvas {
                it.skiaCanvas.drawImageRect(image, Rect.makeLTRB(
                    (size.width - width) / 2, (size.height - height) / 2,
                    (size.width + width) / 2, (size.height + height) / 2,
                ))
            }
        }
    }

    override fun onDetach() {
        synchronized(lock) { attached = false; generation++; drawingAlbum = null; frames.close() }
        loading?.cancel(); watching?.cancel(); posterLoad?.cancel()
        posterImage?.close()
        posterImage = null
        posterName = null
        session.getAndSet(null)?.let(runtime::remove)
    }
}
