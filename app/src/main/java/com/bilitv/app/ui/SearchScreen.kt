package com.bilitv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bilitv.app.net.BiliApi

@Composable
fun SearchScreen(openVideo: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val vm: FeedViewModel = viewModel(key = "search") { FeedViewModel { SearchSource("") } }
    val hot = remember { mutableStateListOf<String>() }
    val searchButton = remember { FocusRequester() }
    var focusTick by remember { mutableIntStateOf(0) }

    // 點熱搜後按鈕會消失，把焦點移到搜尋鍵，往下就是結果
    LaunchedEffect(focusTick) {
        if (focusTick > 0) {
            delay(100)
            runCatching { searchButton.requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        if (hot.isEmpty()) runCatching { hot.addAll(BiliApi.hotSearch()) }
    }
    LaunchedEffect(query) {
        if (query.isNotEmpty() && (vm.source as? SearchSource)?.keyword != query) {
            vm.refresh { SearchSource(query) }
        }
    }

    fun search(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        text = k
        if (k == query) vm.refresh { SearchSource(k) } else query = k
        focusTick++
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜尋")
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("輸入影片關鍵字", color = Gray) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Pink,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Pink,
                ),
                modifier = Modifier.width(520.dp),
            )
            Spacer(Modifier.width(16.dp))
            BiliButton(onClick = { search(text) }, modifier = Modifier.focusRequester(searchButton)) { Text("搜尋") }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                BiliButton(onClick = { query = ""; text = "" }) { Text("清除") }
            }
        }

        if (query.isEmpty()) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp)) {
                Text("熱門搜尋", fontSize = 18.sp, color = Gray)
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    hot.forEach { word ->
                        BiliButton(onClick = { search(word) }) { Text(word, fontSize = 15.sp) }
                    }
                }
            }
        } else {
            VideoGrid(vm, onClick = { openVideo(it.bvid) })
        }
    }
}
