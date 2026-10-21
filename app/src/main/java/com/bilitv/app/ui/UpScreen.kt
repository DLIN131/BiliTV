package com.bilitv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.bilitv.app.Session
import com.bilitv.app.net.BiliApi
import com.bilitv.app.net.UpInfo
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** UP 主個人頁：基本資料、關注、直播入口，下面是全部投稿 */
@Composable
fun UpScreen(mid: Long) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var followBusy by remember { mutableStateOf(false) }
    var followMsg by remember { mutableStateOf<String?>(null) }
    val vm: FeedViewModel = viewModel(key = "up-$mid#${Session.generation}") {
        FeedViewModel { UpVideosSource(mid) }.apply {
            // 第一次載入完把焦點放在第一部影片
            focusIndex = 0
            restoreFocus = true
        }
    }

    LaunchedEffect(mid, attempt) {
        error = null
        try {
            info = BiliApi.upInfo(mid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "載入失敗"
        }
    }

    Column(Modifier.fillMaxSize()) {
        val up = info
        if (up == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp), contentAlignment = Alignment.Center
            ) {
                val err = error
                if (err != null) ErrorBox(err, onRetry = { attempt++ }) else Loading(size = 36)
            }
        } else {
            Row(
                Modifier.padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = Fmt.image(up.face, 192, 192),
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(CardBg),
                )
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(up.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Text("LV${up.level}", fontSize = 14.sp, color = Pink)
                        if (up.vip) {
                            Spacer(Modifier.width(8.dp))
                            Text("大會員", fontSize = 14.sp, color = Pink)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${Fmt.count(up.fans)} 粉絲  ·  ${Fmt.count(up.following)} 關注  ·  ${up.archiveCount} 部影片",
                        fontSize = 15.sp,
                        color = Gray,
                    )
                    if (up.sign.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(up.sign, fontSize = 14.sp, color = Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(24.dp))
                if (up.isLive && up.liveRoomId > 0) {
                    BiliButton(
                        onClick = { nav.openLive(up.liveRoomId, up.liveTitle, up.name) },
                        selected = true,
                    ) { Text("● 直播中") }
                    Spacer(Modifier.width(12.dp))
                }
                if (Session.isLogin) {
                    BiliButton(
                        onClick = {
                            if (followBusy) return@BiliButton
                            followBusy = true
                            followMsg = null
                            scope.launch {
                                runCatching { BiliApi.setFollow(up.mid, !up.isFollowing) }
                                    .onSuccess {
                                        info = up.copy(
                                            isFollowing = !up.isFollowing,
                                            fans = up.fans + if (up.isFollowing) -1 else 1,
                                        )
                                    }
                                    .onFailure { followMsg = it.message }
                                followBusy = false
                            }
                        },
                        selected = !up.isFollowing,
                    ) { Text(if (up.isFollowing) "已關注" else "+ 關注") }
                }
            }
            followMsg?.let {
                Text(it, fontSize = 14.sp, color = Gray, modifier = Modifier.padding(start = 160.dp))
            }
        }
        Text(
            "投稿影片",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 40.dp, top = 12.dp),
        )
        VideoGrid(vm, onClick = nav::open)
    }
}
