package com.bilitv.app.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** 把 Cookie 存在 SharedPreferences，登入狀態重開 App 後仍然有效。 */
class CookieStore(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val store = mutableListOf<Cookie>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = Cookie.Builder()
                    .name(o.getString("n"))
                    .value(o.getString("v"))
                    .path(o.optString("p", "/"))
                    .expiresAt(o.getLong("e"))
                if (o.optBoolean("h")) b.hostOnlyDomain(o.getString("d")) else b.domain(o.getString("d"))
                if (o.optBoolean("s")) b.secure()
                if (o.optBoolean("ho")) b.httpOnly()
                store += b.build()
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now) store += c
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt <= now }
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun get(name: String): String? = store.firstOrNull { it.name == name }?.value

    @Synchronized
    fun put(name: String, value: String) {
        store.removeAll { it.name == name }
        store += Cookie.Builder()
            .name(name)
            .value(value)
            .domain("bilibili.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
            .build()
        persist()
    }

    @Synchronized
    fun clear() {
        store.clear()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        for (c in store) {
            arr.put(
                JSONObject()
                    .put("n", c.name).put("v", c.value).put("d", c.domain).put("p", c.path)
                    .put("e", c.expiresAt).put("h", c.hostOnly).put("s", c.secure).put("ho", c.httpOnly)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
