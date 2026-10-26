package dev.saltmotion.artwork

import dev.saltmotion.MotionSettings
import dev.saltmotion.PluginLog
import dev.saltmotion.am.*
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreparedMotion(val motion: AlbumMotion, val stream: HlsVariants.Stream, val cached: Boolean)

/** Complete, checksum-verified, immutable album directories; partial downloads are never playable. */
internal class MotionVideoCache(
    root: Path,
    private val http: AppleTransport,
    private val transfer: (String, Path, Long) -> Long,
    private val log: PluginLog,
    private val limit: Long = 2_147_483_648L,
) : AutoCloseable {
    private val root = root.toAbsolutePath().normalize()
    private val closed = AtomicBoolean(false)

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hash(path: Path): String = Files.newInputStream(path).use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65_536)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun mapping(album: AlbumKey, settings: MotionSettings): Path {
        val binding = settings.binding(album)
        val key = listOf(album.normalized.first, album.normalized.second, settings.storefronts.joinToString(","), binding.toString()).joinToString("\n")
        return root.resolve("lookup-${digest(key.toByteArray())}.properties")
    }
    private fun properties(path: Path) = Properties().apply { Files.newInputStream(path).use(::load) }
    private fun save(path: Path, values: Properties) = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW).use { values.store(it, "Salt Motion verified cache") }
    private fun albumDirectory(id: String): Path {
        require(id.matches(Regex("[1-9][0-9]{0,19}")))
        return root.resolve(id)
    }
    private fun readAlbum(id: String): PreparedMotion? = runCatching {
        val directory = albumDirectory(id)
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null
        val metadata = properties(directory.resolve("info.properties"))
        check(metadata.getProperty("version") == "1" && metadata.getProperty("id") == id)
        val width = metadata.getProperty("width").toInt(); val height = metadata.getProperty("height").toInt()
        FrameLayout.of(width, height); check(width == height)
        val count = metadata.getProperty("files").toInt(); check(count in 2..65)
        repeat(count) { index ->
            val name = metadata.getProperty("file.$index.name")
            check(name == "video.m3u8" || name.matches(Regex("asset-[0-9]{3}\\.mp4")))
            val path = directory.resolve(name)
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
            check(Files.size(path) == metadata.getProperty("file.$index.size").toLong() && hash(path) == metadata.getProperty("file.$index.sha256"))
        }
        val source = metadata.getProperty("source")
        check(AlbumRef.parse(source)?.id == id)
        val local = directory.resolve("video.m3u8").toString()
        PreparedMotion(AlbumMotion(id, source, local), HlsVariants.Stream(local,
            metadata.getProperty("fps").toDoubleOrNull(), width, height, metadata.getProperty("codec"), metadata.getProperty("bitrate").toLong()), true)
    }.getOrNull()

    @Synchronized fun lookup(album: AlbumKey, settings: MotionSettings): PreparedMotion? {
        if (closed.get() || !album.valid || settings.binding(album) == (true to null)) return null
        val mapped = runCatching { readAlbum(properties(mapping(album, settings)).getProperty("id")) }.getOrNull()
        // A user-selected AM ID is authoritative even when broken local tags need a new mapping.
        // Reuse the verified album directory prepared by the editor without requiring another network request.
        val selected = settings.binding(album).second?.let { readAlbum(it.id) }
        return (mapped ?: selected)?.also {
            log.log("Video cache hit album=${it.motion.albumId} source=${it.stream.width}x${it.stream.height}")
        }
    }

    @Synchronized fun store(album: AlbumKey, settings: MotionSettings, motion: AlbumMotion, stream: HlsVariants.Stream): PreparedMotion {
        check(!closed.get())
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root))
        readAlbum(motion.albumId)?.let { existing -> writeMapping(album, settings, motion.albumId); return existing }
        val destination = albumDirectory(motion.albumId)
        // A corrupt previous directory is left untouched for inspection; never overwrite files a decoder may use.
        check(!Files.exists(destination)) { "Existing cache is invalid; remove this album cache while the plugin is stopped" }
        val response = http.get(stream.url, null, 262_144)
        check(response.status == 200)
        val (playlist, assets) = mediaPlaylist(stream.url, response.body)
        val used = Files.list(root).use { dirs -> dirs.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }.mapToLong { dir ->
            Files.list(dir).use { files -> files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong(Files::size).sum() }
        }.sum() }
        check(used < limit) { "Video cache limit reached" }
        val staging = root.resolve(".partial-${UUID.randomUUID()}")
        Files.createDirectory(staging)
        try {
            var bytes = 0L
            for ((url, name) in assets) {
                check(!closed.get()); check(!Thread.currentThread().isInterrupted)
                bytes += transfer(url, staging.resolve(name), minOf(268_435_456L - bytes, limit - used - bytes))
                check(bytes in 1..268_435_456L && used + bytes <= limit)
            }
            Files.writeString(staging.resolve("video.m3u8"), playlist, StandardOpenOption.CREATE_NEW)
            val files = listOf("video.m3u8") + assets.values
            val metadata = Properties().apply {
                setProperty("version", "1"); setProperty("id", motion.albumId); setProperty("source", motion.sourceUrl)
                setProperty("width", stream.width.toString()); setProperty("height", stream.height.toString())
                setProperty("fps", stream.framesPerSecond?.toString().orEmpty()); setProperty("codec", stream.codec); setProperty("bitrate", stream.bandwidth.toString())
                setProperty("files", files.size.toString())
                files.forEachIndexed { i, name -> setProperty("file.$i.name", name); setProperty("file.$i.size", Files.size(staging.resolve(name)).toString()); setProperty("file.$i.sha256", hash(staging.resolve(name))) }
            }
            save(staging.resolve("info.properties"), metadata)
            check(!closed.get()); check(!Thread.currentThread().isInterrupted)
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            writeMapping(album, settings, motion.albumId)
            log.log("Video cached album=${motion.albumId} source=${stream.width}x${stream.height} bytes=$bytes")
            return checkNotNull(readAlbum(motion.albumId))
        } finally { removePartial(staging) }
    }

    private fun writeMapping(album: AlbumKey, settings: MotionSettings, id: String) {
        val temporary = root.resolve(".lookup-${UUID.randomUUID()}")
        try { save(temporary, Properties().apply { setProperty("id", id) }); Files.move(temporary, mapping(album, settings), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
    }
    private fun removePartial(directory: Path) {
        check(directory.toAbsolutePath().normalize().parent == root && directory.fileName.toString().startsWith(".partial-"))
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return
        Files.list(directory).use { files -> files.forEach { file -> if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file) } }
        Files.deleteIfExists(directory)
    }
    override fun close() { closed.set(true) }

    companion object {
        fun mediaPlaylist(url: String, text: String): Pair<String, LinkedHashMap<String, String>> {
            require(appleMediaUrl(url) && text.trimStart().startsWith("#EXTM3U") && "#EXT-X-ENDLIST" in text && "#EXTINF:" in text)
            require("#EXT-X-STREAM-INF" !in text)
            val assets = linkedMapOf<String, String>()
            fun asset(value: String): String {
                val resolved = URI(url).resolve(value)
                require(AppleHttp.allowed(resolved) && resolved.host.let { it.endsWith(".itunes.apple.com") || it.endsWith(".mzstatic.com") })
                return assets.getOrPut(resolved.toString()) { check(assets.size < 64); "asset-%03d.mp4".format(assets.size) }
            }
            val result = text.lineSequence().map { raw ->
                val line = raw.trim()
                require(!line.startsWith("#EXT-X-KEY") || Regex("(?:^|,)METHOD=NONE(?:,|$)").containsMatchIn(line.substringAfter(':')))
                if ("URI=" in line) require(line.startsWith("#EXT-X-MAP:") && Regex("URI=\"[^\"]+\"").containsMatchIn(line))
                when {
                    line.isNotEmpty() && !line.startsWith('#') -> asset(line)
                    else -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${asset(it.groupValues[1])}\"" }
                }
            }.joinToString("\n") + "\n"
            check(assets.isNotEmpty())
            return result to assets
        }
    }
}
