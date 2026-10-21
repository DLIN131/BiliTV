package com.bilitv.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun count(n: Long): String = when {
        n >= 100_000_000 -> "%.1f億".format(n / 1e8)
        n >= 10_000 -> "%.1f萬".format(n / 1e4)
        else -> n.toString()
    }

    fun duration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun ms(ms: Long): String = duration((ms / 1000).toInt()).ifEmpty { "00:00" }

    fun date(sec: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sec * 1000))

    fun relative(sec: Long): String {
        if (sec <= 0) return ""
        val diff = System.currentTimeMillis() / 1000 - sec
        return when {
            diff < 60 -> "剛剛"
            diff < 3600 -> "${diff / 60} 分鐘前"
            diff < 86400 -> "${diff / 3600} 小時前"
            diff < 2 * 86400 -> "昨天"
            diff < 7 * 86400 -> "${diff / 86400} 天前"
            else -> date(sec)
        }
    }

    /** 轉成 https 並加上 B 站圖片縮放參數，減少 TV 盒子記憶體與流量。 */
    fun image(url: String, w: Int, h: Int): String {
        if (url.isBlank()) return url
        var u = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
        if (u.contains("hdslb.com") && !u.contains('@')) u += "@${w}w_${h}h_1c.webp"
        return u
    }
}
