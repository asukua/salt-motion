package dev.saltmotion.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltmotion.MotionRuntime
import dev.saltmotion.MotionSettings
import dev.saltmotion.PluginLog
import dev.saltmotion.am.AlbumCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal class CoverStudio(val root: Path, val runtime: MotionRuntime, val log: PluginLog, catalog: AlbumCatalog) : AutoCloseable {
    val http = CoverHttp()
    val search = CoverSearch(http,catalog)
    val store = CoverStore(root.resolve("custom-covers"),log)
    private val stopped = AtomicBoolean(false)
    private val preparedImages = java.util.concurrent.ConcurrentHashMap<String,Path>()
    var track by mutableStateOf<SelectedTrack?>(null)
        private set
    fun open(value: SelectedTrack) { if (!stopped.get()) { track = value; log.log("Cover editor opened") } }
    fun dismiss() { track = null }
    fun playbackAlbum(artist:String,album:String,path:String): dev.saltmotion.am.AlbumKey? = effectiveAlbum(artist,album,store.find(path)?.motionRef)
    companion object {
        fun effectiveAlbum(artist:String,album:String,motionRef:String?):dev.saltmotion.am.AlbumKey? {
            val key=dev.saltmotion.am.AlbumKey(artist,album)
            if(key.valid)return key
            val ref=motionRef?.let(dev.saltmotion.am.AlbumRef::parse)?:return null
            return dev.saltmotion.am.AlbumKey(artist.ifBlank{"Apple Music"},album.ifBlank{"album-${ref.id}"})
        }
    }
    fun playbackOptions(settings: MotionSettings): MotionSettings {
        val current = runCatching { WorkshopApi.playback.getCurrentMediaItem() }.getOrNull() ?: return settings
        val cover = store.find(current.path) ?: return settings
        val key=effectiveAlbum(current.albumArtist.ifBlank{current.artist},current.album,cover.motionRef)
        return if (cover.motionRef == null || key==null) settings.copy(enabled=false)
        else settings.copy(bindings="${key.artist} | ${key.album} = ${cover.motionRef}")
    }
    fun image(candidate: CoverCandidate): Path {
        check(!stopped.get())
        val key=candidate.key+candidate.imageUrl+candidate.localFile.toString()
        preparedImages[key]?.takeIf(Files::isRegularFile)?.let{return it}
        if(preparedImages.size>64)preparedImages.clear()
        return preparedImages.computeIfAbsent(key){
            val bytes=candidate.localFile?.let { require(Files.size(it) <= 24_000_000); Files.readAllBytes(it) } ?: http.bytes(candidate.imageUrl)
            check(!stopped.get());store.importImage(bytes)
        }
    }
    fun paths(selection: SelectedTrack, album: Boolean): List<String> {
        if (!album) return listOf(selection.path)
        require(selection.albumKey.valid) { "本地专辑或艺人标签缺失，请选择「当前歌曲」范围" }
        val tracks = WorkshopApi.library.getAllTracks().toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS)
        return tracks.filter { dev.saltmotion.am.AlbumKey(it.albumArtist.ifBlank { it.artist },it.album).normalized == selection.albumKey.normalized }
            .map { it.path }.distinct().ifEmpty { listOf(selection.path) }
    }
    fun apply(selection: SelectedTrack, album: Boolean, candidate: CoverCandidate, embed: Boolean): Int {
        if (embed) check(WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.LIBRARY_WRITE)) { "请在模组管理中授予「修改音乐库」权限后再写入歌曲" }
        val poster = image(candidate)
        val paths = paths(selection,album)
        var count = 0
        for (path in paths) {
            check(!stopped.get() && !Thread.currentThread().isInterrupted)
            try {
                if (embed) CoverTagWriter.write(pathOf(path),poster,root.resolve("cover-backups")) {
                    stopped.get() || Thread.currentThread().isInterrupted || !WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.LIBRARY_WRITE)
                }
                store.apply(listOf(path),poster,candidate.source.label,candidate.motionRef)
            } catch (error: Exception) {
                val name=runCatching{pathOf(path).fileName.toString()}.getOrDefault("无法解析文件名")
                throw IllegalStateException("已完成 $count/${paths.size} 首；$name：${error.message ?: error.javaClass.simpleName}",error)
            }
            count++
        }
        log.log("Custom cover applied files=$count embedded=$embed motion=${candidate.animated}")
        return count
    }
    override fun close() { if (stopped.compareAndSet(false,true)) { dismiss(); http.close();preparedImages.clear() } }
}
