package dev.saltmotion

import dev.saltmotion.am.AppleHttp
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AppleHttpRegressionTest {
    private class Server(private val count: Int = 1, handler: (Socket, Int) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:" + socket.localPort
        val permit: (URI) -> Boolean = { it.scheme == "http" && it.host == "127.0.0.1" && it.port == socket.localPort }
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "http-test-server").apply { isDaemon = true } }
        val result = executor.submit {
            repeat(count) { index -> socket.accept().use { accepted -> accepted.soTimeout = 3_000; handler(accepted, index) } }
        }
        fun complete() { result.get(3, TimeUnit.SECONDS) }
        override fun close() { socket.close(); executor.shutdownNow() }
    }
    private fun request(socket: Socket): Map<String, String> {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        check(input.readLine().startsWith("GET "))
        return buildMap {
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
            }
        }
    }
    private fun respond(socket: Socket, response: String) {
        socket.getOutputStream().apply { write(response.toByteArray(Charsets.ISO_8859_1)); flush() }
    }

    @Test fun originHeaderReachesTheServerAndBodyIsReadWithoutOptionalJdkModules() {
        val headers = AtomicReference<Map<String, String>>()
        Server { socket, _ ->
            headers.set(request(socket))
            respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        }.use { server ->
            AppleHttp(server.permit).use { client ->
                val response = client.get(server.origin + "/album", null, 10)
                assertEquals(200, response.status)
                assertEquals("ok", response.body)
            }
            server.complete()
        }
        assertEquals("https://music.apple.com", headers.get()["origin"])
        assertEquals("https://music.apple.com/", headers.get()["referer"])
        assertFalse(headers.get().containsKey("authorization"))
    }

    @Test fun oversizedBodyIsRejectedBeforeTheWholeResponseIsConsumed() {
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: 32\r\nConnection: close\r\n\r\n" + "x".repeat(32))
        }.use { server ->
            AppleHttp(server.permit).use { client -> assertFailsWith<Exception> { client.get(server.origin, null, 8) } }
            server.complete()
        }
    }

    @Test fun binaryVideoDownloadIsCompleteAndBounded() {
        val directory = java.nio.file.Files.createTempDirectory("motion-download")
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nfilm")
        }.use { server ->
            AppleHttp(server.permit).use { client ->
                val target = directory.resolve("video.bin")
                assertEquals(4L, client.download(server.origin, target, 4))
                assertEquals("film", java.nio.file.Files.readString(target))
            }
            server.complete()
        }
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\ntoo large")
        }.use { server ->
            AppleHttp(server.permit).use { client ->
                val target = directory.resolve("oversized.bin")
                assertFails { client.download(server.origin, target, 4) }
                assertFalse(java.nio.file.Files.exists(target))
            }
            server.complete()
        }
    }

    @Test fun binaryRedirectIsValidatedBeforeCreatingAnyFile() {
        val target = java.nio.file.Files.createTempDirectory("motion-redirect").resolve("video.bin")
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 302 Found\r\nLocation: http://example.invalid/asset\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }.use { server ->
            AppleHttp(server.permit).use { client -> assertFails { client.download(server.origin, target, 16) } }
            assertFalse(java.nio.file.Files.exists(target))
            server.complete()
        }
    }

    @Test fun everyRedirectIsValidatedBeforeAnotherConnection() {
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 302 Found\r\nLocation: http://example.invalid/private\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }.use { server ->
            AppleHttp(server.permit).use { client ->
                assertFailsWith<IllegalArgumentException> { client.get(server.origin, null, 10) }
            }
            server.complete()
        }
    }

    @Test fun allowedRedirectCanCompleteWithoutCarryingCredentials() {
        Server(2) { socket, index ->
            val headers = request(socket)
            assertFalse(headers.containsKey("authorization"))
            respond(socket, if (index == 0)
                "HTTP/1.1 302 Found\r\nLocation: /second\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                else "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
        }.use { server ->
            AppleHttp(server.permit).use { client -> assertEquals("ok", client.get(server.origin, null, 10).body) }
            server.complete()
        }
    }

    @Test fun deadlineAlsoCancelsAStalledResponseBody() {
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nx")
            CountDownLatch(1).await(10, TimeUnit.SECONDS)
        }.use { server ->
            AppleHttp(server.permit, 300).use { client ->
                val started = System.nanoTime()
                assertFailsWith<Exception> { client.get(server.origin, null, 10) }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
            }
        }
    }

    @Test fun stoppingTheClientAbortsAnActiveReadAndRejectsNewRequests() {
        val bodyStarted = CountDownLatch(1)
        Server { socket, _ ->
            request(socket)
            respond(socket, "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nx")
            bodyStarted.countDown()
            CountDownLatch(1).await(10, TimeUnit.SECONDS)
        }.use { server ->
            val client = AppleHttp(server.permit)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val call = executor.submit { client.get(server.origin, null, 10) }
                assertTrue(bodyStarted.await(3, TimeUnit.SECONDS))
                client.close()
                assertFailsWith<Exception> { call.get(3, TimeUnit.SECONDS) }
                assertFailsWith<IllegalStateException> { client.get(server.origin, null, 10) }
            } finally { client.close(); executor.shutdownNow() }
        }
    }

    @Test fun networkRuntimeCanInitializeAndStopOnThePlayersModuleSet() {
        if (java.lang.Boolean.getBoolean("saltmotion.trimmedRuntime")) {
            assertFalse(ModuleLayer.boot().findModule("java.net.http").isPresent)
            assertFailsWith<ClassNotFoundException> { Class.forName("java.net.http.HttpClient") }
        }
        MotionRuntime(PluginLog(null), MotionSettings()).use { assertTrue(it.active) }
    }
}
