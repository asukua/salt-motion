package dev.saltmotion

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import dev.saltmotion.am.AlbumKey
import dev.saltmotion.am.AlbumRef
import java.util.Locale

internal data class MotionSettings(
    val enabled: Boolean = true,
    val storefronts: List<String> = listOf("us", "jp", "cn"),
    val bindings: String = "",
    val framesPerSecond: Int = 30,
) {
    // An invalid explicit binding blocks automatic matching for that album.
    fun binding(key: AlbumKey): Pair<Boolean, AlbumRef?> {
        val matches = bindings.split(Regex("[;\r\n]+")).mapNotNull { line ->
            if ('=' !in line) return@mapNotNull null
            val parts = line.substringBefore('=').split('|', limit = 2)
            if (parts.size != 2 || AlbumKey(parts[0].trim(), parts[1].trim()).normalized != key.normalized)
                return@mapNotNull null
            AlbumRef.parse(line.substringAfter('=')) to line
        }
        return if (matches.isEmpty()) false to null else true to matches.singleOrNull()?.first
    }

    companion object {
        fun regions(raw: String): List<String> = raw.split(Regex("[,;\\s]+"))
            .map { it.lowercase(Locale.ROOT) }.filter { it.matches(Regex("[a-z]{2}")) }
            .distinct().take(5).ifEmpty { listOf("us", "jp", "cn") }

        fun read(config: ConfigHelper): MotionSettings = MotionSettings(
            enabled = config.get("enabled", true),
            storefronts = regions(config.get("storefronts", "US,JP,CN")),
            bindings = config.get("album_bindings", ""),
            framesPerSecond = frameRate(config.get("frame_rate", "30")),
        )

        fun frameRate(raw: String): Int = raw.toIntOrNull()?.takeIf { it == 24 || it == 30 } ?: 30
    }
}
