package com.bilitv.app.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.bilitv.app.net.Http
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 播放器共用的網路設定 */
@OptIn(UnstableApi::class)
object MediaNet {
    /** 影片／直播串流：走和 API 相同的 OkHttp，帶上 B 站要求的 UA 與 Referer */
    fun dataSourceFactory(referer: String): DataSource.Factory =
        OkHttpDataSource.Factory(Http.mediaClient)
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(mapOf("Referer" to referer))

    /** 是不是網路／連線類錯誤（換線路有機會救回來） */
    fun isNetworkError(error: PlaybackException) = error.errorCode / 1000 == 2

    /** 把播放錯誤翻成看得懂的原因，方便回報問題 */
    fun describe(error: PlaybackException): String {
        var c: Throwable? = error.cause
        while (c != null) {
            when (c) {
                is HttpDataSource.InvalidResponseCodeException ->
                    return "伺服器回應 HTTP ${c.responseCode}（${c.dataSpec.uri.host}）"
                is SSLException -> return "安全連線失敗：${c.javaClass.simpleName}"
                is UnknownHostException -> return "找不到伺服器（DNS）：${c.message}"
                is SocketTimeoutException -> return "連線逾時"
            }
            c = c.cause
        }
        return error.errorCodeName
    }
}
