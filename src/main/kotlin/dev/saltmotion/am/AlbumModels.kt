package dev.saltmotion.am

import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal fun identity(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal data class AlbumKey(val artist: String, val album: String) {
    val valid: Boolean get() = identity(artist).isNotEmpty() && identity(album).isNotEmpty()
    val normalized: Pair<String, String> get() = identity(artist) to identity(album)
}

internal data class AlbumRef(val id: String, val storefront: String? = null) {
    companion object {
        fun parse(value: String): AlbumRef? = runCatching {
            val trimmed = value.trim()
            if (trimmed.matches(Regex("[1-9][0-9]{0,19}"))) return AlbumRef(trimmed)
            val uri = URI(trimmed)
            require(uri.scheme == "https" && uri.host == "music.apple.com" && uri.userInfo == null)
            require(uri.port == -1 || uri.port == 443)
            val match = Regex("/([a-zA-Z]{2})/album/(?:[^/]+/)?([1-9][0-9]{0,19})/?").matchEntire(uri.path)
                ?: return null
            AlbumRef(match.groupValues[2], match.groupValues[1].lowercase(Locale.ROOT))
        }.getOrNull()
    }
}

internal data class AlbumMotion(val albumId: String, val sourceUrl: String, val videoUrl: String)
internal data class AlbumCandidate(val reference: AlbumRef, val name: String, val artist: String)

internal fun selectAlbum(key: AlbumKey, candidates: List<AlbumCandidate>): AlbumCandidate? = candidates
    .filter { identity(it.name) == identity(key.album) && identity(it.artist) == identity(key.artist) }
    .distinctBy { it.reference.id }.singleOrNull()

internal fun appleMediaUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
        (host.endsWith(".itunes.apple.com") || host.endsWith(".mzstatic.com")) &&
        uri.path.lowercase(Locale.ROOT).endsWith(".m3u8") && uri.fragment == null
}.getOrDefault(false)
