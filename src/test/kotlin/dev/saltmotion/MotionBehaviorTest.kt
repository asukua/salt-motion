package dev.saltmotion

import dev.saltmotion.am.*
import dev.saltmotion.artwork.*
import dev.saltmotion.hook.CoverTarget
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MotionBehaviorTest {
    @Test fun frameRateHasOnly24And30AndDefaultsTo30() {
        assertEquals(30, MotionSettings().framesPerSecond)
        assertEquals(24, MotionSettings.frameRate("24"))
        assertEquals(30, MotionSettings.frameRate("30"))
        for (value in listOf("", "10", "15", "60", "bad")) assertEquals(30, MotionSettings.frameRate(value))
    }

    @Test fun sourceFrameRateBelongsToTheSelectedVariant() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:RESOLUTION=720x720,CODECS="avc1",FRAME-RATE=30.000
            large.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=512x512,CODECS="avc1",FRAME-RATE=24.000
            chosen.m3u8
        """.trimIndent()
        val selected = assertNotNull(HlsVariants.selectStream(master, body))
        assertEquals(30.0, selected.framesPerSecond)
        assertTrue(selected.url.endsWith("/large.m3u8"))
        assertNull(HlsVariants.selectStream(master, body.replace("30.000", "NaN"))?.framesPerSecond)
    }

    @Test fun frameScheduleDeductsReadAndUploadTimeWithoutAccumulatingRoundingDrift() {
        val schedule = FrameSchedule(30, 0)
        var time = 0L
        repeat(300) {
            time += 12_000_000L
            val wait = schedule.delayNanos(time)
            assertTrue(wait in 21_333_333L..21_333_334L)
            time += wait
        }
        assertEquals(10_000_000_000L, time)
        val slower = FrameSchedule(24, 0)
        time = 0
        repeat(240) { time += 5_000_000L; time += slower.delayNanos(time) }
        assertEquals(10_000_000_000L, time)
    }

    @Test fun slowFramesDoNotAddAnotherFullDelayAndStallsDoNotCreateCatchupBursts() {
        val schedule = FrameSchedule(30, 0)
        assertEquals(0L, schedule.delayNanos(40_000_000L))
        assertEquals(0L, schedule.delayNanos(200_000_000L))
        assertEquals(28_333_333L, schedule.delayNanos(205_000_000L))
        assertFailsWith<IllegalArgumentException> { FrameSchedule(15, 0) }
    }

    @Test fun frameRateStatsCountOnlyDistinctSubmittedFramesActuallyDrawn() {
        val stats = FrameRateStats(30, 0)
        repeat(300) {
            stats.submitted()
            if (it % 2 == 0) { stats.drawn(); stats.drawn() }
        }
        assertNull(stats.sample(9_000_000_000L))
        val sample = assertNotNull(stats.sample(10_000_000_000L))
        assertEquals(30, sample.target)
        assertEquals(30.0, sample.submitted)
        assertEquals(15.0, sample.drawn)
        assertEquals(0.0, assertNotNull(stats.sample(20_000_000_000L)).submitted)
    }

    private val master = "https://mvod.itunes.apple.com/itunes-assets/album/master.m3u8"
    private val key = AlbumKey("Artist", "Album")
    private fun candidate(id: String, name: String = "Album", artist: String = "Artist") =
        AlbumCandidate(AlbumRef(id, "us"), name, artist)
    private fun token(expiry: Long): String {
        fun part(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return part("""{"alg":"test"}""") + "." + part("""{"exp":EXP}""".replace("EXP", expiry.toString())) + ".signature"
    }
    private fun albumBody(id: String = "123", name: String = "Album", video: String = master): String =
        """{"data":[{"id":"@@ID@@","type":"albums","attributes":{"name":"@@NAME@@","artistName":"Artist","url":"https://music.apple.com/us/album/album/@@ID@@","editorialVideo":{"motionDetailSquare":{"video":"@@VIDEO@@"}}}}]}"""
            .replace("@@ID@@", id).replace("@@NAME@@", name).replace("@@VIDEO@@", video)

    @Test fun exactIdentityRetainsEditionAndArtistDifferences() {
        assertNotNull(selectAlbum(AlbumKey("ＡＲＴＩＳＴ", "album!"), listOf(candidate("1"))))
        assertNull(selectAlbum(key, listOf(candidate("1", "Album (Deluxe)"))))
        assertNull(selectAlbum(key, listOf(candidate("1", artist = "Other Artist"))))
    }

    @Test fun searchRankingCannotResolveAnAmbiguousAlbum() {
        assertNull(selectAlbum(key, listOf(candidate("1"), candidate("2"))))
        assertEquals("1", selectAlbum(key, listOf(candidate("1"), candidate("1")))?.reference?.id)
    }

    @Test fun invalidAndConflictingExplicitBindingsDoNotFallThroughToAutomaticMatching() {
        assertEquals(true to null, MotionSettings(bindings = "Artist | Album = invalid").binding(key))
        assertEquals(true to null, MotionSettings(bindings = "Artist | Album = 1;Artist | Album = 2").binding(key))
        assertEquals(false to null, MotionSettings(bindings = "Other | Album = 1").binding(key))
    }

    @Test fun pastedAppleAlbumUrlsPreserveStorefrontAndAlbumRatherThanSongId() {
        assertEquals(AlbumRef("123", "jp"), AlbumRef.parse("https://music.apple.com/jp/album/name/123?i=456"))
        assertEquals(AlbumRef("123"), AlbumRef.parse("123"))
        for (bad in listOf("https://music.apple.com.evil/album/123", "http://music.apple.com/us/album/123",
            "https://user@music.apple.com/us/album/123", "https://music.apple.com/us/artist/123", "0")) {
            assertNull(AlbumRef.parse(bad))
        }
    }

    @Test fun regionInputIsBoundedDeduplicatedAndDefaultsWhenEmpty() {
        assertEquals(listOf("jp", "us"), MotionSettings.regions("JP,US,JP,123"))
        assertEquals(listOf("us", "jp", "cn"), MotionSettings.regions(" "))
        assertEquals(5, MotionSettings.regions("US,JP,CN,HK,GB,FR").size)
    }

    @Test fun albumParserRejectsWrongEntityIdNameAndExternalStream() {
        assertNotNull(AlbumCatalog.parseAlbum(albumBody(), "123", key))
        assertNull(AlbumCatalog.parseAlbum(albumBody(id = "456"), "123", key))
        assertNull(AlbumCatalog.parseAlbum(albumBody(name = "Other"), "123", key))
        assertNull(AlbumCatalog.parseAlbum(albumBody(video = "https://evil.example/master.m3u8"), "123", key))
        assertNull(AlbumCatalog.parseAlbum(albumBody().replace("\"albums\"", "\"songs\""), "123", key))
        assertNull(AlbumCatalog.parseAlbum("{broken", "123", key))
    }

    @Test fun recommendationsAndTallVideosAreNeverBorrowedAsTheRequestedSquareCover() {
        val body = albumBody().replace("\"motionDetailSquare\"", "\"motionDetailTall\"")
        assertNull(AlbumCatalog.parseAlbum(body, "123", key))
        val mixed = """{"data":DATA,"recommendations":OTHER}"""
            .replace("DATA", "[]").replace("OTHER", albumBody())
        assertNull(AlbumCatalog.parseAlbum(mixed, "123", key))
    }

    @Test fun manualBindingAllowsAnExplicitForeignTitleButStillChecksId() {
        assertNotNull(AlbumCatalog.parseAlbum(albumBody(name = "Foreign Album"), "123"))
        assertNull(AlbumCatalog.parseAlbum(albumBody(id = "456"), "123"))
    }

    @Test fun publicTokenComesOnlyFromTheDeclaredDeveloperTokenAndMustBeFresh() {
        val valid = token(1000)
        assertEquals(valid, PublicWebToken.fromBundle("const t='" + valid + "';const x={developerToken:t};", 0))
        assertNull(PublicWebToken.fromBundle("const unrelated='" + valid + "';", 0))
        assertNull(PublicWebToken.fromBundle("const t='" + token(10) + "';const x={developerToken:t};", 0))
    }

    @Test fun tokenBundleDiscoveryRejectsExternalAndUnrelatedScripts() {
        assertEquals("https://music.apple.com/assets/index~abc123.js",
            PublicWebToken.indexBundle("""<script src="/assets/index~abc123.js"></script>"""))
        assertNull(PublicWebToken.indexBundle("""<script src="https://evil.example/assets/index.js"></script>"""))
        assertNull(PublicWebToken.indexBundle("""<script src="/assets/other.js"></script>"""))
    }

    @Test fun hlsSelectsHighestHevcAndStillRejectsIframeHdrOversizedAndExternalVariants() {
        val text = """
            #EXTM3U
            #EXT-X-I-FRAME-STREAM-INF:RESOLUTION=4096x4096,CODECS="avc1.64001f",URI="iframe.m3u8"
            #EXT-X-STREAM-INF:RESOLUTION=1080x1080,CODECS="avc1.64001f"
            big.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=2160x2160,CODECS="hvc1.2"
            hevc.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=3840x3840,CODECS="avc1.64001f",VIDEO-RANGE=PQ
            hdr.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=3840x3840,CODECS="avc1.64001f"
            https://evil.example/other.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=65536x65536,CODECS="hvc1.2"
            unsafe.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=486x486,CODECS="avc1.64001f",VIDEO-RANGE=SDR,BANDWIDTH=600000
            correct.m3u8
        """.trimIndent()
        assertEquals(URI(master).resolve("hevc.m3u8").toString(), HlsVariants.select(master, text))
        assertNull(HlsVariants.select(master, "#EXTM3U\n#EXTINF:1\nsegment.ts"))
    }

    @Test fun hlsPrefersResolutionThenHighestBitrateAndRejectsNonSquare() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:RESOLUTION=2160x2160,CODECS="hvc1",BANDWIDTH=900000
            large.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=2160x2160,CODECS="hvc1",BANDWIDTH=500000
            small.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=3840x2160,CODECS="avc1",BANDWIDTH=9000000
            tall.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=1080x1080,CODECS="avc1",BANDWIDTH=9000000
            lower-resolution.m3u8
        """.trimIndent()
        assertTrue(HlsVariants.select(master, text)!!.endsWith("/large.m3u8"))
    }

    @Test fun originalResolutionBuffersAreAlignedPackedAndBounded() {
        val uhd = FrameLayout.of(2160, 2160)
        assertEquals(8640, uhd.stride())
        assertEquals(18_662_400, uhd.packedBytes())
        assertEquals(uhd.packedBytes(), uhd.bufferBytes())
        val padded = FrameLayout.of(1080, 1080)
        assertEquals(4352, padded.stride())
        assertTrue(padded.bufferBytes() > padded.packedBytes())
        for (size in listOf(-1, 0, 4097, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { FrameLayout.of(size, 2160) }
        }
    }

    @Test fun hlsKeepsHighestAvcWhenHevcIsUnavailableAndUsesAverageBitrateForTies() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:RESOLUTION=512x512,CODECS="avc1",BANDWIDTH=9000000
            low.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=1080x1080,CODECS="avc1",AVERAGE-BANDWIDTH=800000,BANDWIDTH=1200000
            peak.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=1080x1080,CODECS="avc1",AVERAGE-BANDWIDTH=900000,BANDWIDTH=1000000
            preferred.m3u8
        """.trimIndent()
        val selected = assertNotNull(HlsVariants.selectStream(master, body))
        assertTrue(selected.url.endsWith("/preferred.m3u8"))
        assertEquals(1080, selected.width)
        assertEquals(900000, selected.bandwidth)
    }

    @Test fun appleUrlBoundaryExcludesCredentialsPortsAndLookalikeHosts() {
        assertTrue(appleMediaUrl(master))
        for (url in listOf("http://mvod.itunes.apple.com/a.m3u8", "https://mvod.itunes.apple.com.evil/a.m3u8",
            "https://user@mvod.itunes.apple.com/a.m3u8", "https://mvod.itunes.apple.com:444/a.m3u8")) {
            assertFalse(appleMediaUrl(url))
        }
        assertFalse(AppleHttp.allowed(URI("https://music.apple.com.evil/")))
    }

    @Test fun catalogUsesManualMappingCachesAndStopsAfterClose() {
        val calls = AtomicInteger()
        val valid = token(1_000_000_000)
        val fake = AppleTransport { url, bearer, _ ->
            calls.incrementAndGet()
            when {
                "/assets/index" in url -> WebResponse(200, "const t='" + valid + "';const x={developerToken:t};")
                "amp-api" in url -> {
                    assertEquals(valid, bearer)
                    WebResponse(200, albumBody())
                }
                "/album/" in url -> WebResponse(200, """<script src="/assets/index~abc.js"></script>""")
                else -> error("Manual mapping must not search")
            }
        }
        val catalog = AlbumCatalog(fake, PluginLog(null)) { 0 }
        val settings = MotionSettings(bindings = "Artist | Album = 123", storefronts = listOf("us"))
        assertNotNull(catalog.resolve(key, settings))
        assertNotNull(catalog.resolve(key, settings))
        assertEquals(3, calls.get())
        catalog.close()
        assertNull(catalog.resolve(key, settings))
        assertEquals(3, calls.get())
    }

    @Test fun catalogRejectsInvalidBindingWithoutMakingNetworkRequests() {
        val catalog = AlbumCatalog(AppleTransport { _, _, _ -> error("Unexpected network call") }, PluginLog(null))
        assertNull(catalog.resolve(key, MotionSettings(bindings = "Artist | Album = bad")))
        catalog.close()
    }

    private fun amSearchBody(vararg ids: String): String = """{"results":{"albums":{"data":[ITEMS]}}}"""
        .replace("ITEMS", ids.joinToString(",") { albumBody(id = it).substringAfter("\"data\":[").removeSuffix("]}") })

    @Test fun amSearchParserIgnoresWrongEntitiesAndMismatchedAlbumUrls() {
        assertEquals("123", AlbumCatalog.catalogCandidates(amSearchBody("123"), "jp").single().reference.id)
        assertEquals("jp", AlbumCatalog.catalogCandidates(amSearchBody("123"), "jp").single().reference.storefront)
        assertTrue(AlbumCatalog.catalogCandidates(amSearchBody("123").replace("\"albums\",\"attributes\"", "\"songs\",\"attributes\""), "us").isEmpty())
        assertTrue(AlbumCatalog.catalogCandidates(amSearchBody("123").replace("https://music.apple.com", "https://evil.example"), "us").isEmpty())
        assertTrue(AlbumCatalog.catalogCandidates("{broken", "us").isEmpty())
    }

    @Test fun amPrimarySearchFindsAnAlbumWithoutCallingItunesOrKnowingAnIdFirst() {
        val valid = token(1_000_000_000)
        var searched = 0
        val fake = AppleTransport { url, bearer, _ ->
            when {
                "/new?" in url -> WebResponse(200, """<script src="/assets/index~abc.js"></script>""")
                "/assets/index" in url -> WebResponse(200, "const t='$valid';const x={developerToken:t};")
                "/search?" in url && "amp-api" in url -> {
                    searched++; assertEquals(valid, bearer); WebResponse(200, amSearchBody("123"))
                }
                "/albums/123?" in url -> WebResponse(200, albumBody())
                else -> error("AM matched: no legacy search or album-page bootstrap expected")
            }
        }
        AlbumCatalog(fake, PluginLog(null)) { 0 }.use { catalog ->
            assertEquals("123", catalog.resolve(key, MotionSettings(storefronts = listOf("us")))?.albumId)
            assertNotNull(catalog.resolve(key, MotionSettings(storefronts = listOf("us"))))
            assertEquals(1, searched)
        }
    }

    @Test fun missingUsResultAndRateLimitedLegacySearchContinueToJapan() {
        val valid = token(1_000_000_000)
        val regions = mutableListOf<String>()
        val fake = AppleTransport { url, _, _ ->
            when {
                "/new?" in url -> WebResponse(200, """<script src="/assets/index~abc.js"></script>""")
                "/assets/index" in url -> WebResponse(200, "const t='$valid';const x={developerToken:t};")
                "/us/search?" in url -> { regions += "us"; WebResponse(200, amSearchBody()) }
                "itunes.apple.com/search?" in url -> WebResponse(429, "")
                "/jp/search?" in url -> { regions += "jp"; WebResponse(200, amSearchBody("123")) }
                "/jp/albums/123?" in url -> WebResponse(200, albumBody())
                else -> error("Unexpected call")
            }
        }
        AlbumCatalog(fake, PluginLog(null)) { 0 }.use { catalog ->
            assertNotNull(catalog.resolve(key, MotionSettings(storefronts = listOf("us", "jp", "cn"))))
            assertEquals(listOf("us", "jp"), regions)
        }
    }

    @Test fun tokenDiscoveryFailureInOneRegionDoesNotBlockTheNextRegion() {
        val valid = token(1_000_000_000)
        val pages = mutableListOf<String>()
        val fake = AppleTransport { url, _, _ ->
            when {
                "/us/new?" in url -> { pages += "us"; WebResponse(503, "") }
                "/jp/new?" in url -> { pages += "jp"; WebResponse(200, """<script src="/assets/index~abc.js"></script>""") }
                "/assets/index" in url -> WebResponse(200, "const t='$valid';const x={developerToken:t};")
                else -> error("Unexpected call")
            }
        }
        val tokens = PublicWebToken(fake) { 0 }
        assertNull(tokens.get(null, "us"))
        assertNull(tokens.get(null, "us"))
        assertEquals(valid, tokens.get(null, "jp"))
        assertEquals(valid, tokens.get(null, "us"))
        assertEquals(listOf("us", "jp"), pages)
    }

    @Test fun ambiguousAmResultsCannotBeOverriddenByLegacyRanking() {
        val valid = token(1_000_000_000)
        val fake = AppleTransport { url, _, _ ->
            when {
                "/new?" in url -> WebResponse(200, """<script src="/assets/index~abc.js"></script>""")
                "/assets/index" in url -> WebResponse(200, "const t='$valid';const x={developerToken:t};")
                "amp-api" in url && "/search?" in url -> WebResponse(200, amSearchBody("123", "456"))
                else -> error("Ambiguity must not trigger fallback or choose an album")
            }
        }
        AlbumCatalog(fake, PluginLog(null)) { 0 }.use { catalog ->
            assertNull(catalog.resolve(key, MotionSettings(storefronts = listOf("us"))))
        }
    }

    @Test fun rejectedAmSearchTokenIsRefreshedBeforeRepeatingTheSearch() {
        val old = token(1_000_000_000)
        val fresh = token(1_000_000_001)
        var pages = 0
        var attempts = 0
        val fake = AppleTransport { url, bearer, _ ->
            when {
                "/new?" in url -> { pages++; WebResponse(200, "<script src=\"/assets/index~${if (pages == 1) "old" else "fresh"}.js\"></script>") }
                "/assets/index~old" in url -> WebResponse(200, "const t='$old';const x={developerToken:t};")
                "/assets/index~fresh" in url -> WebResponse(200, "const t='$fresh';const x={developerToken:t};")
                "amp-api" in url && "/search?" in url -> {
                    attempts++; if (bearer == old) WebResponse(401, "") else WebResponse(200, amSearchBody("123"))
                }
                "/albums/123?" in url -> { assertEquals(fresh, bearer); WebResponse(200, albumBody()) }
                else -> error("Unexpected call")
            }
        }
        AlbumCatalog(fake, PluginLog(null)) { 0 }.use { catalog ->
            assertNotNull(catalog.resolve(key, MotionSettings(storefronts = listOf("us"))))
            assertEquals(2, pages)
            assertEquals(2, attempts)
        }
    }

    @Test fun legacyFallbackStillRequiresVerifiedAlbumIdentityAndSquareMotion() {
        val valid = token(1_000_000_000)
        val fake = AppleTransport { url, _, _ ->
            when {
                "/new?" in url -> WebResponse(200, """<script src="/assets/index~abc.js"></script>""")
                "/assets/index" in url -> WebResponse(200, "const t='$valid';const x={developerToken:t};")
                "amp-api" in url && "/search?" in url -> WebResponse(503, "")
                "itunes.apple.com/search?" in url -> WebResponse(200, """{"results":[{"wrapperType":"collection","collectionType":"Album","collectionId":123,"collectionName":"Album","artistName":"Artist"}]}""")
                "/albums/123?" in url -> WebResponse(200, albumBody())
                else -> error("Unexpected call")
            }
        }
        AlbumCatalog(fake, PluginLog(null)) { 0 }.use { catalog ->
            assertNotNull(catalog.resolve(key, MotionSettings(storefronts = listOf("us"))))
        }
    }

    @Test fun rejectedCachedWebBundleIsRefreshedFromANewPageBeforeRetrying() {
        val oldToken = token(1_000_000_000)
        val freshToken = token(1_000_000_001)
        var rejected = 0
        var refreshed = 0
        val http = AppleTransport { url, bearer, _ ->
            when {
                "/new?" in url -> { refreshed++; WebResponse(200, """<script src="/assets/index~fresh.js"></script>""") }
                "/assets/index~fresh" in url -> WebResponse(200, "const t='" + freshToken + "';const x={developerToken:t};")
                "/assets/index~old" in url -> WebResponse(200, "const t='" + oldToken + "';const x={developerToken:t};")
                "amp-api" in url -> if (bearer == oldToken) { rejected++; WebResponse(401, "") }
                    else { assertEquals(freshToken, bearer); WebResponse(200, albumBody()) }
                else -> WebResponse(200, """<script src="/assets/index~old.js"></script>""")
            }
        }
        val catalog = AlbumCatalog(http, PluginLog(null)) { 0 }
        assertNotNull(catalog.resolve(key, MotionSettings(bindings = "Artist | Album = 123", storefronts = listOf("us"))))
        assertEquals(1, rejected)
        assertEquals(1, refreshed)
        catalog.close()
    }

    @Test fun decoderBudgetIsReleasedOnlyAfterItsOwnerCloses() {
        val created = AtomicInteger()
        val live = AtomicInteger()
        val pool = DecoderPool(1)
        val factory = {
            created.incrementAndGet()
            assertEquals(1, live.incrementAndGet())
            object : FrameDecoder {
                override fun read(): PixelFrame? = null
                override fun close() { live.decrementAndGet() }
            }
        }
        val first = pool.open(factory)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val next = executor.submit<FrameDecoder> { started.countDown(); pool.open(factory) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertEquals(1, created.get())
            first.close(); first.close()
            val second = next.get(3, TimeUnit.SECONDS)
            assertEquals(2, created.get())
            assertEquals(1, live.get())
            second.close()
            assertEquals(0, live.get())
            pool.close()
            assertFails { pool.open(factory) }
        } finally { first.close(); pool.close(); executor.shutdownNow() }
    }

    @Test fun lateDecoderInitializationAfterStopIsClosedExactlyOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val decoder = object : FrameDecoder {
            override fun read(): PixelFrame? = null
            override fun close() { closed.incrementAndGet() }
        }
        val session = MotionSession({ entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); decoder }) { it() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit { session.open(master) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.close()
            release.countDown()
            task.get(3, TimeUnit.SECONDS)
            session.close()
            assertEquals(1, closed.get())
            assertNull(session.read())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun sessionClosedBeforeOpeningNeverCreatesNativeDecoder() {
        val session = MotionSession({ error("Native factory must not run") }) { it() }
        session.close()
        session.open(master)
        assertNull(session.read())
    }

    @Test fun frameRetirementWaitsForDrawAndRejectsLateFrames() {
        class Frame : AutoCloseable { val closes = AtomicInteger(); override fun close() { closes.incrementAndGet() } }
        val holder = OwnedMotionFrame<Frame>()
        val frame = Frame()
        holder.replace(frame)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val draw = executor.submit { holder.draw { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); assertEquals(0, it.closes.get()) } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val close = executor.submit { holder.close() }
            assertEquals(0, frame.closes.get())
            release.countDown()
            draw.get(3, TimeUnit.SECONDS); close.get(3, TimeUnit.SECONDS)
            assertEquals(1, frame.closes.get())
            val late = Frame()
            assertFalse(holder.replace(late))
            assertEquals(1, late.closes.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun unknownHostOrChangedClassIsRejectedBeforeHookRegistration() {
        assertFalse(CoverTarget.verified("1.19.3", byteArrayOf()))
        assertFalse(CoverTarget.verified("1.19.2", byteArrayOf(1, 2, 3)))
    }

    @Test fun onlyTheVerifiedCoverCallsitesCanAttachAnimationNodes() {
        assertTrue(CoverTarget.callsite(CoverTarget.CALLER, "\u037f", CoverTarget.SQUARE_DESCRIPTOR, 385, true))
        assertTrue(CoverTarget.callsite(CoverTarget.CALLER, "\u037f", CoverTarget.CIRCLE_DESCRIPTOR, 560, false))
        assertFalse(CoverTarget.callsite(CoverTarget.CALLER, "\u037f", CoverTarget.SQUARE_DESCRIPTOR, 296, true))
        assertFalse(CoverTarget.callsite("other.screen", "\u037f", CoverTarget.SQUARE_DESCRIPTOR, 385, true))
        assertFalse(CoverTarget.callsite(CoverTarget.CALLER, "\u037f", CoverTarget.CIRCLE_DESCRIPTOR, 560, true))
    }

    @Test fun realStackInspectionCanReadDescriptorsAndRejectAnUnrelatedCaller() {
        // This used to throw UnsupportedOperationException before examining any host frame.
        assertFalse(CoverTarget.onCoverStack(true))
        assertFalse(CoverTarget.onCoverStack(false))
    }

    @Test fun frameClearingDoesNotCloseTheOwnerForTheNextAlbum() {
        class Frame : AutoCloseable { var closed = false; override fun close() { closed = true } }
        val holder = OwnedMotionFrame<Frame>()
        val previous = Frame(); holder.replace(previous); holder.clear()
        assertTrue(previous.closed)
        assertFalse(holder.draw { fail("Cleared frame was drawn") })
        val next = Frame(); assertTrue(holder.replace(next))
        assertTrue(holder.draw { assertSame(next, it) })
        holder.close()
        assertTrue(next.closed)
    }
}
