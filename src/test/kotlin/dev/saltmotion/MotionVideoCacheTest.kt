package dev.saltmotion

import dev.saltmotion.am.*
import dev.saltmotion.artwork.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MotionVideoCacheTest {
    private val url = "https://mvod.itunes.apple.com/album/video.m3u8"
    private val key = AlbumKey("Artist", "Album")
    private val settings = MotionSettings()
    private val motion = AlbumMotion("123", "https://music.apple.com/us/album/album/123", url)
    private val stream = HlsVariants.Stream(url, 30.0, 2160, 2160, "hvc1", 25_000_000)
    private val playlist = "#EXTM3U\n#EXT-X-MAP:URI=\"video.mp4\",BYTERANGE=\"12@0\"\n#EXTINF:5,\n#EXT-X-BYTERANGE:20@12\nvideo.mp4\n#EXT-X-ENDLIST\n"
    private fun cache(root: Path, transfer: (String, Path, Long) -> Long, limit: Long = 2048) =
        MotionVideoCache(root, AppleTransport { _, _, _ -> WebResponse(200, playlist) }, transfer, PluginLog(null), limit)
    private val copy: (String, Path, Long) -> Long = { _, path, limit -> check(limit >= 32); Files.write(path, ByteArray(32) { it.toByte() }); 32L }

    @Test fun repeatedAlbumAndRestartReuseOneVerifiedAssetWithoutNetwork() {
        val root = Files.createTempDirectory("motion-cache-test")
        var downloads = 0
        cache(root, { url, path, max -> downloads++; copy(url, path, max) }).use { c ->
            val first = c.store(key, settings, motion, stream)
            assertTrue(first.cached); assertEquals(2160, first.stream.width)
            assertTrue(Files.isRegularFile(Path.of(first.stream.url)))
            c.store(AlbumKey("ARTIST", "ALBUM"), settings, motion, stream)
            assertEquals(1, downloads)
        }
        cache(root, { _, _, _ -> error("Offline cache must not download") }).use { c ->
            assertEquals("123", assertNotNull(c.lookup(key, settings)).motion.albumId)
            assertNull(c.lookup(key, settings.copy(bindings = "Artist | Album = 456")))
            assertNull(c.lookup(key, settings.copy(storefronts = listOf("jp"))))
        }
    }
    @Test fun explicitSelectionReusesAnAlbumCachedUnderDifferentBrokenLocalTagsOffline() {
        val root=Files.createTempDirectory("motion-selected-cache")
        cache(root,copy).use{it.store(key,settings,motion,stream)}
        cache(root,{_,_,_->error("Must remain offline")}).use{c->
            val broken=AlbumKey("Unknown artist","Wrong local album")
            val selected=settings.copy(bindings="Unknown artist | Wrong local album = https://music.apple.com/us/album/album/123")
            assertEquals("123",assertNotNull(c.lookup(broken,selected)).motion.albumId)
            assertNull(c.lookup(broken,selected.copy(bindings="Unknown artist | Wrong local album = invalid")))
        }
    }

    @Test fun partialFailureNeverPublishesAndItsTemporaryFilesAreRemoved() {
        val root = Files.createTempDirectory("motion-cache-failed")
        cache(root, { _, path, _ -> Files.writeString(path, "partial"); error("connection lost") }).use { c ->
            assertFails { c.store(key, settings, motion, stream) }
            assertNull(c.lookup(key, settings))
            assertFalse(Files.exists(root.resolve("123")))
            Files.list(root).use { assertEquals(0, it.count()) }
        }
    }

    @Test fun changedPixelsOrManifestCannotBecomeAValidOfflineHit() {
        val root = Files.createTempDirectory("motion-cache-corrupt")
        cache(root, copy).use { c ->
            c.store(key, settings, motion, stream)
            Files.write(root.resolve("123/asset-000.mp4"), ByteArray(32))
            assertNull(c.lookup(key, settings))
            assertFails { c.store(key, settings, motion, stream) }
        }
    }

    @Test fun mediaManifestKeepsRangesAndDeduplicatesInitAndSegmentReferences() {
        val (text, assets) = MotionVideoCache.mediaPlaylist(url, playlist)
        assertEquals(1, assets.size)
        assertTrue("BYTERANGE=\"12@0\"" in text && "#EXT-X-BYTERANGE:20@12" in text)
        assertTrue("URI=\"asset-000.mp4\"" in text)
        assertTrue("\nasset-000.mp4\n" in text)
    }

    @Test fun remoteReferencesEncryptionAndLivePlaylistsAreRejected() {
        for (bad in listOf(playlist.replace("video.mp4", "https://evil.example/video.mp4"),
            playlist.replace("#EXT-X-ENDLIST", ""), playlist.replace("#EXTINF:5,", "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5,"))) {
            assertFails { MotionVideoCache.mediaPlaylist(url, bad) }
        }
    }

    @Test fun quotaAndInvalidAlbumIdsCannotWriteOutsideTheCache() {
        val root = Files.createTempDirectory("motion-cache-bounds")
        cache(root, { _, _, limit -> check(limit >= 32); 32 }, 16).use { c ->
            assertFails { c.store(key, settings, motion, stream) }
            assertFails { c.store(key, settings, motion.copy(albumId = "../escape"), stream) }
            assertNull(c.lookup(key, settings))
        }
    }
}
