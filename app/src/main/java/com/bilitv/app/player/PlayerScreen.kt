package com.bilitv.app.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.bilitv.app.net.Page
import com.bilitv.app.ui.BiliButton
import com.bilitv.app.ui.Gray
import com.bilitv.app.ui.Pink
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.delay

class PlayerUi {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var loading by mutableStateOf(true)
    var buffering by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var seekTarget by mutableStateOf<Long?>(null)
    var controlsVisible by mutableStateOf(true)
    var menuOpen by mutableStateOf(false)
    var hint by mutableStateOf<String?>(null)
    var qualities by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var quality by mutableIntStateOf(0)
    var speed by mutableFloatStateOf(1f)
    var danmakuOn by mutableStateOf(true)
    var pages by mutableStateOf<List<Page>>(emptyList())
    var pageIndex by mutableIntStateOf(0)
    /** 長按 OK 暫時 2 倍速 */
    var boosting by mutableStateOf(false)
}

val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(s: Float) = if (s == 1f) "正常" else "${s}x"

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    ui: PlayerUi,
    player: Player,
    danmakuView: DanmakuView,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onDanmaku: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(factory = { danmakuView }, modifier = Modifier.fillMaxSize())

        val err = ui.error
        if (err != null) {
            Text(
                err,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(28.dp),
            )
        } else if (ui.loading || ui.buffering) {
            CircularProgressIndicator(
                color = Pink,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp),
            )
        } else if (!ui.playing && ui.duration > 0 && !ui.menuOpen) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "暫停中", modifier = Modifier.size(60.dp))
            }
        }

        AnimatedVisibility(
            visible = ui.controlsVisible || ui.seekTarget != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(ui)
        }

        if (ui.boosting) {
            Text(
                "▶▶  2 倍速播放中",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 36.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        ui.hint?.let {
            Text(
                it,
                fontSize = 17.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 48.dp, top = 110.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        if (ui.menuOpen) {
            PlayerMenu(ui, onQuality, onSpeed, onDanmaku, onPage)
        }
    }
}

@Composable
private fun Controls(ui: PlayerUi) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp)
        ) {
            Text(ui.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(ui.subtitle, fontSize = 16.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 32.dp)
        ) {
            val pos = ui.seekTarget ?: ui.position
            val dur = ui.duration
            val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            val bufFrac = if (dur > 0) (ui.buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (ui.seekTarget != null) 8.dp else 5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(bufFrac)
                        .background(Color.White.copy(alpha = 0.35f))
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(frac)
                        .background(Pink)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.ms(pos)} / ${Fmt.ms(dur)}", fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Text("←→ 快退/快進    OK 暫停（長按 2 倍速）    ↑ 選單    ↓ 資訊    返回×2 離開", fontSize = 14.sp, color = Gray)
                Spacer(Modifier.width(20.dp))
                val q = ui.qualities.firstOrNull { it.first == ui.quality }?.second.orEmpty()
                Text(listOf(q, if (ui.speed != 1f) "${ui.speed}x" else "").filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 14.sp, color = Pink)
            }
        }
    }
}

@Composable
private fun PlayerMenu(
    ui: PlayerUi,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onDanmaku: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    val focusOnQuality = ui.qualities.any { it.first == ui.quality }

    Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(460.dp)
                .background(Color(0xF0141418))
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            Text("播放設定", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.qualities.isNotEmpty()) {
                    item {
                        MenuSection("畫質") {
                            ui.qualities.forEach { (id, name) ->
                                val sel = id == ui.quality
                                BiliButton(
                                    onClick = { onQuality(id) },
                                    selected = sel,
                                    modifier = if (sel) Modifier.focusRequester(first) else Modifier,
                                ) { Text(name, fontSize = 15.sp) }
                            }
                        }
                    }
                }
                item {
                    MenuSection("播放速度") {
                        SPEEDS.forEach { s ->
                            BiliButton(onClick = { onSpeed(s) }, selected = s == ui.speed) {
                                Text(speedLabel(s), fontSize = 15.sp)
                            }
                        }
                    }
                }
                item {
                    MenuSection("彈幕") {
                        BiliButton(
                            onClick = { onDanmaku(true) },
                            selected = ui.danmakuOn,
                            modifier = if (!focusOnQuality) Modifier.focusRequester(first) else Modifier,
                        ) { Text("開啟", fontSize = 15.sp) }
                        BiliButton(onClick = { onDanmaku(false) }, selected = !ui.danmakuOn) {
                            Text("關閉", fontSize = 15.sp)
                        }
                    }
                }
                if (ui.pages.size > 1) {
                    item {
                        Text("選集（${ui.pages.size}）", fontSize = 15.sp, color = Gray, modifier = Modifier.padding(top = 8.dp))
                    }
                    itemsIndexed(ui.pages, key = { _, p -> p.cid }) { i, p ->
                        BiliButton(
                            onClick = { onPage(i) },
                            selected = i == ui.pageIndex,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("P${p.page}  ${p.title}", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, fontSize = 15.sp, color = Gray)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}
