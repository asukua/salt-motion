package dev.saltmotion.editor

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Public catalog and image requests only. No account cookies or playback endpoints. */
internal class CoverHttp : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun bytes(url: String, limit: Int = 24_000_000): ByteArray {
        require(limit in 1..32_000_000)
        var uri = URI(url)
        repeat(4) {
            check(!stopped.get()); require(allowed(uri)) { "不支持的封面地址" }
            val referer = when {
                uri.host.endsWith("126.net") || uri.host.endsWith("163.com") -> "https://music.163.com/"
                uri.host.endsWith("qq.com") || uri.host.endsWith("gtimg.cn") -> "https://y.qq.com/"
                else -> "https://music.apple.com/"
            }
            val call = client.newCall(Request.Builder().url(uri.toString()).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
                .header("Referer", referer).header("Accept-Encoding", "identity").build())
            calls.add(call)
            try {
                if (stopped.get()) { call.cancel(); error("封面搜索已停止") }
                call.execute().use { response ->
                    if (response.code in setOf(301,302,303,307,308)) uri = uri.resolve(response.header("Location") ?: error("封面重定向缺少地址"))
                    else {
                        check(response.code == 200) { "服务器返回 HTTP ${response.code}" }
                        check((response.body?.contentLength() ?: -1) <= limit) { "封面文件过大" }
                        val bytes = response.body?.byteStream()?.readNBytes(limit + 1) ?: error("封面为空")
                        check(bytes.isNotEmpty() && bytes.size <= limit) { "封面文件过大或为空" }
                        return bytes
                    }
                }
            } finally { if (Thread.currentThread().isInterrupted) call.cancel(); calls.remove(call) }
        }
        error("封面重定向次数过多")
    }
    fun json(url: String) = bytes(url, 4_000_000).toString(Charsets.UTF_8)
    override fun close() {
        if (!stopped.compareAndSet(false,true)) return
        calls.forEach(Call::cancel); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    companion object {
        fun allowed(uri: URI): Boolean {
            val h = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443) &&
                (h in setOf("music.163.com", "c.y.qq.com", "y.gtimg.cn") || h.matches(Regex("p[0-9]+\\.music\\.126\\.net")) || h.endsWith(".mzstatic.com"))
        }
        fun imageUrl(value: String): String? = runCatching {
            val url = if (value.startsWith("http://")) "https://" + value.removePrefix("http://") else value
            url.takeIf { allowed(URI(it)) }
        }.getOrNull()
    }
}
