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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
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
    val QUALITIES = listOf(
        127 to "8K 超高清", 120 to "4K 超清", 116 to "1080P 60幀", 112 to "1080P 高碼率",
        80 to "1080P 高清", 64 to "720P 高清", 32 to "480P 清晰", 16 to "360P 流暢",
    )
    val CODECS = listOf(7 to "AVC (H.264，相容性最好)", 12 to "HEVC (H.265)", 13 to "AV1")

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
