package com.bilitv.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.bilitv.app.BuildConfig
import com.bilitv.app.R
import com.bilitv.app.Session
import com.bilitv.app.player.LivePlayerActivity
import com.bilitv.app.util.Updater
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {
    /** 外部連結（影片／UP 主／直播），處理完清掉 */
    private var pendingLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingLink = parseDeepLink(intent?.data)
        setContent {
            BiliTheme {
                AppRoot(link = pendingLink, onLinkHandled = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink = parseDeepLink(intent.data)
    }
}

enum class Tab(@StringRes val labelRes: Int, val icon: ImageVector) {
    Recommend(R.string.nav_recommend, Icons.Filled.Home),
    Popular(R.string.nav_popular, Icons.Filled.Star),
    Ranking(R.string.nav_ranking, Icons.AutoMirrored.Filled.List),
    Live(R.string.nav_live, Icons.Filled.PlayArrow),
    Dynamic(R.string.nav_dynamic, Icons.Filled.Notifications),
    History(R.string.nav_history, Icons.Filled.DateRange),
    Search(R.string.nav_search, Icons.Filled.Search),
    Mine(R.string.nav_mine, Icons.Filled.Person),
}

@Composable
fun AppRoot(link: DeepLink? = null, onLinkHandled: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(Tab.Recommend) }
    var firstLaunch by remember { mutableStateOf(true) }
    // 頁面堆疊：影片詳情、UP 主頁，可以一直往下點
    val stack = remember { mutableStateListOf<Route>() }
    val context = LocalContext.current
    val nav = remember {
        Nav(
            openVideo = { stack.add(Route.Video(it)) },
            openUp = { if (it > 0) stack.add(Route.Up(it)) },
            openLive = { roomId, title, anchor -> LivePlayerActivity.start(context, roomId, title, anchor) },
        )
    }

    LaunchedEffect(link) {
        when (link) {
            is DeepLink.Open -> {
                firstLaunch = false
                stack.add(link.route)
            }
            is DeepLink.Live -> nav.openLive(link.roomId, "", "")
            null -> return@LaunchedEffect
        }
        onLinkHandled()
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            val top = stack.lastOrNull()
            if (top == null) {
                HomeScreen(
                    tab = tab,
                    onTab = { tab = it },
                    focusDrawer = firstLaunch,
                    onFocused = { firstLaunch = false },
                )
            } else {
                key(stack.size, top) {
                    when (top) {
                        is Route.Video -> DetailScreen(bvid = top.bvid)
                        is Route.Up -> UpScreen(mid = top.mid)
                    }
                }
            }
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    // 開啟時檢查 GitHub 有沒有新版（debug 版的 versionCode 固定是 1，不自動檢查）
    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) {
            runCatching { Updater.check() }.getOrNull()?.let { Updater.pending = it }
        }
    }
    Updater.pending?.let { release ->
        UpdateDialog(release, onDismiss = { Updater.pending = null })
    }
}

@Composable
fun HomeScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    focusDrawer: Boolean,
    onFocused: () -> Unit,
) {
    val activity = LocalActivity.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var drawerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (focusDrawer) {
            delay(100)
            runCatching { requesters.getValue(tab).requestFocus() }
            onFocused()
        }
    }

    // 內容區按返回鍵先回到側邊選單，在選單上再按返回才離開
    BackHandler {
        if (drawerFocused) activity?.finish()
        else runCatching { requesters.getValue(tab).requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(if (value == DrawerValue.Open) Color(0xFF17171C) else Color.Transparent)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .onFocusChanged { drawerFocused = it.hasFocus }
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (value == DrawerValue.Open) "BiliTV" else "B",
                    color = Pink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                )
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.focusRequester(requesters.getValue(t)),
                    ) {
                        Text(if (t == Tab.Mine) Session.user?.takeIf { it.isLogin }?.name ?: stringResource(t.labelRes) else stringResource(t.labelRes))
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 92.dp)
        ) {
            val goLogin = { onTab(Tab.Mine) }
            when (tab) {
                Tab.Recommend -> FeedTab(stringResource(R.string.nav_recommend), "recommend", ::RecommendSource)
                Tab.Popular -> FeedTab(stringResource(R.string.nav_popular), "popular", ::PopularSource)
                Tab.Ranking -> FeedTab(stringResource(R.string.nav_ranking), "ranking", ::RankingSource)
                Tab.Live -> LiveScreen(goLogin)
                Tab.Dynamic ->
                    if (Session.isLogin) FeedTab(stringResource(R.string.following_updates), "dynamic", ::DynamicSource)
                    else LoginPrompt(goLogin)
                Tab.History ->
                    if (Session.isLogin) FeedTab(stringResource(R.string.nav_history), "history", ::HistorySource)
                    else LoginPrompt(goLogin)
                Tab.Search -> SearchScreen()
                Tab.Mine -> MineScreen()
            }
        }
    }
}

@Composable
fun FeedTab(title: String, key: String, factory: () -> FeedSource) {
    val nav = LocalNav.current
    val language = ConfigurationCompat.getLocales(LocalConfiguration.current).toLanguageTags()
    val vm: FeedViewModel = viewModel(key = "$key#${Session.generation}#$language") { FeedViewModel(factory) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title) {
            BiliButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.refresh)) }
        }
        VideoGrid(vm, onClick = nav::open)
    }
}

/** 直播：推薦直播／我關注的（登入後） */
@Composable
fun LiveScreen(goLogin: () -> Unit) {
    val nav = LocalNav.current
    var following by rememberSaveable { mutableStateOf(false) }
    val showFollowing = following && Session.isLogin
    val language = ConfigurationCompat.getLocales(LocalConfiguration.current).toLanguageTags()
    val vm: FeedViewModel = viewModel(key = "live-$showFollowing#${Session.generation}#$language") {
        FeedViewModel(if (showFollowing) ::LiveFollowingSource else ::LiveRecommendSource)
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_live)) {
            BiliButton(onClick = { following = false }, selected = !showFollowing) { Text(stringResource(R.string.live_recommend)) }
            Spacer(Modifier.width(12.dp))
            BiliButton(
                onClick = { if (Session.isLogin) following = true else goLogin() },
                selected = showFollowing,
            ) { Text(stringResource(R.string.live_following)) }
            Spacer(Modifier.width(12.dp))
            BiliButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.refresh)) }
        }
        VideoGrid(vm, onClick = nav::open)
    }
}
