package com.bilitv.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val REFERER = "https://www.bilibili.com/"

    lateinit var cookies: CookieStore
        private set
    lateinit var client: OkHttpClient
        private set

    fun init(context: Context) {
        cookies = CookieStore(context)
        client = OkHttpClient.Builder()
            .cookieJar(cookies)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.header("User-Agent") == null) b.header("User-Agent", UA)
                if (req.header("Referer") == null) b.header("Referer", REFERER)
                chain.proceed(b.build())
            }
            .build()
    }

    suspend fun getJson(url: HttpUrl, headers: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            parse(client.newCall(req).execute())
        }

    suspend fun postForm(url: String, form: Map<String, Any?>): JSONObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> if (v != null) add(k, v.toString()) }
        }.build()
        parse(client.newCall(Request.Builder().url(url).post(body).build()).execute())
    }

    suspend fun getBytes(url: HttpUrl): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body!!.bytes()
        }
    }

    private fun parse(response: Response): JSONObject = response.use { r ->
        val text = r.body?.string().orEmpty()
        try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("HTTP ${r.code}")
        }
    }
}
