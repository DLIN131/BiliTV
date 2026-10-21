package com.bilitv.app.net

import androidx.core.text.HtmlCompat
import com.bilitv.app.Prefs
import com.bilitv.app.Session
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.TreeMap
import java.util.zip.Inflater

object BiliApi {
    private const val API = "https://api.bilibili.com"
    private const val PASSPORT = "https://passport.bilibili.com"
    private const val LIVE = "https://api.live.bilibili.com"
    private const val APP_KEY = "1d8b6e7d45233436"
    private const val APP_SEC = "560c52ccd288fed045859ed18bffd973"
    private const val APP_UA = "Mozilla/5.0 BiliDroid/8.0.0 (bbcallen@gmail.com)"
    private val LIVE_HEADERS = mapOf("Referer" to "https://live.bilibili.com/")

    // ---------- 共用 ----------

    private fun buildUrl(base: String, params: Map<String, String>): HttpUrl {
        val b = base.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun get(
        base: String,
        params: Map<String, Any?> = emptyMap(),
        wbi: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val p = params.filterValues { it != null }.mapValues { it.value.toString() }
        return checked(Http.getJson(buildUrl(base, if (wbi) Wbi.sign(p) else p), headers))
    }

    private fun checked(json: JSONObject): JSONObject {
        val code = json.optInt("code", 0)
        if (code != 0) throw ApiException(code, errorMessage(code, json.optString("message")))
        return json
    }

    /** Android App 介面的 appkey 簽名（UP 主投稿列表用，Web 版介面風控太嚴） */
    private fun appSign(params: Map<String, String>): Map<String, String> {
        val p = TreeMap(params)
        p["appkey"] = APP_KEY
        p["ts"] = (System.currentTimeMillis() / 1000).toString()
        val query = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        p["sign"] = Wbi.md5(query + APP_SEC)
        return p
    }

    private fun errorMessage(code: Int, msg: String) = when (code) {
        -101 -> "帳號未登入"
        -352, -412 -> "請求被 B 站風控攔截，請稍後再試 ($code)"
        -404 -> "影片不存在或已刪除"
        62002 -> "影片不可見"
        62004 -> "影片審核中"
        else -> "${msg.ifEmpty { "未知錯誤" }} ($code)"
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }

    private fun archive(o: JSONObject): VideoItem {
        val stat = o.optJSONObject("stat")
        return VideoItem(
            bvid = o.optString("bvid"),
            aid = o.optLong("aid").takeIf { it != 0L } ?: o.optLong("id"),
            cid = o.optLong("cid"),
            title = o.optString("title"),
            cover = o.optString("pic"),
            author = o.optJSONObject("owner")?.optString("name").orEmpty(),
            duration = Fmt.duration(o.optInt("duration")),
            views = Fmt.count(stat?.optLong("view") ?: 0),
            danmaku = Fmt.count(stat?.optLong("danmaku") ?: 0),
        )
    }

    // ---------- 帳號 ----------

    suspend fun nav(): UserInfo {
        val json = Http.getJson("$API/x/web-interface/nav".toHttpUrl())
        val d = json.optJSONObject("data") ?: return UserInfo(false, 0, "", "", 0, false)
        d.optJSONObject("wbi_img")?.let { Wbi.update(it.optString("img_url"), it.optString("sub_url")) }
        return UserInfo(
            isLogin = d.optBoolean("isLogin"),
            mid = d.optLong("mid"),
            name = d.optString("uname"),
            face = d.optString("face"),
            level = d.optJSONObject("level_info")?.optInt("current_level") ?: 0,
            vip = d.optInt("vipStatus") == 1,
        )
    }

    /** 沒有 buvid3 時搜尋等介面會回傳 -412。 */
    suspend fun ensureBuvid() {
        if (Http.cookies.get("buvid3") != null) return
        val d = get("$API/x/frontend/finger/spi").getJSONObject("data")
        Http.cookies.put("buvid3", d.getString("b_3"))
        Http.cookies.put("buvid4", d.getString("b_4"))
        Http.cookies.put("b_nut", (System.currentTimeMillis() / 1000).toString())
    }

    suspend fun qrGenerate(): Pair<String, String> {
        val d = get("$PASSPORT/x/passport-login/web/qrcode/generate").getJSONObject("data")
        return d.getString("url") to d.getString("qrcode_key")
    }

    /** 回傳狀態碼：0 成功、86101 未掃描、86090 已掃描待確認、86038 已過期。 */
    suspend fun qrPoll(key: String): Int {
        val d = get("$PASSPORT/x/passport-login/web/qrcode/poll", mapOf("qrcode_key" to key)).getJSONObject("data")
        val code = d.optInt("code", -1)
        if (code == 0) saveLoginCookies(d.optString("url"))
        return code
    }

    /** Set-Cookie 通常已經被 CookieJar 存下，這裡從回傳網址補上缺少的。 */
    private fun saveLoginCookies(url: String) {
        val query = url.substringAfter('?', "")
        for (pair in query.split('&')) {
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (name in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5") &&
                value.isNotEmpty() && Http.cookies.get(name) == null
            ) {
                Http.cookies.put(name, value)
            }
        }
    }

    // ---------- 影片列表 ----------

    suspend fun recommend(index: Int): List<VideoItem> {
        val d = get(
            "$API/x/web-interface/wbi/index/top/feed/rcmd",
            mapOf(
                "web_location" to 1430650, "feed_version" to "V8", "fresh_type" to 4, "ps" to 24,
                "fresh_idx" to index, "fresh_idx_1h" to index, "brush" to index,
                "y_num" to 4, "last_y_num" to 4,
            ),
            wbi = true,
        ).getJSONObject("data")
        return d.optJSONArray("item").objects()
            .filter { it.optString("goto") == "av" && it.optString("bvid").isNotEmpty() }
            .map(::archive)
    }

    /** 回傳列表與是否已經沒有更多。 */
    suspend fun popular(page: Int): Pair<List<VideoItem>, Boolean> {
        val d = get("$API/x/web-interface/popular", mapOf("pn" to page, "ps" to 24)).getJSONObject("data")
        return d.optJSONArray("list").objects().map(::archive) to d.optBoolean("no_more")
    }

    suspend fun ranking(): List<VideoItem> {
        val d = get("$API/x/web-interface/ranking/v2", mapOf("rid" to 0, "type" to "all"), wbi = true)
            .getJSONObject("data")
        return d.optJSONArray("list").objects().mapIndexed { i, o -> archive(o).copy(extra = "#${i + 1}") }
    }

    /** 回傳列表與總頁數。 */
    suspend fun search(keyword: String, page: Int): Pair<List<VideoItem>, Int> {
        ensureBuvid()
        val d = get(
            "$API/x/web-interface/wbi/search/type",
            mapOf("search_type" to "video", "keyword" to keyword, "page" to page),
            wbi = true,
        ).getJSONObject("data")
        val list = d.optJSONArray("result").objects().filter { it.optString("bvid").isNotEmpty() }.map { o ->
            VideoItem(
                bvid = o.optString("bvid"),
                aid = o.optLong("aid"),
                title = HtmlCompat.fromHtml(o.optString("title"), HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                cover = o.optString("pic"),
                author = o.optString("author"),
                duration = o.optString("duration"),
                views = Fmt.count(o.optLong("play")),
                danmaku = Fmt.count(o.optLong("video_review")),
            )
        }
        return list to d.optInt("numPages", 1)
    }

    suspend fun hotSearch(): List<String> = try {
        get("$API/x/web-interface/wbi/search/square", mapOf("limit" to 12), wbi = true)
            .getJSONObject("data").getJSONObject("trending").optJSONArray("list").objects()
            .map { it.optString("show_name").ifEmpty { it.optString("keyword") } }
    } catch (e: Exception) {
        Http.getJson("https://s.search.bilibili.com/main/hotword".toHttpUrl())
            .optJSONArray("list").objects().map { it.optString("keyword") }
    }.filter { it.isNotBlank() }

    data class DynamicPage(val items: List<VideoItem>, val offset: String, val hasMore: Boolean)

    suspend fun dynamic(offset: String, page: Int): DynamicPage {
        val d = get(
            "$API/x/polymer/web-dynamic/v1/feed/all",
            mapOf("type" to "video", "offset" to offset.ifEmpty { null }, "page" to page, "timezone_offset" to -480),
        ).getJSONObject("data")
        val list = d.optJSONArray("items").objects().mapNotNull { item ->
            val modules = item.optJSONObject("modules") ?: return@mapNotNull null
            val author = modules.optJSONObject("module_author")
            val arc = modules.optJSONObject("module_dynamic")?.optJSONObject("major")?.optJSONObject("archive")
                ?: return@mapNotNull null
            val stat = arc.optJSONObject("stat")
            VideoItem(
                bvid = arc.optString("bvid"),
                aid = arc.optString("aid").toLongOrNull() ?: 0,
                title = arc.optString("title"),
                cover = arc.optString("cover"),
                author = author?.optString("name").orEmpty(),
                duration = arc.optString("duration_text"),
                views = stat?.optString("play").orEmpty(),
                danmaku = stat?.optString("danmaku").orEmpty(),
                extra = author?.optString("pub_time").orEmpty(),
            )
        }.filter { it.bvid.isNotEmpty() }
        return DynamicPage(list, d.optString("offset"), d.optBoolean("has_more"))
    }

    data class HistoryPage(val items: List<VideoItem>, val max: Long, val viewAt: Long)

    suspend fun history(max: Long, viewAt: Long): HistoryPage {
        val d = get(
            "$API/x/web-interface/history/cursor",
            mapOf(
                "ps" to 24, "type" to "archive",
                "max" to max.takeIf { it > 0 }, "view_at" to viewAt.takeIf { it > 0 },
                "business" to if (max > 0) "archive" else null,
            ),
        ).getJSONObject("data")
        val list = d.optJSONArray("list").objects()
            .filter { it.optJSONObject("history")?.optString("business") == "archive" }
            .map { o ->
                val h = o.getJSONObject("history")
                val dur = o.optInt("duration")
                val prog = o.optInt("progress")
                VideoItem(
                    bvid = h.optString("bvid"),
                    aid = h.optLong("oid"),
                    cid = h.optLong("cid"),
                    title = o.optString("title"),
                    cover = o.optString("cover").ifEmpty { o.optJSONArray("covers")?.optString(0).orEmpty() },
                    author = o.optString("author_name"),
                    duration = Fmt.duration(dur),
                    extra = Fmt.relative(o.optLong("view_at")),
                    progress = when {
                        prog < 0 -> 1f
                        dur > 0 -> (prog.toFloat() / dur).coerceIn(0f, 1f)
                        else -> 0f
                    },
                )
            }
            .filter { it.bvid.isNotEmpty() }
        val cursor = d.optJSONObject("cursor")
        return HistoryPage(list, cursor?.optLong("max") ?: 0, cursor?.optLong("view_at") ?: 0)
    }

    // ---------- 影片詳情 ----------

    suspend fun view(bvid: String): VideoDetail {
        val d = get("$API/x/web-interface/wbi/view", mapOf("bvid" to bvid), wbi = true).getJSONObject("data")
        val stat = d.optJSONObject("stat")
        val owner = d.optJSONObject("owner")
        return VideoDetail(
            bvid = d.optString("bvid"),
            aid = d.optLong("aid"),
            title = d.optString("title"),
            cover = d.optString("pic"),
            desc = d.optString("desc"),
            ownerName = owner?.optString("name").orEmpty(),
            ownerFace = owner?.optString("face").orEmpty(),
            views = stat?.optLong("view") ?: 0,
            danmaku = stat?.optLong("danmaku") ?: 0,
            likes = stat?.optLong("like") ?: 0,
            coins = stat?.optLong("coin") ?: 0,
            favorites = stat?.optLong("favorite") ?: 0,
            pubdate = d.optLong("pubdate"),
            pages = d.optJSONArray("pages").objects().map {
                Page(it.optLong("cid"), it.optInt("page"), it.optString("part"), it.optInt("duration"))
            },
            ownerMid = owner?.optLong("mid") ?: 0,
            season = d.optJSONObject("ugc_season")?.let(::season),
        )
    }

    private fun season(s: JSONObject): Season? {
        val episodes = s.optJSONArray("sections").objects().flatMap { sec ->
            sec.optJSONArray("episodes").objects().map { e ->
                val arc = e.optJSONObject("arc")
                Episode(
                    bvid = e.optString("bvid"),
                    aid = e.optLong("aid"),
                    cid = e.optLong("cid"),
                    title = e.optString("title"),
                    cover = arc?.optString("pic").orEmpty(),
                    duration = arc?.optInt("duration") ?: e.optJSONObject("page")?.optInt("duration") ?: 0,
                    views = arc?.optJSONObject("stat")?.optLong("view") ?: 0,
                )
            }
        }.filter { it.bvid.isNotEmpty() }
        if (episodes.size < 2) return null
        return Season(s.optLong("id"), s.optString("title"), episodes)
    }

    suspend fun related(bvid: String): List<VideoItem> =
        get("$API/x/web-interface/archive/related", mapOf("bvid" to bvid))
            .optJSONArray("data").objects().map(::archive).filter { it.bvid.isNotEmpty() }

    // ---------- UP 主 ----------

    suspend fun upInfo(mid: Long): UpInfo {
        val d = get("$API/x/web-interface/card", mapOf("mid" to mid, "photo" to false)).getJSONObject("data")
        val c = d.getJSONObject("card")
        val live = runCatching { liveRoomOf(mid) }.getOrNull()
        return UpInfo(
            mid = mid,
            name = c.optString("name"),
            face = c.optString("face"),
            sign = c.optString("sign"),
            fans = c.optLong("fans"),
            following = c.optLong("attention"),
            level = c.optJSONObject("level_info")?.optInt("current_level") ?: 0,
            vip = c.optJSONObject("vip")?.optInt("status") == 1,
            archiveCount = d.optInt("archive_count"),
            isFollowing = d.optBoolean("following"),
            liveRoomId = live?.optLong("roomid") ?: 0,
            isLive = live?.optInt("liveStatus") == 1,
            liveTitle = live?.optString("title").orEmpty(),
        )
    }

    private suspend fun liveRoomOf(mid: Long): JSONObject? =
        get("$LIVE/room/v1/Room/getRoomInfoOld", mapOf("mid" to mid), headers = LIVE_HEADERS).optJSONObject("data")

    data class UpVideosPage(val items: List<VideoItem>, val hasNext: Boolean)

    /** UP 主投稿，依發布時間；以上一頁最後一部的 aid 當游標 */
    suspend fun upVideos(mid: Long, lastAid: Long): UpVideosPage {
        val p = mutableMapOf(
            "vmid" to "$mid", "order" to "pubdate", "ps" to "30",
            "build" to "8000000", "mobi_app" to "android", "platform" to "android",
        )
        if (lastAid > 0) p["aid"] = "$lastAid"
        val json = checked(
            Http.getJson(buildUrl("https://app.bilibili.com/x/v2/space/archive/cursor", appSign(p)), mapOf("User-Agent" to APP_UA))
        )
        val d = json.getJSONObject("data")
        val items = d.optJSONArray("item").objects().filter { it.optString("bvid").isNotEmpty() }.map { o ->
            VideoItem(
                bvid = o.optString("bvid"),
                aid = o.optString("param").toLongOrNull() ?: 0,
                cid = o.optLong("first_cid"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                author = "",
                duration = Fmt.duration(o.optInt("duration")),
                views = Fmt.count(o.optLong("play")),
                danmaku = Fmt.count(o.optLong("danmaku")),
                extra = o.optString("publish_time_text").ifEmpty { Fmt.relative(o.optLong("ctime")) },
            )
        }
        return UpVideosPage(items, d.optBoolean("has_next") && items.isNotEmpty())
    }

    suspend fun setFollow(mid: Long, follow: Boolean) {
        val csrf = Http.cookies.get("bili_jct") ?: throw ApiException(-101, "帳號未登入")
        checked(
            Http.postForm(
                "$API/x/relation/modify",
                mapOf("fid" to mid, "act" to if (follow) 1 else 2, "re_src" to 11, "csrf" to csrf),
            )
        )
    }

    // ---------- 直播 ----------

    private fun liveItem(o: JSONObject, cover: String, online: String, area: String) = VideoItem(
        bvid = "",
        liveRoomId = o.optLong("roomid"),
        title = o.optString("title"),
        cover = cover,
        author = o.optString("uname"),
        views = online,
        extra = area,
    )

    suspend fun liveRecommend(page: Int): List<VideoItem> {
        val d = get(
            "$LIVE/xlive/web-interface/v1/second/getUserRecommend",
            mapOf("page" to page, "page_size" to 30, "platform" to "web", "web_location" to "333.1007"),
            wbi = true,
            headers = LIVE_HEADERS,
        ).getJSONObject("data")
        return d.optJSONArray("list").objects().map { o ->
            liveItem(
                o,
                cover = o.optString("user_cover").ifEmpty { o.optString("cover") }.ifEmpty { o.optString("system_cover") },
                online = Fmt.count(o.optLong("online")),
                area = o.optString("area_v2_name").ifEmpty { o.optString("area_name") },
            )
        }
    }

    data class LiveFollowingPage(val items: List<VideoItem>, val hasMore: Boolean)

    /** 關注的主播，清單是開播中的排在前面 */
    suspend fun liveFollowing(page: Int): LiveFollowingPage {
        val d = get(
            "$LIVE/xlive/web-ucenter/user/following",
            mapOf("page" to page, "page_size" to 10, "ignoreRecord" to 1, "hit_ab" to true),
            headers = LIVE_HEADERS,
        ).getJSONObject("data")
        val all = d.optJSONArray("list").objects()
        val live = all.filter { it.optInt("live_status") == 1 }
        val items = live.map { o ->
            liveItem(o, cover = o.optString("room_cover"), online = o.optString("text_small"), area = o.optString("area_name_v2"))
        }
        val hasMore = all.isNotEmpty() && live.size == all.size && page < d.optInt("totalPage", 1)
        return LiveFollowingPage(items, hasMore)
    }

    data class LiveRoom(val title: String, val uid: Long, val online: Long, val area: String, val liveStatus: Int)

    suspend fun liveRoom(roomId: Long): LiveRoom {
        val d = get("$LIVE/room/v1/Room/get_info", mapOf("room_id" to roomId), headers = LIVE_HEADERS).getJSONObject("data")
        return LiveRoom(d.optString("title"), d.optLong("uid"), d.optLong("online"), d.optString("area_name"), d.optInt("live_status"))
    }

    suspend fun liveAnchorName(roomId: Long): String =
        get("$LIVE/live_user/v1/UserInfo/get_anchor_in_room", mapOf("roomid" to roomId), headers = LIVE_HEADERS)
            .optJSONObject("data")?.optJSONObject("info")?.optString("uname").orEmpty()

    /**
     * 只選 AVC（電視盒子相容性最好）。格式優先 HLS-fmp4 → HLS-ts → FLV：
     * 有些房間的 HLS-ts 是轉碼流，playlist 會回 404。
     */
    suspend fun livePlay(roomId: Long, qn: Int): LivePlay {
        val d = get(
            "$LIVE/xlive/web-room/v2/index/getRoomPlayInfo",
            mapOf(
                "room_id" to roomId, "protocol" to "0,1", "format" to "0,1,2", "codec" to "0",
                "qn" to qn, "platform" to "web", "ptype" to 8,
            ),
            headers = LIVE_HEADERS,
        ).getJSONObject("data")
        val status = d.optInt("live_status")
        val realId = d.optLong("room_id").takeIf { it > 0 } ?: roomId
        val pu = d.optJSONObject("playurl_info")?.optJSONObject("playurl")
            ?: return LivePlay(realId, status, emptyList(), 0, emptyList())
        val names = pu.optJSONArray("g_qn_desc").objects().associate { it.optInt("qn") to it.optString("desc") }

        class Candidate(val protocol: String, val format: String, val codec: JSONObject)
        val candidates = mutableListOf<Candidate>()
        for (s in pu.optJSONArray("stream").objects()) {
            for (f in s.optJSONArray("format").objects()) {
                for (c in f.optJSONArray("codec").objects()) {
                    if (c.optString("codec_name") == "avc") {
                        candidates += Candidate(s.optString("protocol_name"), f.optString("format_name"), c)
                    }
                }
            }
        }
        fun rank(c: Candidate) = when {
            c.protocol == "http_hls" && c.format == "fmp4" -> 0
            c.protocol == "http_hls" -> 1
            else -> 2
        }
        val ordered = candidates.sortedBy(::rank)
        val first = ordered.firstOrNull() ?: throw IOException("沒有可用的直播串流")
        val sources = ordered.flatMap { cand ->
            val base = cand.codec.optString("base_url")
            cand.codec.optJSONArray("url_info").objects().map {
                LiveSource(it.optString("host") + base + it.optString("extra"), cand.protocol == "http_hls")
            }
        }
        val acceptArr = first.codec.optJSONArray("accept_qn")
        val accept = if (acceptArr == null) emptyList() else (0 until acceptArr.length()).map { acceptArr.optInt(it) }
        return LivePlay(
            roomId = realId,
            liveStatus = status,
            qualities = accept.map { it to (names[it] ?: "$it") },
            quality = first.codec.optInt("current_qn"),
            sources = sources,
        )
    }

    // ---------- 播放 ----------

    private fun stream(o: JSONObject) = Stream(
        id = o.optInt("id"),
        urls = (listOf(o.optString("baseUrl").ifEmpty { o.optString("base_url") }) +
            (o.optJSONArray("backupUrl") ?: o.optJSONArray("backup_url")).strings()).filter { it.isNotEmpty() },
        bandwidth = o.optLong("bandwidth"),
        codecid = o.optInt("codecid"),
    )

    suspend fun playUrl(bvid: String, cid: Long): PlayInfo {
        val params = mutableMapOf<String, Any?>(
            "bvid" to bvid, "cid" to cid, "qn" to 127, "fnval" to 4048, "fnver" to 0, "fourk" to 1,
        )
        if (!Session.isLogin) params["try_look"] = 1
        val d = get("$API/x/player/wbi/playurl", params, wbi = true).getJSONObject("data")
        val dash = d.optJSONObject("dash")
        val videos = dash?.optJSONArray("video").objects().map(::stream).filter { it.urls.isNotEmpty() }
        val audios = dash?.optJSONArray("audio").objects().map(::stream).filter { it.urls.isNotEmpty() }
        val durl = d.optJSONArray("durl").objects().map { it.optString("url") }.filter { it.isNotEmpty() }

        val names = mutableMapOf<Int, String>()
        val acceptQ = d.optJSONArray("accept_quality")
        val acceptD = d.optJSONArray("accept_description")
        if (acceptQ != null) for (i in 0 until acceptQ.length()) {
            names[acceptQ.optInt(i)] = acceptD?.optString(i).orEmpty()
        }
        val ids = if (videos.isNotEmpty()) videos.map { it.id }.distinct() else listOf(d.optInt("quality"))
        val qualities = ids.sortedDescending().map { it to (names[it]?.ifEmpty { null } ?: Prefs.qualityName(it)) }
        return PlayInfo(videos, audios, durl, qualities)
    }

    /** 上次播放的 cid 與位置（毫秒）。 */
    suspend fun lastPlay(bvid: String, cid: Long): Pair<Long, Long>? {
        val d = get("$API/x/player/wbi/v2", mapOf("bvid" to bvid, "cid" to cid), wbi = true).getJSONObject("data")
        val time = d.optLong("last_play_time")
        return if (time > 0) d.optLong("last_play_cid") to time else null
    }

    suspend fun reportHistory(aid: Long, cid: Long, progressSec: Long) {
        val csrf = Http.cookies.get("bili_jct") ?: return
        Http.postForm(
            "$API/x/v2/history/report",
            mapOf("aid" to aid, "cid" to cid, "progress" to progressSec, "platform" to "web", "csrf" to csrf),
        )
    }

    // ---------- 彈幕 ----------

    private val DANMAKU_RE = Regex("<d p=\"([^\"]+)\">([^<]*)</d>")

    suspend fun danmaku(cid: Long): List<Danmaku> {
        val bytes = Http.getBytes("$API/x/v1/dm/list.so?oid=$cid".toHttpUrl())
        return withContext(Dispatchers.Default) {
            val xml = if (bytes.isNotEmpty() && bytes[0] == '<'.code.toByte()) String(bytes) else inflate(bytes)
            DANMAKU_RE.findAll(xml).mapNotNull { m ->
                val p = m.groupValues[1].split(',')
                if (p.size < 4) return@mapNotNull null
                val time = p[0].toFloatOrNull() ?: return@mapNotNull null
                Danmaku(
                    timeMs = (time * 1000).toLong(),
                    mode = p[1].toIntOrNull() ?: 1,
                    color = (p[3].toLongOrNull() ?: 0xFFFFFF).toInt() or 0xFF000000.toInt(),
                    text = unescapeXml(m.groupValues[2]),
                )
            }.sortedBy { it.timeMs }.toList()
        }
    }

    private fun inflate(data: ByteArray): String {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }

    private fun unescapeXml(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
}
