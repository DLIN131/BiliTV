package com.bilitv.app.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.bilitv.app.Session
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BiliTheme {
                AppRoot()
            }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Recommend("推薦", Icons.Filled.Home),
    Popular("熱門", Icons.Filled.Star),
    Ranking("排行榜", Icons.AutoMirrored.Filled.List),
    Dynamic("動態", Icons.Filled.Notifications),
    History("歷史紀錄", Icons.Filled.DateRange),
    Search("搜尋", Icons.Filled.Search),
    Mine("我的", Icons.Filled.Person),
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Recommend) }
    var firstLaunch by remember { mutableStateOf(true) }
    // 詳情頁堆疊（bvid），從相關推薦可以一直往下點
    val stack = remember { mutableStateListOf<String>() }
    val openVideo: (String) -> Unit = { stack.add(it) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        if (stack.isEmpty()) {
            HomeScreen(
                tab = tab,
                onTab = { tab = it },
                openVideo = openVideo,
                focusDrawer = firstLaunch,
                onFocused = { firstLaunch = false },
            )
        } else {
            key(stack.size, stack.last()) {
                DetailScreen(bvid = stack.last(), openVideo = openVideo)
            }
        }
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
}

@Composable
fun HomeScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    openVideo: (String) -> Unit,
    focusDrawer: Boolean,
    onFocused: () -> Unit,
) {
    val activity = LocalContext.current as Activity
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
        if (drawerFocused) activity.finish()
        else runCatching { requesters.getValue(tab).requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(if (value == DrawerValue.Open) Color(0xFF17171C) else Color.Transparent)
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
                        Text(if (t == Tab.Mine) Session.user?.takeIf { it.isLogin }?.name ?: t.label else t.label)
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
                Tab.Recommend -> FeedTab("推薦", "recommend", ::RecommendSource, openVideo)
                Tab.Popular -> FeedTab("熱門", "popular", ::PopularSource, openVideo)
                Tab.Ranking -> FeedTab("排行榜", "ranking", ::RankingSource, openVideo)
                Tab.Dynamic ->
                    if (Session.isLogin) FeedTab("關注動態", "dynamic", ::DynamicSource, openVideo)
                    else LoginPrompt(goLogin)
                Tab.History ->
                    if (Session.isLogin) FeedTab("歷史紀錄", "history", ::HistorySource, openVideo)
                    else LoginPrompt(goLogin)
                Tab.Search -> SearchScreen(openVideo)
                Tab.Mine -> MineScreen()
            }
        }
    }
}

@Composable
fun FeedTab(title: String, key: String, factory: () -> FeedSource, openVideo: (String) -> Unit) {
    val vm: FeedViewModel = viewModel(key = "$key#${Session.generation}") { FeedViewModel(factory) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title) {
            BiliButton(onClick = { vm.refresh() }) { Text("重新整理") }
        }
        VideoGrid(vm, onClick = { openVideo(it.bvid) })
    }
}
