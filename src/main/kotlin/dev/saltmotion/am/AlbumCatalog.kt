package dev.saltmotion.am

import dev.saltmotion.MotionSettings
import dev.saltmotion.PluginLog
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

internal class AlbumCatalog(
    private val http: AppleTransport,
    private val log: PluginLog,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    data class CoverResult(val id: String, val album: String, val artist: String, val image: String,
        val url: String, val width: Int?, val height: Int?, val motionRef: String? = null, val motionUrl: String? = null)
    private val token = PublicWebToken(http, now)
    private data class Key(val album: Pair<String, String>, val regions: List<String>, val binding: Pair<Boolean, AlbumRef?>)
    private data class Cached(val motion: AlbumMotion?, val until: Long)
    private val cache = object : LinkedHashMap<Key, Cached>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>?) = size > 64
    }
    private val inflight = ConcurrentHashMap<Key, FutureTask<AlbumMotion?>>()
    private val closed = AtomicBoolean(false)

    fun resolve(album: AlbumKey, settings: MotionSettings): AlbumMotion? {
        if (!album.valid || !settings.enabled || closed.get()) return null
        val binding = settings.binding(album)
        if (binding.first && binding.second == null) return null
        val key = Key(album.normalized, settings.storefronts, binding)
        synchronized(cache) { cache[key]?.takeIf { it.until > now() }?.let { return it.motion } }
        val task = FutureTask<AlbumMotion?> {
            val result = runCatching { fetch(album, settings, binding) }.onFailure {
                // Do not log URLs, bundle bodies, request headers or bearer values.
                log.log("Apple album resolution failed: ${it.javaClass.simpleName}")
            }.getOrNull()
            if (!closed.get()) synchronized(cache) {
                cache[key] = Cached(result, now() + if (result == null) 60_000L else 21_600_000L)
            }
            result
        }
        val actual = inflight.putIfAbsent(key, task) ?: task
        if (actual === task) task.run()
        return try { actual.get() } finally { inflight.remove(key, actual) }
    }

    private fun fetch(album: AlbumKey, settings: MotionSettings, binding: Pair<Boolean, AlbumRef?>): AlbumMotion? {
        val manual = binding.second
        val regions = (listOfNotNull(manual?.storefront) + settings.storefronts).distinct()
        for (region in regions) {
            if (closed.get() || Thread.currentThread().isInterrupted) return null
            try {
                val ref = manual ?: search(album, region) ?: continue
                val url = "https://amp-api.music.apple.com/v1/catalog/$region/albums/${ref.id}?extend=editorialVideo&platform=web"
                val response = authorizedGet(url, ref, region) ?: continue
                log.log("Apple detail region=$region album=${ref.id} status=${response.status}")
                if (response.status == 200) parseAlbum(response.body, ref.id, album.takeUnless { binding.first })?.let {
                    log.log("Apple motion album=${it.albumId} region=$region")
                    return it
                }
                if (response.status == 200) log.log("Apple detail region=$region album=${ref.id} no-verified-square-motion")
            } catch (interrupted: InterruptedException) { throw interrupted }
            catch (error: Exception) { log.log("Apple region=$region unavailable: ${error.javaClass.simpleName}") }
        }
        return null
    }

    private fun authorizedGet(url: String, ref: AlbumRef?, region: String): WebResponse? {
        var bearer = token.get(ref, region) ?: run {
            log.log("Apple region=$region web-token-unavailable")
            return null
        }
        var response = http.get(url, bearer, 4_000_000)
        if (response.status == 401 || response.status == 403) {
            token.invalidate(bearer)
            bearer = token.get(ref, region) ?: return null
            response = http.get(url, bearer, 4_000_000)
        }
        return response
    }

    private fun search(album: AlbumKey, region: String): AlbumRef? {
        val term = URLEncoder.encode("${album.artist} ${album.album}", Charsets.UTF_8)
        val response = authorizedGet(
            "https://amp-api.music.apple.com/v1/catalog/$region/search?term=$term&types=albums&limit=25&platform=web", null, region)
        val candidates = if (response?.status == 200) catalogCandidates(response.body, region) else emptyList()
        val exact = candidates.filter { it.name.let(::identity) == identity(album.album) && identity(it.artist) == identity(album.artist) }
            .distinctBy { it.reference.id }
        log.log("Apple search source=am region=$region status=${response?.status ?: 0} candidates=${candidates.size} exact=${exact.size}")
        selectAlbum(album, candidates)?.let { return it.reference }
        // A second source must not turn an ambiguous AM identity into a guessed match.
        if (exact.size > 1) return null

        val fallback = http.get("https://itunes.apple.com/search?term=$term&country=$region&entity=album&limit=25", null, 2_000_000)
        val legacy = if (fallback.status == 200) searchCandidates(fallback.body, region) else emptyList()
        val legacyExact = legacy.filter { identity(it.name) == identity(album.album) && identity(it.artist) == identity(album.artist) }
            .distinctBy { it.reference.id }
        log.log("Apple search source=itunes region=$region status=${fallback.status} candidates=${legacy.size} exact=${legacyExact.size}")
        return selectAlbum(album, legacy)?.reference
    }

    /** Interactive candidate search is separate from the strict automatic identity matcher. */
    fun searchCovers(query: String, regions: List<String>, expected: AlbumKey, songs: Boolean = false): List<CoverResult> {
        val results = linkedMapOf<String, CoverResult>()
        var available = false
        val term = URLEncoder.encode(query.take(200), Charsets.UTF_8)
        for (region in regions.take(3)) {
            if (closed.get() || Thread.currentThread().isInterrupted) break
            try {
                val response = authorizedGet("https://amp-api.music.apple.com/v1/catalog/$region/search?term=$term&types=${if(songs)"songs&include%5Bsongs%5D=albums"else"albums"}&limit=8&platform=web", null, region)
                if (response?.status != 200) continue
                available = true
                val values = AppleMusicJson.read(response.body).jsonObject()?.objectAt("results")?.objectAt(if(songs)"songs"else"albums")?.get("data").jsonArray()
                values.mapNotNull { if(songs) songCoverResult(it.jsonObject(),region)else coverResult(it.jsonObject(), region) }.forEach { results.putIfAbsent(it.id, it) }
                if (results.values.any { identity(it.album) == identity(expected.album) && identity(it.artist) == identity(expected.artist) }) break
            } catch (interrupted: InterruptedException) { throw interrupted }
            catch (error: Exception) { log.log("Cover catalog region=$region failed: ${error.javaClass.simpleName}") }
        }
        check(available) { "Apple Music 暂时不可用" }
        val ordered = results.values.sortedByDescending { (if (identity(it.album) == identity(expected.album)) 60 else 0) + (if (identity(it.artist) == identity(expected.artist)) 40 else 0) }.take(8)
        val motions = mutableListOf<CoverResult>()
        for (candidate in ordered) {
            if (closed.get() || Thread.currentThread().isInterrupted) break
            runCatching {
                val ref = AlbumRef.parse(candidate.url) ?: return@runCatching
                val region = ref.storefront ?: regions.firstOrNull() ?: "us"
                val response = authorizedGet("https://amp-api.music.apple.com/v1/catalog/$region/albums/${ref.id}?extend=editorialVideo&platform=web",ref,region)
                if (response?.status == 200) parseAlbum(response.body,ref.id)?.let { motion -> motions += candidate.copy(motionRef=candidate.url,motionUrl=motion.videoUrl) }
            }.onFailure { log.log("Cover motion detail unavailable: ${it.javaClass.simpleName}") }
        }
        return ordered.take(20) + motions
    }

    override fun close() {
        closed.set(true)
        (http as? AutoCloseable)?.close()
        synchronized(cache) { cache.clear() }
    }

    companion object {
        fun songCoverResult(item:Map<*,*>?,region:String):CoverResult? {
            if(item.textAt("type")!="songs")return null
            val a=item.objectAt("attributes")?:return null
            val album=item.objectAt("relationships")?.objectAt("albums")?.get("data").jsonArray().firstOrNull().jsonObject()?:return null
            coverResult(album,region)?.let{return it}
            val id=album.textAt("id")?.takeIf{AlbumRef.parse(it)!=null}?:return null
            val url=album.objectAt("attributes").textAt("url")?:"https://music.apple.com/$region/album/$id"
            val attrs=mapOf("name" to a.textAt("albumName"),"artistName" to (a.textAt("albumArtistName")?:a.textAt("artistName")),"url" to url,"artwork" to a.objectAt("artwork"))
            return coverResult(mapOf("type" to "albums","id" to id,"attributes" to attrs),region)
        }
        fun coverResult(item: Map<*,*>?, region: String): CoverResult? {
            if (item.textAt("type") != "albums") return null
            val a = item.objectAt("attributes") ?: return null
            val id = item.textAt("id") ?: return null
            val url = a.textAt("url") ?: return null
            if (AlbumRef.parse(url)?.id != id) return null
            val art = a.objectAt("artwork") ?: return null
            val w = (art["width"] as? Number)?.toInt()?.coerceIn(32,8192)
            val h = (art["height"] as? Number)?.toInt()?.coerceIn(32,8192)
            val image = art.textAt("url")?.replace("{w}",(w ?: 3000).toString())?.replace("{h}",(h ?: 3000).toString())?.replace("{c}","")?.replace("{f}","jpg") ?: return null
            if (!dev.saltmotion.editor.CoverHttp.imageUrl(image).let { it != null }) return null
            return CoverResult(id,a.textAt("name") ?: return null,a.textAt("artistName") ?: return null,image,url,w,h)
        }
        fun catalogCandidates(body: String, region: String): List<AlbumCandidate> = runCatching {
            AppleMusicJson.read(body).jsonObject()?.objectAt("results")?.objectAt("albums")?.get("data").jsonArray().mapNotNull { entry ->
                val item = entry.jsonObject() ?: return@mapNotNull null
                if (item.textAt("type") != "albums") return@mapNotNull null
                val ref = AlbumRef.parse(item.textAt("id").orEmpty())?.copy(storefront = region) ?: return@mapNotNull null
                val attributes = item.objectAt("attributes") ?: return@mapNotNull null
                if (AlbumRef.parse(attributes.textAt("url").orEmpty())?.id != ref.id) return@mapNotNull null
                AlbumCandidate(ref, attributes.textAt("name") ?: return@mapNotNull null,
                    attributes.textAt("artistName") ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())

        fun searchCandidates(body: String, region: String): List<AlbumCandidate> = runCatching {
            AppleMusicJson.read(body).jsonObject()?.get("results").jsonArray().mapNotNull { entry ->
                val item = entry.jsonObject() ?: return@mapNotNull null
                if (item.textAt("wrapperType") != "collection" || item.textAt("collectionType") != "Album") return@mapNotNull null
                val id = (item["collectionId"] as? Number)?.toLong()?.toString() ?: return@mapNotNull null
                val ref = AlbumRef.parse(id)?.copy(storefront = region) ?: return@mapNotNull null
                AlbumCandidate(ref, item.textAt("collectionName") ?: return@mapNotNull null,
                    item.textAt("artistName") ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())

        fun parseAlbum(body: String, expectedId: String, expected: AlbumKey? = null): AlbumMotion? = runCatching {
            val entries = AppleMusicJson.read(body).jsonObject()?.get("data").jsonArray()
                .mapNotNull { it.jsonObject() }.filter { it.textAt("type") == "albums" && it.textAt("id") == expectedId }
            val attributes = entries.singleOrNull()?.objectAt("attributes") ?: return null
            val source = attributes.textAt("url") ?: return null
            if (AlbumRef.parse(source)?.id != expectedId) return null
            if (expected != null && (identity(attributes.textAt("name").orEmpty()) != identity(expected.album) ||
                        identity(attributes.textAt("artistName").orEmpty()) != identity(expected.artist))) return null
            val videos = attributes.objectAt("editorialVideo") ?: return null
            val video = listOf("motionDetailSquare", "motionSquareVideo1x1").firstNotNullOfOrNull { name ->
                videos.objectAt(name).textAt("video")?.takeIf(::appleMediaUrl)
            } ?: return null
            AlbumMotion(expectedId, source, video)
        }.getOrNull()
    }
}
