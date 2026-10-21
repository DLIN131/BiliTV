package com.bilitv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.bilitv.app.net.BiliApi
import com.bilitv.app.net.VideoDetail
import com.bilitv.app.net.VideoItem
import com.bilitv.app.player.PlayerActivity
import com.bilitv.app.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(bvid: String, openVideo: (String) -> Unit) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var related by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(bvid, attempt) {
        error = null
        try {
            detail = BiliApi.view(bvid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "載入失敗"
            return@LaunchedEffect
        }
        delay(60)
        runCatching { playFocus.requestFocus() }
        runCatching { related = BiliApi.related(bvid) }
    }

    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val err = error
            if (err != null) ErrorBox(err, onRetry = { attempt++ }) else Loading()
        }
        return
    }

    val play = { cid: Long -> PlayerActivity.start(context, d.bvid, cid) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 36.dp)
    ) {
        Row {
            AsyncImage(
                model = Fmt.image(d.cover, 960, 540),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(460.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg),
            )
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 34.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = Fmt.image(d.ownerFace, 96, 96),
                        contentDescription = null,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(CardBg),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(d.ownerName, fontSize = 17.sp, color = Pink)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${Fmt.count(d.views)} 播放  ·  ${Fmt.count(d.danmaku)} 彈幕  ·  ${Fmt.count(d.likes)} 讚  ·  " +
                        "${Fmt.count(d.coins)} 投幣  ·  ${Fmt.count(d.favorites)} 收藏  ·  ${Fmt.date(d.pubdate)}",
                    fontSize = 14.sp,
                    color = Gray,
                )
                Spacer(Modifier.height(12.dp))
                if (d.desc.isNotBlank() && d.desc != "-") {
                    Text(d.desc, fontSize = 14.sp, color = Gray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                }
                BiliButton(
                    onClick = { play(0L) },
                    modifier = Modifier.focusRequester(playFocus),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("播放", fontSize = 18.sp)
                }
            }
        }

        if (d.pages.size > 1) {
            Spacer(Modifier.height(28.dp))
            Text("選集（${d.pages.size}）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            LazyRow(
                contentPadding = PaddingValues(vertical = 14.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(d.pages, key = { it.cid }) { p ->
                    BiliButton(onClick = { play(p.cid) }) {
                        Text(
                            "P${p.page}  ${p.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    }
                }
            }
        }

        if (related.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("相關推薦", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            LazyRow(
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(related, key = { it.bvid }) { item ->
                    VideoCard(item, onClick = { openVideo(item.bvid) }, modifier = Modifier.width(270.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
