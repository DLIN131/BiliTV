package com.bilitv.app.net

import java.io.IOException

data class VideoItem(
    val bvid: String,
    val aid: Long = 0,
    val cid: Long = 0,
    val title: String,
    val cover: String,
    val author: String,
    val duration: String = "",
    val views: String = "",
    val danmaku: String = "",
    /** 額外資訊，例如發布時間、觀看時間 */
    val extra: String = "",
    /** 觀看進度 0..1，-1 表示不顯示 */
    val progress: Float = -1f,
)

data class Page(val cid: Long, val page: Int, val title: String, val duration: Int)

data class VideoDetail(
    val bvid: String,
    val aid: Long,
    val title: String,
    val cover: String,
    val desc: String,
    val ownerName: String,
    val ownerFace: String,
    val views: Long,
    val danmaku: Long,
    val likes: Long,
    val coins: Long,
    val favorites: Long,
    val pubdate: Long,
    val pages: List<Page>,
)

data class Stream(val id: Int, val urls: List<String>, val bandwidth: Long, val codecid: Int)

data class PlayInfo(
    val videos: List<Stream>,
    val audios: List<Stream>,
    val durl: List<String>,
    /** 可用畫質（由高到低）：qn 與名稱 */
    val qualities: List<Pair<Int, String>>,
)

data class UserInfo(
    val isLogin: Boolean,
    val mid: Long,
    val name: String,
    val face: String,
    val level: Int,
    val vip: Boolean,
)

data class Danmaku(val timeMs: Long, val mode: Int, val color: Int, val text: String)

class ApiException(val code: Int, message: String) : IOException(message)
