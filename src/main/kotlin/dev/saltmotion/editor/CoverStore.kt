package dev.saltmotion.editor

import androidx.compose.runtime.mutableIntStateOf
import dev.saltmotion.PluginLog
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Content-addressed images and atomically published path mappings; no audio writes here. */
internal class CoverStore(val root: Path, private val log: PluginLog) {
    val revision = mutableIntStateOf(0)
    @Volatile private var entries: Map<String,SavedCover> = emptyMap()
    init {
        runCatching {
            val file = root.resolve("index.properties")
            if (Files.isRegularFile(file)) {
                val p = Properties().apply { Files.newInputStream(file).use(::load) }
                val count = p.getProperty("count").toInt().also { require(it in 0..100_000) }
                val loaded = mutableMapOf<String,SavedCover>()
                repeat(count) { i ->
                    val poster = p.getProperty("$i.poster")
                    require(poster.matches(Regex("[a-f0-9]{64}\\.(jpg|png)")))
                    loaded[p.getProperty("$i.path")] = SavedCover(poster, p.getProperty("$i.source", "本地"), p.getProperty("$i.motion")?.takeIf(String::isNotBlank))
                }
                entries = loaded.toMap()
            }
        }.onFailure { log.log("Custom cover index unavailable: ${it.javaClass.simpleName}"); entries = emptyMap() }
    }
    fun find(path: String): SavedCover? { revision.intValue; return runCatching { entries[normalizedPath(path)] }.getOrNull() }
    fun poster(cover: SavedCover): Path = root.resolve(cover.poster)
    @Synchronized fun importImage(bytes: ByteArray): Path {
        require(bytes.size in 1..24_000_000) { "请选择小于 24 MB 的图片" }
        val stored: ByteArray
        val extension: String
        Image.makeFromEncoded(bytes).use { image ->
            require(image.width in 32..8192 && image.height in 32..8192 && image.width.toLong() * image.height <= 33_554_432) { "图片尺寸须为 32～8192 像素，且不超过 3200 万像素" }
            when {
                bytes.size > 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> { stored = bytes; extension = "jpg" }
                bytes.size > 8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) -> { stored = bytes; extension = "png" }
                else -> { stored = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "无法转换这张图片" }.use { it.bytes }; extension = "png" }
            }
        }
        require(stored.size <= 24_000_000) { "转换后的图片超过 24 MB，请导入尺寸较小的图片" }
        val name = MessageDigest.getInstance("SHA-256").digest(stored).joinToString("") { "%02x".format(it) } + ".$extension"
        Files.createDirectories(root); check(!Files.isSymbolicLink(root))
        val target = root.resolve(name)
        if (!Files.exists(target)) {
            val temporary = root.resolve(".image-${UUID.randomUUID()}")
            try { Files.write(temporary, stored, StandardOpenOption.CREATE_NEW); Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE) }
            finally { Files.deleteIfExists(temporary) }
        }
        return target
    }
    @Synchronized fun apply(paths: List<String>, poster: Path, source: String, motionRef: String?) {
        require(poster.toAbsolutePath().normalize().parent == root.toAbsolutePath().normalize() && Files.isRegularFile(poster))
        val next = HashMap(entries)
        paths.forEach { next[normalizedPath(it)] = SavedCover(poster.fileName.toString(), source, motionRef) }
        publish(next)
    }
    @Synchronized fun reset(paths: List<String>) { val next = HashMap(entries); paths.forEach { next.remove(normalizedPath(it)) }; publish(next) }
    private fun publish(next: Map<String,SavedCover>) {
        Files.createDirectories(root)
        val temporary = root.resolve(".index-${UUID.randomUUID()}")
        val p = Properties().apply {
            setProperty("count", next.size.toString())
            next.entries.sortedBy { it.key }.forEachIndexed { i, (path, cover) ->
                setProperty("$i.path", path); setProperty("$i.poster", cover.poster); setProperty("$i.source", cover.source)
                cover.motionRef?.let { setProperty("$i.motion", it) }
            }
        }
        try { Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW).use { p.store(it,"Salt Motion custom covers") }; Files.move(temporary,root.resolve("index.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
        entries = next.toMap(); revision.intValue++
    }
}
