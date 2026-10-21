package com.bilitv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.bilitv.app.util.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateDialog(release: Updater.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) } // null = 沒在下載
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val primary = remember { FocusRequester() }

    fun tryInstall(apk: File) {
        if (Updater.canInstall(context)) {
            message = null
            Updater.install(context, apk)
        } else {
            message = "請在接下來的設定畫面允許 BiliTV「安裝不明應用程式」，返回後再按「安裝」。"
            if (!Updater.openInstallPermissionSettings(context)) {
                message = "找不到設定頁面，請到「設定 → 安全性與限制 → 不明來源」允許 BiliTV，再回來按「安裝」。"
            }
        }
    }

    fun start() {
        if (progress != null) return
        file?.let {
            tryInstall(it)
            return
        }
        message = null
        progress = 0f
        job = scope.launch {
            try {
                val apk = Updater.download(context, release) { progress = it }
                file = apk
                progress = null
                tryInstall(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                message = "下載失敗：${e.message}"
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        onDismiss()
    }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { primary.requestFocus() }
    }

    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(600.dp)
                .background(CardBg, RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text("發現新版本", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("${Updater.currentVersionName}  →  ${release.name.removePrefix("BiliTV ")}", fontSize = 16.sp, color = Pink)
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("更新內容", fontSize = 15.sp, color = Gray)
                Spacer(Modifier.height(6.dp))
                Text(release.notes, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            progress?.let { p ->
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { p },
                    color = Pink,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("下載中 ${(p * 100).toInt()}%", fontSize = 14.sp, color = Gray)
            }
            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 15.sp, color = Gray)
            }
            Spacer(Modifier.height(24.dp))
            Row {
                BiliButton(onClick = ::start, modifier = Modifier.focusRequester(primary)) {
                    Text(
                        when {
                            progress != null -> "下載中…"
                            file != null -> "安裝"
                            else -> "立即更新"
                        }
                    )
                }
                Spacer(Modifier.width(16.dp))
                BiliButton(onClick = ::dismiss) { Text("稍後再說") }
            }
        }
    }
}
