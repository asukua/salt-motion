@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.runtime.InternalComposeApi::class)
package dev.saltmotion

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path

/** Invokes the real host cover in a private offscreen Compose scene, not the user's running app. */
object CoverHostSceneProbe {
    @JvmStatic fun render(caller: String, output: String) {
        val clazz=Class.forName(caller)
        val method=clazz.declaredMethods.single { m -> m.name=="\u037f" && m.parameterCount==5 && m.parameterTypes[0].name=="androidx.compose.ui.graphics.painter.Painter" }
        method.isAccessible=true
        var scene:ImageComposeScene?=null
        EventQueue.invokeAndWait {
            scene=ImageComposeScene(300,300,Density(1f),LayoutDirection.Ltr,Dispatchers.Unconfined) {
                val painter=remember { object : Painter() {
                    override val intrinsicSize = Size(256f,256f)
                    override fun DrawScope.onDraw() { drawRect(Color(0xff7899aa)) }
                } }
                val color:()->Color={Color.White}; val corner:()->Dp={12.dp}
                method.invoke(null,painter,color,corner,currentComposer,0)
            }
        }
        repeat(6){Thread.sleep(50);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
        EventQueue.invokeAndWait {
            scene!!.render(System.nanoTime()).use { frame -> frame.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(Path.of(output),it.bytes) } }
            scene!!.close()
        }
    }
}
