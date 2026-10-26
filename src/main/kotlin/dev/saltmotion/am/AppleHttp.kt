package dev.saltmotion.am

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request

internal data class WebResponse(val status: Int, val body: String)
internal fun interface AppleTransport {
    fun get(url: String, bearer: String?, limit: Int): WebResponse
}

/** The bundled client preserves Origin without depending on optional JDK network modules. */
internal class AppleHttp(
    private val permit: (URI) -> Boolean = ::allowed,
    private val deadlineMillis: Long = 12_000,
) : AppleTransport, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val active = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(deadlineMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    override fun get(url: String, bearer: String?, limit: Int): WebResponse {
        require(limit in 1..8_000_000)
        var target = URI(url)
        repeat(4) {
            check(!closed.get()) { "Apple client closed" }
            require(permit(target)) { "Unexpected Apple endpoint" }
            require(bearer == null || target.host == "amp-api.music.apple.com")
            val request = Request.Builder().url(target.toString())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache")
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            val call = client.newCall(request.get().build())
            active.add(call)
            try {
                // A stop racing dispatcher registration must cancel even a not-yet-executed call.
                if (closed.get()) { call.cancel(); error("Apple client closed") }
                call.execute().use { response ->
                val bytes = response.body?.byteStream()?.readNBytes(limit + 1) ?: ByteArray(0)
                if (bytes.size > limit) {
                    call.cancel()
                    error("Apple response exceeds size limit")
                }
                if (response.code in setOf(301, 302, 303, 307, 308) && bearer == null) {
                    target = target.resolve(response.header("Location") ?: error("Missing redirect"))
                } else return WebResponse(response.code,
                    if (response.code == 200) bytes.toString(Charsets.UTF_8) else "")
                }
            } finally { active.remove(call) }
        }
        error("Too many Apple redirects")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.forEach { it.cancel() }
        active.clear()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Bounded binary CDN transfer, sharing cancellation with metadata requests. */
    fun download(url: String, target: Path, maxBytes: Long): Long {
        require(maxBytes in 1..268_435_456L)
        var uri = URI(url)
        repeat(4) {
            check(!closed.get())
            require(permit(uri))
            val request = Request.Builder().url(uri.toString())
                .header("Origin", "https://music.apple.com").header("Referer", "https://music.apple.com/")
                .header("Accept-Encoding", "identity").build()
            val call = client.newCall(request)
            call.timeout().timeout(120, TimeUnit.SECONDS)
            active.add(call)
            try {
                if (closed.get()) { call.cancel(); error("Apple client closed") }
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        uri = uri.resolve(response.header("Location") ?: error("Missing redirect"))
                    } else {
                        check(response.code == 200) { "Video download HTTP ${response.code}" }
                        val body = checkNotNull(response.body)
                        val expected = body.contentLength()
                        check(expected <= maxBytes) { "Video exceeds cache size limit" }
                        var count = 0L
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(65_536)
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Video download interrupted")
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    count += size
                                    check(count <= maxBytes) { "Video exceeds cache size limit" }
                                    output.write(buffer, 0, size)
                                }
                            }
                        }
                        check(count > 0 && (expected < 0 || count == expected)) { "Incomplete video download" }
                        return count
                    }
                }
            } finally { if (Thread.currentThread().isInterrupted) call.cancel(); active.remove(call) }
        }
        error("Too many video redirects")
    }

    companion object {
        fun allowed(uri: URI): Boolean {
            val host = uri.host ?: return false
            return uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
                (uri.port == -1 || uri.port == 443) && (host in setOf(
                    "music.apple.com", "amp-api.music.apple.com", "itunes.apple.com",
                ) || host.endsWith(".itunes.apple.com") || host.endsWith(".mzstatic.com"))
        }
    }
}
