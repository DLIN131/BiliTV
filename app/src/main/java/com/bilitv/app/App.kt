package com.bilitv.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bilitv.app.net.BiliApi
import com.bilitv.app.net.Http
import com.bilitv.app.net.UserInfo
import com.bilitv.app.util.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        Prefs.init(this)
        Http.init(this)
        scope.launch {
            runCatching { BiliApi.ensureBuvid() }
            Session.refresh()
        }
    }

    companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

/** 登入狀態。generation 在登入／登出時遞增，讓各列表重新載入。 */
object Session {
    var user by mutableStateOf<UserInfo?>(null)
        private set
    var generation by mutableIntStateOf(0)
        private set

    val isLogin: Boolean get() = user?.isLogin == true

    suspend fun refresh() {
        runCatching { BiliApi.nav() }.onSuccess { user = it }
    }

    suspend fun onLoggedIn() {
        refresh()
        generation++
    }

    fun logout() {
        Http.cookies.clear()
        user = null
        generation++
        App.scope.launch { runCatching { BiliApi.ensureBuvid() } }
    }
}

object Prefs {
    val QUALITIES get() = listOf(
        127 to Strings.get(R.string.quality_8k), 120 to Strings.get(R.string.quality_4k), 116 to Strings.get(R.string.quality_1080p60), 112 to Strings.get(R.string.quality_1080p_high),
        80 to Strings.get(R.string.quality_1080p), 64 to Strings.get(R.string.quality_720p), 32 to Strings.get(R.string.quality_480p), 16 to Strings.get(R.string.quality_360p),
    )
    val CODECS get() = listOf(7 to Strings.get(R.string.codec_avc), 12 to "HEVC (H.265)", 13 to "AV1")

    fun qualityName(qn: Int) = QUALITIES.firstOrNull { it.first == qn }?.second ?: "$qn"
    fun codecName(id: Int) = CODECS.firstOrNull { it.first == id }?.second ?: "$id"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var quality: Int
        get() = sp.getInt("quality", 80)
        set(v) = sp.edit().putInt("quality", v).apply()

    var codec: Int
        get() = sp.getInt("codec", 7)
        set(v) = sp.edit().putInt("codec", v).apply()

    var danmaku: Boolean
        get() = sp.getBoolean("danmaku", true)
        set(v) = sp.edit().putBoolean("danmaku", v).apply()
}
