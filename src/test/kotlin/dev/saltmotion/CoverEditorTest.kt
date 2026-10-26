package dev.saltmotion

import dev.saltmotion.am.*
import dev.saltmotion.editor.*
import java.net.URI
import kotlin.test.*

class CoverEditorTest {
    @Test fun explicitMotionCanPlayEvenWhenLocalAlbumTagsAreEmpty() {
        assertNull(CoverStudio.effectiveAlbum("","",null))
        val key=CoverStudio.effectiveAlbum("","","https://music.apple.com/us/album/24k-magic/1161503945")!!
        assertTrue(key.valid);assertEquals("album-1161503945",key.album)
        assertNull(CoverStudio.effectiveAlbum("","","https://example.com/fake"))
    }
    @Test fun qqAlbumUsesReturnedVersionedOriginalArtwork() {
        val result=CoverSearch.parseQq("""{"code":0,"data":{"album":{"list":[{"albumMID":"000Y3pJo100umL","albumName":"Iron Man 2","singerName":"AC/DC","albumPic":"http://y.gtimg.cn/music/photo_new/T002R180x180M000000Y3pJo100umL_2.jpg"}]}}}""").single()
        assertEquals("https://y.gtimg.cn/music/photo_new/T002M000000Y3pJo100umL_2.jpg",result.imageUrl)
    }
    @Test fun neteaseRejectsUnexpectedImageServersButKeepsCorrectAlbum() {
        val result=CoverSearch.parseNetease("""{"code":200,"result":{"albums":[{"id":3,"name":"Album","artist":{"name":"Artist"},"picUrl":"http://p2.music.126.net/a/b.jpg"},{"id":4,"name":"Wrong","artist":{"name":"Other"},"picUrl":"https://example.com/cover.jpg"}]}}""")
        assertEquals(1,result.size);assertEquals("3",result.single().id);assertTrue(result.single().imageUrl.startsWith("https://"))
    }
    @Test fun editedSongQueryResultsUseTheSongsAlbumIdentity() {
        val result=CoverSearch.parseQqSongs("""{"code":0,"data":{"song":{"list":[{"songname":"Come As You Are","albumname":"Nevermind","albummid":"000b9DFD3r9CFu","singer":[{"name":"Nirvana"}]}]}}}""").single()
        assertEquals("Nevermind",result.album);assertEquals("Nirvana",result.artist)
        assertFalse(result.exact(SelectedTrack("x","Bad","Bad","Bad","","D:\\bad.flac")))
        assertTrue(result.exact(SelectedTrack("x","Come As You Are","Nirvana","Nevermind","","D:\\good.flac")))
    }
    @Test fun appleSongUsesAlbumRelationshipInsteadOfSongId() {
        val item=AppleMusicJson.read("""{"id":"900","type":"songs","attributes":{"albumName":"Nevermind","artistName":"Nirvana","artwork":{"url":"https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg","width":3000,"height":3000}},"relationships":{"albums":{"data":[{"id":"123","type":"albums"}]}}}""").jsonObject()
        val result=AlbumCatalog.songCoverResult(item,"jp")!!
        assertEquals("123",result.id);assertEquals("https://music.apple.com/jp/album/123",result.url)
        assertTrue(result.image.endsWith("/3000x3000bb.jpg"))
    }
    @Test fun inputSourcesCannotRedirectCoverRequestsToUnrelatedHosts() {
        for(url in listOf("http://p2.music.126.net/a.jpg","https://y.gtimg.cn.evil.test/a.jpg","https://evil@y.gtimg.cn/a.jpg","https://music.163.com:8443/api/search","file:///D:/MUSIC/a.jpg"))assertFalse(CoverHttp.allowed(URI(url)),url)
        assertTrue(CoverHttp.allowed(URI("https://y.gtimg.cn/music/photo_new/T002M000abcd.jpg")))
    }
    @Test fun songSearchDeduplicatesAlbumsWithoutDroppingDistinctVersions() {
        val body="""{"code":200,"result":{"songs":[{"album":{"id":1,"name":"Nevermind"},"artists":[{"name":"Nirvana"}]},{"album":{"id":1,"name":"Nevermind"},"artists":[{"name":"Nirvana"}]},{"album":{"id":2,"name":"Nevermind (Deluxe)"},"artists":[{"name":"Nirvana"}]}]}}"""
        assertEquals(listOf("1","2"),CoverSearch.parseNeteaseSongs(body).map{it.id})
    }
}
