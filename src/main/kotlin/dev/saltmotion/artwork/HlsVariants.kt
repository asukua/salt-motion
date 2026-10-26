package dev.saltmotion.artwork

import dev.saltmotion.am.AppleTransport
import dev.saltmotion.am.appleMediaUrl
import java.net.URI

internal object HlsVariants {
    data class Stream(val url: String, val framesPerSecond: Double?, val width: Int, val height: Int,
                      val codec: String, val bandwidth: Long)
    fun select(masterUrl: String, text: String): String? = selectStream(masterUrl, text)?.url

    fun selectStream(masterUrl: String, text: String): Stream? {
        if (!appleMediaUrl(masterUrl) || !text.trimStart().startsWith("#EXTM3U")) return null
        var pending: String? = null
        val variants = mutableListOf<Stream>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) pending = line.substringAfter(':')
            else if (line.isNotEmpty() && !line.startsWith('#')) {
                val attributes = pending ?: continue
                pending = null
                fun attribute(key: String) = Regex("(?:^|,)" + Regex.escape(key) + "=(\"[^\"]*\"|[^,]*)")
                    .find(attributes)?.groupValues?.get(1)?.trim('"')
                val codec = attribute("CODECS").orEmpty().split(',').map(String::trim).firstOrNull {
                    it.substringBefore('.') in setOf("avc1", "avc3", "hvc1", "hev1", "av01")
                } ?: continue
                if (attribute("VIDEO-RANGE") !in setOf(null, "SDR")) continue
                val size = attribute("RESOLUTION")?.split('x')?.mapNotNull(String::toIntOrNull) ?: continue
                if (size.size != 2 || size[0] != size[1] || size[0] !in 128..FrameLayout.MAX_SIDE) continue
                val url = runCatching { URI(masterUrl).resolve(line).toString() }.getOrNull() ?: continue
                if (!appleMediaUrl(url) || "iframe" in url.lowercase()) continue
                val rate = attribute("FRAME-RATE")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..120.0 }
                variants += Stream(url, rate, size[0], size[1], codec,
                    attribute("AVERAGE-BANDWIDTH")?.toLongOrNull()?.takeIf { it > 0 }
                        ?: attribute("BANDWIDTH")?.toLongOrNull()?.takeIf { it > 0 } ?: 0)
            }
        }
        return variants.maxWithOrNull(compareBy<Stream> { it.width.toLong() * it.height }.thenBy { it.bandwidth })
    }

    fun resolve(masterUrl: String, http: AppleTransport): String = resolveStream(masterUrl, http).url

    fun resolveStream(masterUrl: String, http: AppleTransport): Stream {
        require(appleMediaUrl(masterUrl))
        val response = http.get(masterUrl, null, 262_144)
        check(response.status == 200) { "Motion manifest unavailable" }
        return selectStream(masterUrl, response.body) ?: error("No supported square SDR video stream")
    }
}
