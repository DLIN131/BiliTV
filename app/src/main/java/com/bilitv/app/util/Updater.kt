package com.bilitv.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.bilitv.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * App 內更新：讀 GitHub 最新 Release，tag 為 v1.0.<versionCode>（CI 的 run_number）。
 */
object Updater {
    private const val LATEST_API = "https://api.github.com/repos/DLIN131/BiliTV/releases/latest"

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    /** 有新版本時設定，主畫面會跳出更新視窗 */
    var pending by mutableStateOf<Release?>(null)

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = "v" + BuildConfig.VERSION_NAME

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST_API).header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            val tag = o.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@use null
            val assets = o.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            url?.let { Release(code, o.optString("name").ifEmpty { tag }, cleanNotes(o.optString("body")), it) }
        }
    }

    /** 發布說明裡 `---` 以下是固定的下載資訊，Co-Authored-By 也不用給使用者看 */
    private fun cleanNotes(body: String) = body
        .substringBefore("\n---")
        .lines()
        .filterNot { it.startsWith("Co-Authored-By") }
        .joinToString("\n")
        .trim()

    /** 有比目前新的版本才回傳 */
    suspend fun check(): Release? = fetchLatest()?.takeIf { it.versionCode > currentVersionCode }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "BiliTV-${release.versionCode}.apk")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = (read * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
            }
            file
        }

    /** Android 8 以上要使用者允許本 App「安裝不明應用程式」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
