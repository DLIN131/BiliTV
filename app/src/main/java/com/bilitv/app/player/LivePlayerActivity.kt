package com.bilitv.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.bilitv.app.net.BiliApi
import com.bilitv.app.net.Http
import com.bilitv.app.net.LivePlay
import com.bilitv.app.ui.BiliButton
import com.bilitv.app.ui.BiliTheme
import com.bilitv.app.ui.Gray
import com.bilitv.app.ui.Pink
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LiveUi {
    var title by mutableStateOf("")
    var anchor by mutableStateOf("")
    var area by mutableStateOf("")
    var online by mutableStateOf("")
    var loading by mutableStateOf(true)
    var buffering by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var controlsVisible by mutableStateOf(true)
    var menuOpen by mutableStateOf(false)
    var hint by mutableStateOf<String?>(null)
    var qualities by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var quality by mutableIntStateOf(0)
}

@OptIn(UnstableApi::class)
class LivePlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ANCHOR = "anchor"
        /** 預設要求原畫，伺服器會回最接近的可用畫質 */
        private const val DEFAULT_QN = 10000
        private const val STALL_TIMEOUT_MS = 12_000L

        fun start(context: Context, roomId: Long, title: String = "", anchor: String = "") {
            context.startActivity(
                Intent(context, LivePlayerActivity::class.java)
                    .putExtra(EXTRA_ROOM, roomId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_ANCHOR, anchor)
            )
        }
    }

    private val ui = LiveUi()
    private lateinit var player: ExoPlayer
    private var roomId = 0L
    private var play: LivePlay? = null
    private var urlAttempt = 0
    /** 串流網址有時效，所有線路都失敗時重新取一次 */
    private var refetched = false
    private var stopped = false
    private var stallSince = 0L

    private var loadJob: Job? = null
    private var hintJob: Job? = null
    private var lastInteraction = 0L
    private var lastBack = 0L

    private val dataSourceFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(mapOf("Referer" to "https://live.bilibili.com/"))
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.playing = isPlaying
            if (isPlaying) touch() else ui.controlsVisible = true
        }

        override fun onPlaybackStateChanged(state: Int) {
            ui.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) ui.error = "直播已結束或中斷\n按 OK 鍵重新連線"
        }

        override fun onPlayerError(error: PlaybackException) {
            failover("連線失敗（${error.errorCodeName}）")
        }
    }

    /** 換下一條串流；全部試完就重新取一次地址，再不行才顯示錯誤 */
    private fun failover(reason: String) {
        stallSince = 0L
        val p = play
        if (p != null && urlAttempt < p.sources.size - 1) {
            urlAttempt++
            showHint("$reason，改用備用線路（$urlAttempt）")
            startStream()
            return
        }
        if (!refetched) {
            refetched = true
            load(ui.quality)
            return
        }
        player.stop()
        ui.error = "直播播放失敗：$reason\n按 OK 鍵重試"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        roomId = intent.getLongExtra(EXTRA_ROOM, 0L)
        if (roomId <= 0) {
            finish()
            return
        }
        ui.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        ui.anchor = intent.getStringExtra(EXTRA_ANCHOR).orEmpty()

        player = ExoPlayer.Builder(this).build().also { it.addListener(listener) }

        setContent {
            BiliTheme {
                LivePlayerScreen(ui, player, onQuality = ::switchQuality)
            }
        }

        onBackPressedDispatcher.addCallback(this) { onBack() }
        startTicker()
        load(DEFAULT_QN)
    }

    override fun onStop() {
        super.onStop()
        // 直播不能「暫停後接著看」，回來時重新接到最新進度
        stopped = true
        player.stop()
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            refetched = false
            load(ui.quality.takeIf { it > 0 } ?: DEFAULT_QN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    private fun load(qn: Int) {
        loadJob?.cancel()
        ui.loading = true
        ui.error = null
        loadJob = lifecycleScope.launch {
            try {
                val p = BiliApi.livePlay(roomId, qn)
                if (p.sources.isEmpty()) {
                    ui.error = "主播目前沒有在直播"
                    return@launch
                }
                roomId = p.roomId
                play = p
                ui.qualities = p.qualities
                ui.quality = p.quality
                urlAttempt = 0
                startStream()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.error = "載入直播失敗：${e.message}\n按 OK 鍵重試"
            } finally {
                ui.loading = false
            }
        }
    }

    private fun startStream() {
        val p = play ?: return
        ui.error = null
        stallSince = 0L
        val stream = p.sources[urlAttempt % p.sources.size]
        val item = MediaItem.fromUri(stream.url)
        val source = if (stream.isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun refreshRoomInfo() {
        lifecycleScope.launch {
            runCatching { BiliApi.liveRoom(roomId) }.onSuccess { r ->
                if (r.title.isNotEmpty()) ui.title = r.title
                ui.area = r.area
                ui.online = if (r.online > 0) "人氣 ${Fmt.count(r.online)}" else ""
            }
            if (ui.anchor.isEmpty()) {
                runCatching { BiliApi.liveAnchorName(roomId) }.onSuccess { ui.anchor = it }
            }
        }
    }

    private fun switchQuality(qn: Int) {
        ui.menuOpen = false
        showControls()
        if (qn == ui.quality) return
        refetched = false
        load(qn)
        showHint("切換至 ${ui.qualities.firstOrNull { it.first == qn }?.second ?: qn}")
    }

    private fun startTicker() {
        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                // 每分鐘更新一次標題與人氣
                if (tick % 60 == 0) refreshRoomInfo()
                tick++
                // 有些串流不會回錯誤、只會一直緩衝，卡太久就換下一條
                val stalled = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING &&
                    ui.error == null && !ui.loading
                if (!stalled) {
                    stallSince = 0L
                } else if (stallSince == 0L) {
                    stallSince = SystemClock.uptimeMillis()
                } else if (SystemClock.uptimeMillis() - stallSince > STALL_TIMEOUT_MS) {
                    failover("連線逾時")
                }
                if (ui.controlsVisible && player.isPlaying && !ui.menuOpen &&
                    SystemClock.uptimeMillis() - lastInteraction > 5_000
                ) {
                    ui.controlsVisible = false
                }
                delay(1_000)
            }
        }
    }

    private fun touch() {
        lastInteraction = SystemClock.uptimeMillis()
    }

    private fun showControls() {
        touch()
        ui.controlsVisible = true
    }

    private fun showHint(text: String) {
        ui.hint = text
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(3_000)
            ui.hint = null
        }
    }

    private fun onOk() {
        if (ui.error != null) {
            refetched = false
            load(ui.quality.takeIf { it > 0 } ?: DEFAULT_QN)
            return
        }
        if (player.isPlaying) player.pause() else player.play()
        showControls()
    }

    private fun onBack() {
        if (ui.menuOpen) {
            ui.menuOpen = false
            showControls()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 2_000) {
            finish()
        } else {
            lastBack = now
            showHint("再按一次返回鍵離開直播")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val first = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (ui.menuOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) {
                    ui.menuOpen = false
                    showControls()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) onOk()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (first && ui.qualities.isNotEmpty()) {
                    ui.menuOpen = true
                    ui.controlsVisible = true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (first) {
                    if (ui.controlsVisible && player.isPlaying && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        ui.controlsVisible = false
                    } else {
                        showControls()
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LivePlayerScreen(ui: LiveUi, player: Player, onQuality: (Int) -> Unit) {
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
        } else if (!ui.playing && !ui.menuOpen) {
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
            visible = ui.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "● 直播",
                            fontSize = 14.sp,
                            modifier = Modifier
                                .background(Pink, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(ui.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf(ui.anchor, ui.area, ui.online).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        fontSize = 16.sp,
                        color = Gray,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("OK 暫停    ↑ 畫質    ↓ 資訊    返回×2 離開", fontSize = 14.sp, color = Gray)
                    Spacer(Modifier.weight(1f))
                    Text(ui.qualities.firstOrNull { it.first == ui.quality }?.second.orEmpty(), fontSize = 14.sp, color = Pink)
                }
            }
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
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(50)
                runCatching { first.requestFocus() }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
            ) {
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(360.dp)
                        .background(Color(0xF0141418))
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                ) {
                    Text("畫質", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(ui.qualities, key = { it.first }) { (qn, name) ->
                            val selected = qn == ui.quality
                            BiliButton(
                                onClick = { onQuality(qn) },
                                selected = selected,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (selected) Modifier.focusRequester(first) else Modifier),
                            ) { Text(name, fontSize = 16.sp) }
                        }
                    }
                }
            }
        }
    }
}
