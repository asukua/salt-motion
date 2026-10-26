package dev.saltmotion

import dev.saltmotion.editor.*
import java.nio.file.Path
import kotlin.test.*

class ArtworkPathTest {
    @Test fun reportedThrillerPathKeepsLiteralSpaces() {
        assertEquals(Path.of("D:/MUSIC/Michael Jackson - Thriller.flac"),pathOf("file:///D:/MUSIC/Michael Jackson - Thriller.flac"))
    }
    @Test fun nativePathsKeepUnicodeHashPercentAndPlus() {
        val path="D:/MUSIC/幼稚园杀手 - 再爱我一次 #1 100% +.mp3"
        assertEquals(Path.of(path),pathOf("file:///$path"))
    }
    @Test fun percentEscapesInNativeVriAreLiteralFilenameCharacters() {
        assertEquals(Path.of("D:/MUSIC/a%20b%23c.flac"),pathOf("file:///D:/MUSIC/a%20b%23c.flac"))
        assertNotEquals(normalizedPath("file:///D:/MUSIC/a%20b.flac"),normalizedPath("file:///D:/MUSIC/a b.flac"))
    }
    @Test fun rawVriAndFilesystemSpellingShareTheSameCoverMappingKey() {
        assertEquals(normalizedPath("D:\\MUSIC\\Michael Jackson - Thriller.flac"),normalizedPath("file:///D:/MUSIC/Michael Jackson - Thriller.flac"))
        assertEquals(normalizedPath("d:/music/中文 #1 %.mp3"),normalizedPath("FILE:///D:/MUSIC/中文 #1 %.mp3"))
    }
    @Test fun networkVriUsesTheHostsUncPathConvention() {
        assertEquals(Path.of("\\\\nas\\music\\album #1\\song 100%.flac"),pathOf("file://nas/music/album #1/song 100%.flac"))
    }
}
