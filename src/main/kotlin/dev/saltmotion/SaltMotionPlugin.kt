package dev.saltmotion

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import com.xuncorp.spw.workshop.api.hook.HookHandle
import dev.saltmotion.hook.CoverTarget
import dev.saltmotion.hook.CoverModifier
import dev.saltmotion.ui.MotionCoverElement
import androidx.compose.ui.Modifier
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.util.function.Consumer

class SaltMotionPlugin(context: PluginContext) : SpwPlugin(context) {
    private var runtime: MotionRuntime? = null
    private var manager: ConfigManager? = null
    private var listener: Consumer<ConfigHelper>? = null
    private val hooks = mutableListOf<HookHandle>()

    @Synchronized override fun start() {
        if (runtime != null) return
        val loader = WorkshopApi::class.java.classLoader
        val target = try { CoverTarget.discover(loader) }
            catch (_: Exception) { null }
            catch (_: LinkageError) { null }
        try {
            val created = WorkshopApi.manager.createConfigManager()
            manager = created
            val config = created.getConfig("motion.json")
            val log = PluginLog(config.getConfigPath().parent.resolve("diagnostics.log"))
            try {
                // The host protects current playback metadata with LIBRARY_READ, even though
                // this plugin never enumerates or modifies the user's library.
                WorkshopApi.playback.getCurrentMediaItem()
            } catch (denied: PluginPermissionDeniedException) {
                log.log("Startup permission check failed: " + denied.message)
                toast("Salt Motion 需要「读取音乐库」权限来读取当前歌曲的专辑信息。请重新启用并勾选该权限")
                stop()
                return
            }
            if (target == null) log.log("Playback cover contract unavailable; preserving the native cover")
            val state = MotionRuntime(log, MotionSettings.read(config), config.getConfigPath().parent.resolve("motion-cache"))
            runtime = state
            state.studio?.let { studio ->
                // An unverified editor must not disable an independently verified playback hook.
                try { hooks += dev.saltmotion.editor.EditorHooks.install(studio) }
                catch (error: Exception) { log.log("Cover editor unavailable on this host: " + log.describe(error)) }
                catch (error: LinkageError) { log.log("Cover editor unavailable on this host: " + log.describe(error)) }
            }
            val changed = Consumer<ConfigHelper> {
                synchronized(this) {
                    if (runtime === state) runCatching { state.update(MotionSettings.read(created.getConfig("motion.json"))) }
                }
            }
            listener = changed
            created.addConfigChangeListener(changed)
            if (target != null) for (square in listOf(true, false)) {
                val owner = if (square) "androidx.compose.ui.draw.DrawModifierKt"
                    else "androidx.compose.ui.graphics.GraphicsLayerModifierKt"
                val name = if (square) "drawWithCache" else "graphicsLayer"
                hooks += WorkshopApi.hookRegistrar.hookMethod(owner, name, CoverTarget.parameters, object : MethodHook() {
                    override fun after(call: MethodHookParam) {
                        if (call.throwable != null || !state.active) return
                        val callback = call.args.getOrNull(1) ?: return
                        if (!callback.javaClass.name.startsWith(target.caller + "$")) return
                        if (!CoverTarget.onCoverStack(square, target)) return
                        val original = call.result as? Modifier ?: return
                        val receiver = call.args.getOrNull(0) as? Modifier ?: return
                        try {
                            if (CoverModifier.attach(original, receiver, MotionCoverElement(state), square)) {
                                state.log.logOnce("attached-$square", "Cover modifier connected square=$square")
                            } else {
                                state.log.logOnce("shape-$square", "Cover modifier shape unsupported: ${original.javaClass.name}")
                            }
                        } catch (error: Exception) {
                            state.log.logOnce("hook-error-$square", "Cover attachment failed: " + state.log.describe(error))
                        } catch (error: LinkageError) {
                            state.log.logOnce("hook-error-$square", "Cover attachment failed: " + state.log.describe(error))
                        }
                    }
                })
            }
            if (hooks.isEmpty()) {
                log.log("No verified hook available on this host; plugin stayed inactive")
                stop()
                return
            }
            log.log("Started Salt Motion " + pluginContext.pluginVersion + " host=" + pluginContext.spwVersion + "; registered hooks=" + hooks.size)
        } catch (error: Exception) {
            runtime?.log?.log("Startup failed: " + error.javaClass.name + ": " + error.message)
            stop()
            toast("Salt Motion 接入失败：" + error.javaClass.simpleName + "。请检查模组的修改应用行为权限")
        } catch (error: LinkageError) {
            runtime?.log?.log("Host runtime dependency unavailable: " + error.javaClass.name + ": " + error.message)
            stop()
            toast("Salt Motion 缺少运行依赖：" + error.javaClass.simpleName + "。请重新导入完整插件包")
        }
    }

    @Synchronized override fun stop() {
        val previous = runtime
        runtime = null
        previous?.close()
        hooks.forEach { runCatching { it.unhook() } }
        hooks.clear()
        listener?.let { changed -> manager?.let { runCatching { it.removeConfigChangeListener(changed) } } }
        listener = null
        manager = null
    }

    private fun toast(message: String) {
        runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }
}
