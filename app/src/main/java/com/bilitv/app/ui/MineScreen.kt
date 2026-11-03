package com.bilitv.app.ui

import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.bilitv.app.Prefs
import com.bilitv.app.R
import com.bilitv.app.Session
import com.bilitv.app.net.BiliApi
import com.bilitv.app.util.Fmt
import com.bilitv.app.util.QrCode
import com.bilitv.app.util.Strings
import com.bilitv.app.util.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MineScreen() {
    val user = Session.user
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle(stringResource(R.string.nav_mine))
        Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
            LanguagePanel()
            Spacer(Modifier.height(32.dp))
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
                            "UID ${user.mid}  ·  LV${user.level}" + if (user.vip) stringResource(R.string.vip_suffix) else "",
                            fontSize = 15.sp,
                            color = if (user.vip) Pink else Gray,
                        )
                    }
                    Spacer(Modifier.width(40.dp))
                    BiliButton(onClick = { Session.logout() }) { Text(stringResource(R.string.logout)) }
                }
            } else {
                LoginPanel()
            }

            Spacer(Modifier.height(40.dp))
            Text(stringResource(R.string.playback_settings), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            SettingsPanel()
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.playback_tip),
                fontSize = 14.sp,
                color = Gray,
            )

            Spacer(Modifier.height(40.dp))
            Text(stringResource(R.string.about), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            UpdatePanel()
        }
    }
}

@Composable
private fun LanguagePanel() {
    // AppCompat recreates activities and persists the selection, including on API 23–32.
    val selected = AppCompatDelegate.getApplicationLocales()
    val options = listOf(
        "" to R.string.language_system,
        "zh-Hans" to R.string.language_simplified,
        "zh-Hant" to R.string.language_traditional,
    )
    Text(stringResource(R.string.language_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        options.forEach { (tag, label) ->
            val locales = LocaleListCompat.forLanguageTags(tag)
            BiliButton(
                onClick = { AppCompatDelegate.setApplicationLocales(locales) },
                selected = selected == locales,
            ) { Text(stringResource(label)) }
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
        }) { Text(stringResource(R.string.default_quality, Prefs.qualityName(quality))) }

        BiliButton(onClick = {
            val ids = Prefs.CODECS.map { it.first }
            codec = ids[(ids.indexOf(codec) + 1) % ids.size]
            Prefs.codec = codec
        }) { Text(stringResource(R.string.video_codec, Prefs.codecName(codec))) }

        BiliButton(onClick = {
            danmaku = !danmaku
            Prefs.danmaku = danmaku
        }) { Text(stringResource(R.string.default_danmaku, if (danmaku) stringResource(R.string.on) else stringResource(R.string.off))) }
    }
}

@Composable
private fun UpdatePanel() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.current_version, Updater.currentVersionName), fontSize = 16.sp)
        Spacer(Modifier.width(24.dp))
        BiliButton(onClick = {
            if (checking) return@BiliButton
            checking = true
            result = null
            scope.launch {
                runCatching { Updater.check() }
                    .onSuccess { if (it != null) Updater.pending = it else result = Strings.get(R.string.up_to_date) }
                    .onFailure { result = Strings.get(R.string.update_check_failed, it.message) }
                checking = false
            }
        }) { Text(if (checking) stringResource(R.string.update_checking) else stringResource(R.string.check_updates)) }
        result?.let {
            Spacer(Modifier.width(16.dp))
            Text(it, fontSize = 15.sp, color = Gray)
        }
    }
}

@Composable
private fun LoginPanel() {
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf(Strings.get(R.string.login_loading)) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        qr = null
        status = Strings.get(R.string.login_loading)
        try {
            val (url, key) = BiliApi.qrGenerate()
            qr = QrCode.bitmap(url, 480)
            status = Strings.get(R.string.login_scan_prompt)
            while (true) {
                delay(2000)
                when (BiliApi.qrPoll(key)) {
                    0 -> {
                        status = Strings.get(R.string.login_success)
                        Session.onLoggedIn()
                        return@LaunchedEffect
                    }
                    86090 -> status = Strings.get(R.string.login_confirm_prompt)
                    86038 -> {
                        status = Strings.get(R.string.login_expired)
                        qr = null
                        return@LaunchedEffect
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = Strings.get(R.string.error_detail, e.message)
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
                Image(bmp, contentDescription = stringResource(R.string.login_qr_description), modifier = Modifier.size(240.dp))
            } else {
                Loading(size = 40)
            }
        }
        Spacer(Modifier.width(36.dp))
        Column {
            Text(stringResource(R.string.login_title), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 17.sp, color = Gray)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.login_benefits), fontSize = 14.sp, color = Gray)
            Spacer(Modifier.height(20.dp))
            BiliButton(onClick = { round++ }) { Text(stringResource(R.string.login_refresh)) }
        }
    }
}
