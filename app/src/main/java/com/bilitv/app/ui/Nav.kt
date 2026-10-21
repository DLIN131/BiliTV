package com.bilitv.app.ui

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import com.bilitv.app.net.VideoItem

/** 頁面堆疊裡的頁面 */
sealed interface Route {
    data class Video(val bvid: String) : Route
    data class Up(val mid: Long) : Route
}

/** 全 App 共用的導覽動作，用 CompositionLocal 傳下去，不用一層層傳參數 */
class Nav(
    val openVideo: (String) -> Unit,
    val openUp: (Long) -> Unit,
    val openLive: (roomId: Long, title: String, anchor: String) -> Unit,
) {
    /** 點卡片：直播間直接進直播，影片進詳情頁 */
    fun open(item: VideoItem) {
        if (item.liveRoomId > 0) openLive(item.liveRoomId, item.title, item.author)
        else if (item.bvid.isNotEmpty()) openVideo(item.bvid)
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav 尚未提供") }

/** 從外部連結打開 App：影片、UP 主頁、直播間 */
sealed interface DeepLink {
    data class Open(val route: Route) : DeepLink
    data class Live(val roomId: Long) : DeepLink
}

/**
 * 支援：
 * - https://www.bilibili.com/video/BVxxxx、m.bilibili.com/video/BVxxxx
 * - https://space.bilibili.com/<mid>
 * - https://live.bilibili.com/<房間號>
 * - bilitv://video/<bvid>、bilitv://up/<mid>、bilitv://live/<房間號>
 */
fun parseDeepLink(uri: Uri?): DeepLink? {
    if (uri == null) return null
    val host = uri.host.orEmpty()
    val segs = uri.pathSegments
    fun video(id: String?) = id?.takeIf { it.startsWith("BV") }?.let { DeepLink.Open(Route.Video(it)) }
    fun up(id: String?) = id?.toLongOrNull()?.let { DeepLink.Open(Route.Up(it)) }
    fun live(id: String?) = id?.toLongOrNull()?.let { DeepLink.Live(it) }
    return when {
        uri.scheme == "bilitv" -> when (host) {
            "video" -> video(segs.firstOrNull())
            "up" -> up(segs.firstOrNull())
            "live" -> live(segs.firstOrNull())
            else -> null
        }
        host == "space.bilibili.com" -> up(segs.firstOrNull())
        host == "live.bilibili.com" -> live(segs.firstOrNull())
        host.endsWith("bilibili.com") && segs.firstOrNull() == "video" -> video(segs.getOrNull(1))
        else -> null
    }
}
