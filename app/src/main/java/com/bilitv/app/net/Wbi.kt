package com.bilitv.app.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap

/** B 站 Web 介面的 WBI 簽名。 */
object Wbi {
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )
    private const val TTL_MS = 3600_000L

    @Volatile private var mixinKey: String? = null
    @Volatile private var updatedAt = 0L
    private val lock = Mutex()

    fun update(imgUrl: String, subUrl: String) {
        val raw = fileName(imgUrl) + fileName(subUrl)
        if (raw.length < 64) return
        mixinKey = MIXIN_TAB.map { raw[it] }.joinToString("").substring(0, 32)
        updatedAt = System.currentTimeMillis()
    }

    private fun fileName(url: String) = url.substringAfterLast('/').substringBefore('.')

    private fun fresh(): String? = mixinKey?.takeIf { System.currentTimeMillis() - updatedAt < TTL_MS }

    private suspend fun key(): String {
        fresh()?.let { return it }
        lock.withLock {
            if (fresh() == null) {
                val json = Http.getJson("https://api.bilibili.com/x/web-interface/nav".toHttpUrl())
                val img = json.getJSONObject("data").getJSONObject("wbi_img")
                update(img.getString("img_url"), img.getString("sub_url"))
            }
        }
        return mixinKey ?: throw IOException("無法取得 WBI 金鑰")
    }

    suspend fun sign(params: Map<String, String>): Map<String, String> {
        val key = key()
        val p = TreeMap<String, String>()
        params.forEach { (k, v) -> p[k] = v.filterNot { it in "!'()*" } }
        p["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = p.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        p["w_rid"] = md5(query + key)
        return p
    }

    private fun encode(s: String) =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
