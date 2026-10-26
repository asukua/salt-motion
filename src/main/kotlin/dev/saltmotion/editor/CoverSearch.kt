package dev.saltmotion.editor

import dev.saltmotion.am.*
import java.net.URLEncoder

internal class CoverSearch(private val http: CoverHttp, private val catalog: AlbumCatalog) {
    fun search(source: CoverSource, query: String, track: SelectedTrack, regions: List<String>, songs: Boolean = false): List<CoverCandidate> {
        val term = URLEncoder.encode(query.trim().take(200), Charsets.UTF_8)
        require(term.isNotBlank())
        val results = when (source) {
            CoverSource.APPLE -> catalog.searchCovers(query, regions, track.albumKey,songs).map { item ->
                CoverCandidate(source, item.id, item.album, item.artist, item.image, item.url, item.width, item.height, item.motionRef, item.motionUrl)
            }
            CoverSource.NETEASE -> if(!songs) parseNetease(http.json("https://music.163.com/api/search/get?s=$term&type=10&limit=12&offset=0")) else {
                parseNeteaseSongs(http.json("https://music.163.com/api/search/get?s=$term&type=1&limit=10&offset=0")).take(6).mapNotNull { c ->
                    val detail = AppleMusicJson.read(http.json("https://music.163.com/api/album/${c.id}")).jsonObject()?.objectAt("album")
                    val image = CoverHttp.imageUrl(detail.textAt("picUrl").orEmpty()) ?: return@mapNotNull null
                    c.copy(imageUrl=image)
                }
            }
            CoverSource.QQ -> if(!songs) parseQq(http.json("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$term&format=json&t=8&p=1&n=12"))
                else parseQqSongs(http.json("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$term&format=json&t=0&p=1&n=12"))
            CoverSource.LOCAL -> emptyList()
        }
        return results.distinctBy { it.key }.sortedByDescending { it.rank(track) }.take(24)
    }
    companion object {
        fun parseNeteaseSongs(body:String):List<CoverCandidate> {
            val data=AppleMusicJson.read(body).jsonObject() ?: error("网易云音乐返回格式异常")
            check((data["code"] as? Number)?.toInt()==200)
            return data.objectAt("result")?.get("songs").jsonArray().mapNotNull { value ->
                val s=value.jsonObject();val a=s.objectAt("album") ?: return@mapNotNull null
                val id=(a["id"] as? Number)?.toLong()?.takeIf{it>0}?.toString() ?: return@mapNotNull null
                val artist=s?.get("artists").jsonArray().mapNotNull{it.jsonObject().textAt("name")}.joinToString(" / ")
                CoverCandidate(CoverSource.NETEASE,id,a.textAt("name")?:return@mapNotNull null,artist,"","https://music.163.com/#/album?id=$id")
            }.distinctBy{it.id}
        }
        fun parseQqSongs(body:String):List<CoverCandidate> {
            val data=AppleMusicJson.read(body).jsonObject() ?: error("QQ 音乐返回格式异常")
            check((data["code"] as? Number)?.toInt()==0)
            return data.objectAt("data")?.objectAt("song")?.get("list").jsonArray().mapNotNull { value ->
                val s=value.jsonObject();val id=s.textAt("albummid")?.takeIf{it.matches(Regex("[A-Za-z0-9]{10,32}"))}?:return@mapNotNull null
                val artist=s?.get("singer").jsonArray().mapNotNull{it.jsonObject().textAt("name")}.joinToString(" / ")
                CoverCandidate(CoverSource.QQ,id,s.textAt("albumname")?:return@mapNotNull null,artist,"https://y.gtimg.cn/music/photo_new/T002M000$id.jpg","https://y.qq.com/n/ryqq/albumDetail/$id")
            }.distinctBy{it.id}
        }
        fun parseNetease(body: String): List<CoverCandidate> {
            val data = AppleMusicJson.read(body).jsonObject() ?: error("网易云音乐返回格式异常")
            check((data["code"] as? Number)?.toInt() == 200) { "网易云音乐暂时不可用" }
            return data.objectAt("result")?.get("albums").jsonArray().mapNotNull { value ->
                val a = value.jsonObject() ?: return@mapNotNull null
                val id = (a["id"] as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: return@mapNotNull null
                val image = CoverHttp.imageUrl(a.textAt("picUrl").orEmpty()) ?: return@mapNotNull null
                CoverCandidate(CoverSource.NETEASE, id, a.textAt("name") ?: return@mapNotNull null,
                    a.objectAt("artist").textAt("name").orEmpty(), image, "https://music.163.com/#/album?id=$id")
            }
        }
        fun parseQq(body: String): List<CoverCandidate> {
            val data = AppleMusicJson.read(body).jsonObject() ?: error("QQ 音乐返回格式异常")
            check((data["code"] as? Number)?.toInt() == 0) { "QQ 音乐暂时不可用" }
            return data.objectAt("data")?.objectAt("album")?.get("list").jsonArray().mapNotNull { value ->
                val a = value.jsonObject() ?: return@mapNotNull null
                val id = a.textAt("albumMID")?.takeIf { it.matches(Regex("[A-Za-z0-9]{10,32}")) } ?: return@mapNotNull null
                val supplied = CoverHttp.imageUrl(a.textAt("albumPic").orEmpty()) ?: return@mapNotNull null
                val image = supplied.replace(Regex("T002R[0-9]+x[0-9]+M000"), "T002M000")
                CoverCandidate(CoverSource.QQ, id, a.textAt("albumName") ?: return@mapNotNull null,
                    a.textAt("singerName").orEmpty(), image, "https://y.qq.com/n/ryqq/albumDetail/$id")
            }
        }
    }
}
