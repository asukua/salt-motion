package dev.saltmotion.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import dev.saltmotion.artwork.MotionSession
import dev.saltmotion.artwork.OwnedMotionFrame
import kotlinx.coroutines.*
import org.jetbrains.skia.Rect

/** Only the selected card plays. The cached source keeps full quality; the editor preview renders at 512. */
@Composable internal fun AnimatedCoverPreview(candidate:CoverCandidate,studio:CoverStudio,track:SelectedTrack,modifier:Modifier) {
    val frames=remember(candidate.key){OwnedMotionFrame<org.jetbrains.skia.Image>()}
    var revision by remember(candidate.key){mutableIntStateOf(0)}
    DisposableEffect(candidate.key){onDispose{frames.close()}}
    LaunchedEffect(candidate.key){
        var opened:MotionSession?=null
        try {
            withContext(Dispatchers.IO){
                val album=CoverStudio.effectiveAlbum(candidate.artist,candidate.album,candidate.motionRef)?:return@withContext
                val options=studio.runtime.settings.copy(bindings="${album.artist} | ${album.album} = ${candidate.motionRef}")
                val prepared=studio.runtime.prepare(album,options)?:return@withContext
                ensureActive()
                val session=studio.runtime.newSession(prepared.stream.copy(width=512,height=512))?:return@withContext
                opened=session;session.open(prepared.stream.url)
                while(isActive&&studio.runtime.active){
                    val frame=session.readImage()
                    if(frame!=null){frames.replace(frame);revision++}else delay(10)
                }
            }
        }catch(c:CancellationException){throw c}
        catch(e:Exception){studio.log.log("Cover preview unavailable: ${e.javaClass.simpleName}")}
        finally{opened?.let(studio.runtime::remove);frames.clear()}
    }
    Canvas(modifier){revision;frames.draw{frame->drawIntoCanvas{it.skiaCanvas.drawImageRect(frame,Rect.makeWH(size.width,size.height))}}}
}
