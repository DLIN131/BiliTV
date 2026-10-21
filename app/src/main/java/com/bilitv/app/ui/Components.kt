package com.bilitv.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.bilitv.app.net.VideoItem
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.delay

@Composable
fun BiliButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Pink else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun VideoCard(item: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF26262D))
        ) {
            AsyncImage(
                model = Fmt.image(item.cover, 480, 270),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.views.isNotEmpty()) {
                    Text(
                        if (item.liveRoomId > 0) "人氣 ${item.views}" else "▶ ${item.views}",
                        fontSize = 11.sp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (item.danmaku.isNotEmpty()) {
                    Text("彈 ${item.danmaku}", fontSize = 11.sp, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (item.duration.isNotEmpty()) Text(item.duration, fontSize = 11.sp, color = Color.White)
            }
            if (item.liveRoomId > 0) {
                Text(
                    "● 直播",
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Pink, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (item.progress >= 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progress)
                            .background(Pink)
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                item.title,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(item.author, item.extra).filter { it.isNotEmpty() }.joinToString("  ·  "),
                fontSize = 12.sp,
                color = Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 48) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Pink, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        BiliButton(onClick = onRetry) { Text("重試") }
    }
}

@Composable
fun LoginPrompt(onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("登入後才能查看這裡的內容", fontSize = 20.sp, color = Gray)
        Spacer(Modifier.height(16.dp))
        BiliButton(onClick = onLogin) { Text("前往登入") }
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun VideoGrid(vm: FeedViewModel, onClick: (VideoItem) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(vm) { vm.ensureLoaded() }

    if (vm.items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = vm.error
            when {
                vm.loading -> Loading()
                err != null -> ErrorBox(err, onRetry = { vm.loadMore() })
                else -> Text("這裡什麼都沒有", color = Gray, fontSize = 18.sp)
            }
        }
        return
    }

    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (vm.restoreFocus) {
            vm.restoreFocus = false
            delay(80)
            runCatching { restore.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = vm.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(vm.items, key = { i, it -> "$i:${it.bvid}" }) { index, item ->
            if (index >= vm.items.size - 8) {
                LaunchedEffect(vm.items.size) { vm.loadMore() }
            }
            VideoCard(
                item = item,
                onClick = {
                    vm.focusIndex = index
                    vm.restoreFocus = true
                    onClick(item)
                },
                modifier = if (index == vm.focusIndex) Modifier.focusRequester(restore) else Modifier,
            )
        }
        val err = vm.error
        if (vm.loading || err != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    if (err != null) ErrorBox(err, onRetry = { vm.loadMore() }) else Loading(size = 32)
                }
            }
        }
    }
}
