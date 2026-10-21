package com.bilitv.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bilitv.app.App
import com.bilitv.app.Prefs
import com.bilitv.app.Session
import com.bilitv.app.net.BiliApi
import com.bilitv.app.net.Http
import com.bilitv.app.net.PlayInfo
import com.bilitv.app.net.VideoDetail
import com.bilitv.app.ui.BiliTheme
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_BVID = "bvid"
        private const val EXTRA_CID = "cid"
        private const val AVC = 7
        private const val BOOST_SPEED = 2f
        private const val MAX_URL_ATTEMPTS = 3

        fun start(context: Context, bvid: String, cid: Long = 0L) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_BVID, bvid)
                    .putExtra(EXTRA_CID, cid)
            )
        }
    }

    private val ui = PlayerUi()
    private lateinit var player: ExoPlayer
    private lateinit var danmakuView: DanmakuView

    private var detail: VideoDetail? = null
    private var info: PlayInfo? = null
    private var currentCid = 0L
    private var currentCodec = AVC
    private var avcFallback = false
    /** 第幾條線路（主網址 + 備用網址），CDN 回 4xx/5xx 時換下一條 */
    private var urlAttempt = 0

    private var loadJob: Job? = null
    private var danmakuJob: Job? = null
    private var hintJob: Job? = null
    private var lastInteraction = 0L
    private var lastBack = 0L
    private var okDown = false
    private var boosting = false

    private val dataSourceFactory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(mapOf("Referer" to Http.REFERER))
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.playing = isPlaying
            if (isPlaying) touch() else ui.controlsVisible = true
            danmakuView.invalidate()
        }

        override fun onPlaybackStateChanged(state: Int) {
            ui.buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 2xxx 是網路／IO 錯誤（例如 CDN 節點回 503），先換備用線路
            if (error.errorCode / 1000 == 2 && urlAttempt < MAX_URL_ATTEMPTS) {
                urlAttempt++
                showHint("連線失敗，改用備用線路（$urlAttempt）")
                rebuild(player.currentPosition)
                return
            }
            if (currentCodec != AVC && !avcFallback) {
                // 電視盒子不一定支援 HEVC / AV1 硬解，自動退回 AVC
                avcFallback = true
                showHint("目前編碼無法播放，已改用 AVC")
                rebuild(player.currentPosition)
                return
            }
            ui.error = "播放失敗：${error.errorCodeName}\n按 OK 鍵重試"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        player = ExoPlayer.Builder(this).build().also { it.addListener(listener) }
        danmakuView = DanmakuView(this).apply {
            positionProvider = { player.currentPosition }
            playingProvider = { player.isPlaying }
            danmakuEnabled = Prefs.danmaku
        }
        ui.danmakuOn = Prefs.danmaku

        setContent {
            BiliTheme {
                PlayerScreen(
                    ui = ui,
                    player = player,
                    danmakuView = danmakuView,
                    onQuality = ::switchQuality,
                    onSpeed = ::setSpeed,
                    onDanmaku = ::setDanmaku,
                    onPage = { i ->
                        ui.menuOpen = false
                        playPage(i, 0L)
                    },
                )
            }
        }

        onBackPressedDispatcher.addCallback(this) { onBack() }
        startTicker()
        load()
    }

    override fun onPause() {
        super.onPause()
        if (boosting) stopBoost()
        okDown = false
        reportProgress()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    // ---------- 載入 ----------

    private fun load() {
        val bvid = intent.getStringExtra(EXTRA_BVID) ?: run { finish(); return }
        val wantCid = intent.getLongExtra(EXTRA_CID, 0L)
        ui.loading = true
        ui.error = null
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val d = BiliApi.view(bvid)
                if (d.pages.isEmpty()) throw IOException("這部影片沒有可播放的內容")
                detail = d
                ui.title = d.title
                ui.pages = d.pages

                var index = d.pages.indexOfFirst { it.cid == wantCid }.coerceAtLeast(0)
                var start = 0L
                if (Session.isLogin) {
                    val last = runCatching { BiliApi.lastPlay(bvid, d.pages[index].cid) }.getOrNull()
                    if (last != null) {
                        val (lastCid, ms) = last
                        if (wantCid == 0L) {
                            d.pages.indexOfFirst { it.cid == lastCid }.takeIf { it >= 0 }?.let { index = it }
                        }
                        val page = d.pages[index]
                        if (page.cid == lastCid && ms > 5_000 && ms < page.duration * 1000L - 10_000) start = ms
                    }
                }
                playPage(index, start, if (start > 0) "已從 ${Fmt.ms(start)} 繼續播放" else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.loading = false
                ui.error = "載入失敗：${e.message}\n按 OK 鍵重試"
            }
        }
    }

    private fun playPage(index: Int, startMs: Long, hint: String? = null) {
        val d = detail ?: return
        val page = d.pages.getOrNull(index) ?: return
        if (currentCid != 0L) reportProgress()

        loadJob?.cancel()
        danmakuJob?.cancel()
        danmakuView.setData(emptyList())
        player.stop()
        avcFallback = false
        urlAttempt = 0
        ui.pageIndex = index
        ui.subtitle = if (d.pages.size > 1) "P${page.page}  ${page.title}" else d.ownerName
        ui.loading = true
        ui.error = null
        ui.controlsVisible = true

        loadJob = lifecycleScope.launch {
            try {
                val playInfo = BiliApi.playUrl(d.bvid, page.cid)
                info = playInfo
                currentCid = page.cid
                ui.qualities = playInfo.qualities
                ui.quality = pickQuality(playInfo, Prefs.quality)
                player.setMediaSource(buildSource(playInfo, ui.quality), startMs)
                player.setPlaybackSpeed(ui.speed)
                player.prepare()
                player.playWhenReady = true
                hint?.let { showHint(it) }
                danmakuJob = launch {
                    runCatching { BiliApi.danmaku(page.cid) }.onSuccess { danmakuView.setData(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.error = "取得播放地址失敗：${e.message}\n按 OK 鍵重試"
            } finally {
                ui.loading = false
            }
        }
    }

    private fun pickQuality(info: PlayInfo, preferred: Int): Int {
        val ids = info.qualities.map { it.first } // 由高到低
        return ids.firstOrNull { it <= preferred } ?: ids.lastOrNull() ?: 0
    }

    private fun buildSource(info: PlayInfo, qn: Int): MediaSource {
        val factory = ProgressiveMediaSource.Factory(dataSourceFactory)
        if (info.videos.isEmpty()) {
            val url = info.durl.firstOrNull() ?: throw IOException("沒有可用的播放地址")
            currentCodec = AVC
            return factory.createMediaSource(MediaItem.fromUri(url))
        }
        val sameQuality = info.videos.filter { it.id == qn }.ifEmpty {
            val top = info.videos.maxOf { it.id }
            info.videos.filter { it.id == top }
        }
        val wantCodec = if (avcFallback) AVC else Prefs.codec
        val video = sameQuality.firstOrNull { it.codecid == wantCodec }
            ?: sameQuality.firstOrNull { it.codecid == AVC }
            ?: sameQuality.first()
        currentCodec = video.codecid
        val videoSource = factory.createMediaSource(MediaItem.fromUri(pickUrl(video.urls)))
        val audio = info.audios.maxByOrNull { it.bandwidth } ?: return videoSource
        val audioSource = factory.createMediaSource(MediaItem.fromUri(pickUrl(audio.urls)))
        return MergingMediaSource(videoSource, audioSource)
    }

    /** 盡量避開 PCDN（mcdn）節點，它們在海外或部分網路常常很慢。 */
    private fun pickUrl(urls: List<String>): String {
        val (pcdn, normal) = urls.partition { it.contains("mcdn.bilivideo") || it.contains(".szbdyd.com") }
        val ordered = normal + pcdn
        return ordered[urlAttempt % ordered.size]
    }

    private fun rebuild(positionMs: Long) {
        val i = info ?: return
        try {
            ui.error = null
            player.setMediaSource(buildSource(i, ui.quality), positionMs)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            ui.error = "播放失敗：${e.message}"
        }
    }

    private fun retry() {
        if (detail == null) load()
        else if (info == null || currentCid != detail?.pages?.getOrNull(ui.pageIndex)?.cid) playPage(ui.pageIndex, 0L)
        else {
            urlAttempt++
            rebuild(player.currentPosition)
        }
    }

    private fun onEnded() {
        val d = detail ?: return
        if (ui.pageIndex < d.pages.size - 1) {
            playPage(ui.pageIndex + 1, 0L, "自動播放下一集")
        } else {
            reportProgress()
            finish()
        }
    }

    private fun reportProgress() {
        val d = detail ?: return
        val cid = currentCid
        if (cid == 0L || !Session.isLogin) return
        val sec = player.currentPosition / 1000
        App.scope.launch { runCatching { BiliApi.reportHistory(d.aid, cid, sec) } }
    }

    // ---------- 選單動作 ----------

    private fun switchQuality(qn: Int) {
        ui.menuOpen = false
        if (qn == ui.quality) return
        Prefs.quality = qn
        ui.quality = qn
        rebuild(player.currentPosition)
        showHint("已切換至 ${ui.qualities.firstOrNull { it.first == qn }?.second ?: qn}")
    }

    private fun setSpeed(speed: Float) {
        ui.speed = speed
        player.setPlaybackSpeed(speed)
    }

    private fun setDanmaku(on: Boolean) {
        Prefs.danmaku = on
        ui.danmakuOn = on
        danmakuView.danmakuEnabled = on
    }

    // ---------- UI 狀態 ----------

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                ui.position = player.currentPosition
                ui.duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                ui.buffered = player.bufferedPosition
                if (ui.controlsVisible && player.isPlaying && ui.seekTarget == null && !ui.menuOpen &&
                    SystemClock.uptimeMillis() - lastInteraction > 4_000
                ) {
                    ui.controlsVisible = false
                }
                if (!player.isPlaying) danmakuView.invalidate()
                delay(250)
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

    // ---------- 遙控器 ----------

    private fun seekBy(forward: Boolean, repeat: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val base = ui.seekTarget ?: player.currentPosition
        // 第一下 10 秒；長按時依影片長度加速，大約 7 秒可以從頭掃到尾
        val step = if (repeat == 0) 10_000L else (dur / 150).coerceIn(2_000L, 30_000L)
        ui.seekTarget = (base + if (forward) step else -step).coerceIn(0L, dur)
        showControls()
    }

    private fun commitSeek() {
        val target = ui.seekTarget ?: return
        ui.seekTarget = null
        player.seekTo(target)
        if (player.playbackState == Player.STATE_ENDED) player.play()
        showControls()
    }

    private fun startBoost() {
        boosting = true
        ui.boosting = true
        ui.seekTarget = null
        if (!player.isPlaying) player.play()
        player.setPlaybackSpeed(BOOST_SPEED)
    }

    private fun stopBoost() {
        boosting = false
        ui.boosting = false
        player.setPlaybackSpeed(ui.speed)
    }

    private fun togglePlay() {
        if (ui.error != null) {
            retry()
            return
        }
        if (player.isPlaying) player.pause() else player.play()
        showControls()
    }

    private fun closeMenu() {
        ui.menuOpen = false
        showControls()
    }

    /** targetSdk 36 預設啟用預測返回，返回鍵不會經過 dispatchKeyEvent，要走 OnBackPressedDispatcher。 */
    private fun onBack() {
        if (ui.menuOpen) {
            closeMenu()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 2_000) {
            finish()
        } else {
            lastBack = now
            showHint("再按一次返回鍵離開播放")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        if (ui.menuOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (!down) closeMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) seekBy(false, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) seekBy(true, event.repeatCount) else commitSeek()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                // 短按：暫停／播放（放開時才判斷）；長按：放開前以 2 倍速播放
                if (down) {
                    if (event.repeatCount == 0) okDown = true
                    else if (okDown && !boosting && ui.error == null) startBoost()
                } else {
                    if (boosting) stopBoost() else if (okDown) togglePlay()
                    okDown = false
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (first) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) { player.play(); showControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) { player.pause(); showControls() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (first) {
                    ui.seekTarget = null
                    ui.menuOpen = true
                    ui.controlsVisible = true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                if (first) {
                    if (ui.controlsVisible && player.isPlaying) ui.controlsVisible = false else showControls()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (first) detail?.let { if (ui.pageIndex < it.pages.size - 1) playPage(ui.pageIndex + 1, 0L) }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (first && ui.pageIndex > 0) playPage(ui.pageIndex - 1, 0L)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
