package dev.saltmotion.am

import java.net.URI
import java.util.Base64

/** The public web app's developer token stays in memory; never read an account/user token. */
internal class PublicWebToken(private val http: AppleTransport, private val now: () -> Long = System::currentTimeMillis) {
    private class Token(val value: String, val until: Long)
    private var current: Token? = null
    private val retryAfter = mutableMapOf<String, Long>()
    private var refresh = false

    @Synchronized fun get(reference: AlbumRef?, region: String): String? {
        val time = now()
        current?.takeIf { it.until > time }?.let { return it.value }
        current = null
        if (time < (retryAfter[region] ?: 0L)) return null
        retryAfter[region] = time + 60_000L
        // Catalog search needs a token before an album ID is known.
        val discovery = if (refresh || reference == null) "https://music.apple.com/$region/new?l=en-US&saltmotion_refresh=$time"
            else "https://music.apple.com/$region/album/${reference.id}"
        val html = http.get(discovery, null, 8_000_000)
            .takeIf { it.status == 200 }?.body ?: return null
        val bundleUrl = indexBundle(html) ?: return null
        val bundle = http.get(bundleUrl, null, 8_000_000).takeIf { it.status == 200 }?.body ?: return null
        val value = fromBundle(bundle, time) ?: return null
        val until = minOf(expiry(value)!! - 60_000L, time + 3_600_000L)
        current = Token(value, until)
        retryAfter.clear()
        return value
    }

    @Synchronized fun invalidate(value: String) {
        if (current?.value == value) { current = null; retryAfter.clear(); refresh = true }
    }

    companion object {
        fun indexBundle(html: String): String? = Regex(
            "<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE,
        ).findAll(html).firstNotNullOfOrNull { match ->
            runCatching {
                val uri = URI("https://music.apple.com").resolve(match.groupValues[1])
                uri.toString().takeIf {
                    AppleHttp.allowed(uri) && uri.host == "music.apple.com" && uri.query == null &&
                        uri.path.matches(Regex("/assets/index(?:[~.-][A-Za-z0-9_.-]+)?\\.js"))
                }
            }.getOrNull()
        }

        fun fromBundle(bundle: String, now: Long): String? {
            val references = Regex("[\"']?developerToken[\"']?\\s*:\\s*([A-Za-z_$][A-Za-z0-9_$]*)")
            for (reference in references.findAll(bundle)) {
                val variable = Regex.escape(reference.groupValues[1])
                val assignment = Regex("(?<![A-Za-z0-9_$])$variable\\s*=\\s*[\"'](eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)[\"']")
                for (match in assignment.findAll(bundle)) {
                    val value = match.groupValues[1]
                    if ((expiry(value) ?: 0L) > now + 60_000L) return value
                }
            }
            return null
        }

        private fun expiry(value: String): Long? = runCatching {
            val payload = Base64.getUrlDecoder().decode(value.split('.')[1]).toString(Charsets.UTF_8)
            val seconds = (AppleMusicJson.read(payload).jsonObject()?.get("exp") as? Number)?.toLong() ?: return null
            Math.multiplyExact(seconds, 1000L)
        }.getOrNull()
    }
}
