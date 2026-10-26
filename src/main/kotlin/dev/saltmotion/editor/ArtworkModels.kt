package dev.saltmotion.editor

import dev.saltmotion.am.AlbumKey
import dev.saltmotion.am.identity
import java.nio.file.Path
import java.util.Locale

internal enum class CoverSource(val label: String) { APPLE("Apple Music"), NETEASE("网易云音乐"), QQ("QQ 音乐"), LOCAL("本地导入") }
internal data class SelectedTrack(val id: String, val title: String, val artist: String, val album: String,
    val albumArtist: String, val path: String) {
    val albumKey get() = AlbumKey(albumArtist.ifBlank { artist }, album)
    companion object {
        fun fromHost(value: Any): SelectedTrack {
            fun text(name: String) = value.javaClass.getMethod(name).invoke(value) as String
            require(value.javaClass.name == "com.xuncorp.voxzen.data.entity.Track")
            return SelectedTrack(text("getId"), text("getTitle"), text("getArtist"), text("getAlbum"), text("getAlbumArtist"), text("getPath"))
        }
    }
}
internal data class CoverCandidate(val source: CoverSource, val id: String, val album: String, val artist: String,
    val imageUrl: String, val sourceUrl: String, val width: Int? = null, val height: Int? = null,
    val motionRef: String? = null, val motionUrl: String? = null, val localFile: Path? = null) {
    val key get() = "${source.name}:$id:${motionRef != null}"
    val animated get() = motionRef != null
    fun exact(track: SelectedTrack) = identity(album) == identity(track.album) && identity(artist) == identity(track.albumKey.artist)
    fun rank(track: SelectedTrack) = (if (identity(album) == identity(track.album)) 60 else 0) +
        (if (identity(artist) == identity(track.albumKey.artist)) 40 else 0)
}
internal data class SavedCover(val poster: String, val source: String, val motionRef: String?)
internal fun normalizedPath(value: String): String = pathOf(value).toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
/** SPW's Vri stores a literal filesystem path after file:///, not a percent-encoded URI.
 * Match the host's Vri-to-file conversion: spaces, #, + and %xx are filename characters.
 */
internal fun pathOf(value: String): Path = Path.of(when {
    value.startsWith("file:///",true) -> value.substring(8)
    value.startsWith("file://",true) -> "\\\\" + value.substring(7).replace('/','\\')
    else -> value
})
