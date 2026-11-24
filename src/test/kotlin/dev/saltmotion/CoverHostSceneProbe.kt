@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.runtime.InternalComposeApi::class)
package dev.saltmotion

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltmotion.editor.CoverCandidate
import dev.saltmotion.editor.CoverSource
import dev.saltmotion.editor.SelectedTrack
import dev.saltmotion.ui.MotionCoverElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.awt.EventQueue
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

/** Invokes the actual playback cover in an isolated scene and checks the pixels after an editor apply. */
object CoverHostSceneProbe {
    @JvmStatic fun render(caller: String, output: String, attach: Consumer<Modifier.Element>) {
        val method=Class.forName(caller).declaredMethods.single { m ->
            m.name=="\u037f" && m.parameterCount==5 && m.parameterTypes[0].name=="androidx.compose.ui.graphics.painter.Painter"
        }.apply { isAccessible=true }

        val fixture = Files.createTempDirectory(Path.of(output).toAbsolutePath().parent, "live-cover-")
        val track = SelectedTrack("sample", "Sample", "Artist", "Album", "Artist", "file:///D:/static test.flac")
        var currentPath = track.path
        val playback = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi.Playback::class.java)) { _, m, _ ->
            check(m.name == "getCurrentMediaItem")
            MediaItem(track.title, track.artist, track.album, track.albumArtist, currentPath)
        }
        val api = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi::class.java)) { _, m, _ ->
            check(m.name == "getPlayback")
            playback
        }
        Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi\$Companion").getField("instance").set(null, api)

        val poster = fixture.resolve("replacement.png")
        Surface.makeRasterN32Premul(160, 160).use { surface ->
            surface.canvas.clear(0xffdd4411.toInt())
            surface.makeImageSnapshot().use { snapshot ->
                snapshot.encodeToData(EncodedImageFormat.PNG)!!.use { data -> Files.write(poster, data.bytes) }
            }
        }

        MotionRuntime(PluginLog(null), MotionSettings(enabled = false), fixture.resolve("motion-cache")).use { runtime ->
            attach.accept(MotionCoverElement(runtime))
            val studio = checkNotNull(runtime.studio)
            lateinit var scene: ImageComposeScene
            val ui = object : CoroutineDispatcher() {
                override fun isDispatchNeeded(context: CoroutineContext) = !EventQueue.isDispatchThread()
                override fun dispatch(context: CoroutineContext, block: Runnable) = EventQueue.invokeLater(block)
            }
            EventQueue.invokeAndWait {
                scene = ImageComposeScene(300,300,Density(1f),LayoutDirection.Ltr,ui) {
                    val painter = remember { object : Painter() {
                        override val intrinsicSize = Size(256f,256f)
                        override fun DrawScope.onDraw() { drawRect(Color(0xff7899aa)) }
                    } }
                    val color: ()->Color = { Color.Transparent }
                    val corner: ()->Dp = { 12.dp }
                    method.invoke(null, painter, color, corner, currentComposer, 0)
                }
            }
            fun waitForColor(expected: Int, capture: Boolean = false) {
                repeat(60) {
                    var matched = false
                    EventQueue.invokeAndWait {
                        scene.render(System.nanoTime()).use { frame ->
                            Bitmap.makeFromImage(frame).use { matched = it.getColor(150, 150) == expected }
                            if (matched && capture) {
                                frame.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(Path.of(output),it.bytes) }
                            }
                        }
                    }
                    if (matched) return
                    Thread.sleep(40)
                }
                error("Playback cover did not switch to " + expected.toUInt().toString(16))
            }
            try {
                waitForColor(0xff7899aa.toInt())
                val candidate = CoverCandidate(CoverSource.NETEASE, "fixture", track.album, track.artist, "", "", localFile = poster)
                check(studio.apply(track, false, candidate, false) == 1)
                waitForColor(0xffdd4411.toInt(), capture = true)
                val reopenedStore = dev.saltmotion.editor.CoverStore(studio.store.root, runtime.log)
                check(reopenedStore.find(track.path) == studio.store.find(track.path))
                EventQueue.invokeAndWait { currentPath = "file:///D:/different track.flac" }
                waitForColor(0xff7899aa.toInt())
                EventQueue.invokeAndWait { currentPath = track.path }
                waitForColor(0xffdd4411.toInt())
                studio.store.reset(listOf(track.path))
                waitForColor(0xff7899aa.toInt())
                println("PASS saved static cover in actual host playback; same-album track switch, persistence and reset")
            } finally {
                EventQueue.invokeAndWait { scene.close() }
            }
        }
    }
}
