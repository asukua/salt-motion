package dev.saltmotion.editor

import androidx.compose.runtime.Composable
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import kotlin.Unit

internal object EditorHooks {
    val coilParameters = listOf("kotlin.jvm.functions.Function0","androidx.compose.ui.Modifier","kotlin.jvm.functions.Function2",
        "androidx.compose.ui.\u0552","androidx.compose.ui.\u1050","kotlin.jvm.functions.Function1","androidx.compose.ui.graphics.painter.Painter",
        "kotlin.jvm.functions.Function4","kotlin.jvm.functions.Function5","kotlin.jvm.functions.Function4")
    fun install(studio: CoverStudio): List<HookHandle> {
        HostUiBridge.verify()
        val installed = mutableListOf<HookHandle>()
        try {
            installed += WorkshopApi.hookRegistrar.hookMethod(HostUiBridge.SCOPE_IMPL,"\u037f",
                listOf("kotlin.jvm.functions.Function0","boolean","kotlin.jvm.functions.Function2"), object : MethodHook() {
                    override fun after(call: MethodHookParam) {
                        if (call.throwable != null || !studio.runtime.active || !HostUiBridge.afterAlbumCall()) return
                        runCatching {
                            val track = HostUiBridge.selectedTrack(call.args[0] ?: return) ?: return
                            val selection = SelectedTrack.fromHost(track)
                            // Reuse the native scope so dismissal, hover, keyboard focus and row size match other actions.
                            val body = menuBody()
                            HostUiBridge.addMenuItem(call.thisObject ?: return, { studio.open(selection); Unit },body)
                            studio.log.logOnce("editor-menu","Cover editor menu item inserted after album")
                        }.onFailure { studio.log.log("Cover editor menu failed: ${it.javaClass.simpleName}") }
                    }
                })
            installed += WorkshopApi.hookRegistrar.afterComposable(HostUiBridge.ROOT_CONTENT,"\u037f",HostUiBridge.ROOT,
                listOf("boolean"),50,object:ComposableHook(){@Composable override fun Content(call: UiHookCall) { CoverStudioHost(studio) }})
            installed += WorkshopApi.hookRegistrar.replaceComposable(HostUiBridge.COIL,"\u037f",HostUiBridge.AUDIO,
                coilParameters,50,object:ComposableHook(){@Composable override fun Content(call: UiHookCall) { CustomCoverImage(call,studio) }})
            return installed
        } catch(error: Throwable) { installed.forEach { runCatching { it.unhook() } }; throw error }
    }
    @Suppress("UNCHECKED_CAST")
    private fun menuBody(): (androidx.compose.runtime.Composer,Int)->Unit {
        val body: @Composable ()->Unit = { NativeCoverMenuLabel() }
        return body as (androidx.compose.runtime.Composer,Int)->Unit
    }
}
