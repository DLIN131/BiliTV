package com.bilitv.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.bilitv.app.Prefs
import com.bilitv.app.Session
import com.bilitv.app.net.BiliApi
import com.bilitv.app.util.Fmt
import com.bilitv.app.util.QrCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun MineScreen() {
    val user = Session.user
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle("我的")
        Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
            if (user?.isLogin == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = Fmt.image(user.face, 192, 192),
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(CardBg),
                    )
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text(user.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "UID ${user.mid}  ·  LV${user.level}" + if (user.vip) "  ·  大會員" else "",
                            fontSize = 15.sp,
                            color = if (user.vip) Pink else Gray,
                        )
                    }
                    Spacer(Modifier.width(40.dp))
                    BiliButton(onClick = { Session.logout() }) { Text("登出") }
                }
            } else {
                LoginPanel()
            }

            Spacer(Modifier.height(40.dp))
            Text("播放設定", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            SettingsPanel()
            Spacer(Modifier.height(24.dp))
            Text(
                "提示：未登入時只能播放較低畫質；1080P 高碼率、4K 等需要大會員。若影片無法播放，請把編碼改回 AVC。",
                fontSize = 14.sp,
                color = Gray,
            )
        }
    }
}

@Composable
private fun SettingsPanel() {
    var quality by remember { mutableIntStateOf(Prefs.quality) }
    var codec by remember { mutableIntStateOf(Prefs.codec) }
    var danmaku by remember { mutableStateOf(Prefs.danmaku) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BiliButton(onClick = {
            val ids = Prefs.QUALITIES.map { it.first }
            quality = ids[(ids.indexOf(quality) + 1) % ids.size]
            Prefs.quality = quality
        }) { Text("預設畫質：${Prefs.qualityName(quality)}") }

        BiliButton(onClick = {
            val ids = Prefs.CODECS.map { it.first }
            codec = ids[(ids.indexOf(codec) + 1) % ids.size]
            Prefs.codec = codec
        }) { Text("影片編碼：${Prefs.codecName(codec)}") }

        BiliButton(onClick = {
            danmaku = !danmaku
            Prefs.danmaku = danmaku
        }) { Text("預設顯示彈幕：${if (danmaku) "開" else "關"}") }
    }
}

@Composable
private fun LoginPanel() {
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("正在取得登入二維碼…") }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        qr = null
        status = "正在取得登入二維碼…"
        try {
            val (url, key) = BiliApi.qrGenerate()
            qr = QrCode.bitmap(url, 480)
            status = "請用手機上的嗶哩嗶哩 App 掃描二維碼"
            while (true) {
                delay(2000)
                when (BiliApi.qrPoll(key)) {
                    0 -> {
                        status = "登入成功！"
                        Session.onLoggedIn()
                        return@LaunchedEffect
                    }
                    86090 -> status = "已掃描，請在手機上確認登入"
                    86038 -> {
                        status = "二維碼已過期，請重新整理"
                        qr = null
                        return@LaunchedEffect
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "錯誤：${e.message}"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = qr
            if (bmp != null) {
                Image(bmp, contentDescription = "登入二維碼", modifier = Modifier.size(240.dp))
            } else {
                Loading(size = 40)
            }
        }
        Spacer(Modifier.width(36.dp))
        Column {
            Text("掃碼登入", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 17.sp, color = Gray)
            Spacer(Modifier.height(8.dp))
            Text("登入後可以看關注動態、歷史紀錄，並同步播放進度與更高畫質。", fontSize = 14.sp, color = Gray)
            Spacer(Modifier.height(20.dp))
            BiliButton(onClick = { round++ }) { Text("重新整理二維碼") }
        }
    }
}
