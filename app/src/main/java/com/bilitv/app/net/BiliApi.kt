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
import java.util.zip.Inflater

object BiliApi {
    private const val API = "https://api.bilibili.com"
    private const val PASSPORT = "https://passport.bilibili.com"

    // ---------- 共用 ----------

    private fun buildUrl(base: String, params: Map<String, String>): HttpUrl {
        val b = base.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun get(base: String, params: Map<String, Any?> = emptyMap(), wbi: Boolean = false): JSONObject {
        val p = params.filterValues { it != null }.mapValues { it.value.toString() }
        val json = Http.getJson(buildUrl(base, if (wbi) Wbi.sign(p) else p))
        val code = json.optInt("code", 0)
        if (code != 0) throw ApiException(code, errorMessage(code, json.optString("message")))
        return json
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
        )
    }

    suspend fun related(bvid: String): List<VideoItem> =
        get("$API/x/web-interface/archive/related", mapOf("bvid" to bvid))
            .optJSONArray("data").objects().map(::archive).filter { it.bvid.isNotEmpty() }

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
