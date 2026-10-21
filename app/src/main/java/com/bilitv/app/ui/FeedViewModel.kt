package com.bilitv.app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilitv.app.net.BiliApi
import com.bilitv.app.net.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 分頁資料來源。 */
abstract class FeedSource {
    var hasMore = true
        protected set

    abstract suspend fun next(): List<VideoItem>
}

class RecommendSource : FeedSource() {
    private var index = 0
    override suspend fun next() = BiliApi.recommend(++index)
}

class PopularSource : FeedSource() {
    private var page = 0
    override suspend fun next(): List<VideoItem> {
        val (list, noMore) = BiliApi.popular(++page)
        hasMore = !noMore && list.isNotEmpty()
        return list
    }
}

class RankingSource : FeedSource() {
    override suspend fun next(): List<VideoItem> {
        hasMore = false
        return BiliApi.ranking()
    }
}

class DynamicSource : FeedSource() {
    private var offset = ""
    private var page = 0
    override suspend fun next(): List<VideoItem> {
        val r = BiliApi.dynamic(offset, ++page)
        offset = r.offset
        hasMore = r.hasMore && offset.isNotEmpty()
        return r.items
    }
}

class HistorySource : FeedSource() {
    private var max = 0L
    private var viewAt = 0L
    override suspend fun next(): List<VideoItem> {
        val r = BiliApi.history(max, viewAt)
        max = r.max
        viewAt = r.viewAt
        hasMore = r.items.isNotEmpty() && max > 0
        return r.items
    }
}

class SearchSource(val keyword: String) : FeedSource() {
    private var page = 0
    override suspend fun next(): List<VideoItem> {
        if (keyword.isBlank()) {
            hasMore = false
            return emptyList()
        }
        val (list, pages) = BiliApi.search(keyword, ++page)
        hasMore = page < pages && list.isNotEmpty()
        return list
    }
}

class UpVideosSource(private val mid: Long) : FeedSource() {
    private var lastAid = 0L
    override suspend fun next(): List<VideoItem> {
        val r = BiliApi.upVideos(mid, lastAid)
        r.items.lastOrNull()?.let { lastAid = it.aid }
        hasMore = r.hasNext && lastAid > 0
        return r.items
    }
}

class LiveRecommendSource : FeedSource() {
    private var page = 0
    override suspend fun next(): List<VideoItem> {
        val list = BiliApi.liveRecommend(++page)
        hasMore = list.isNotEmpty() && page < 10
        return list
    }
}

class LiveFollowingSource : FeedSource() {
    private var page = 0
    override suspend fun next(): List<VideoItem> {
        val r = BiliApi.liveFollowing(++page)
        hasMore = r.hasMore
        return r.items
    }
}

class FeedViewModel(private var factory: () -> FeedSource) : ViewModel() {
    var source: FeedSource = factory()
        private set

    val items = mutableStateListOf<VideoItem>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var end by mutableStateOf(false)
        private set

    val gridState = LazyGridState()

    /** 點進詳情頁前聚焦的位置，返回時還原。 */
    var focusIndex = -1
    var restoreFocus = false

    private var generation = 0

    fun ensureLoaded() {
        if (items.isEmpty() && !loading && error == null && !end) loadMore()
    }

    fun loadMore() {
        if (loading || end) return
        val gen = ++generation
        loading = true
        error = null
        viewModelScope.launch {
            try {
                // 推薦流偶爾整頁都是廣告，多試幾次
                var tries = 0
                while (true) {
                    val page = source.next()
                    if (gen != generation) return@launch
                    items.addAll(page)
                    if (page.isNotEmpty() || !source.hasMore || ++tries >= 3) break
                }
                if (!source.hasMore) end = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "載入失敗"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun refresh(newFactory: (() -> FeedSource)? = null) {
        newFactory?.let { factory = it }
        generation++
        source = factory()
        items.clear()
        loading = false
        error = null
        end = false
        focusIndex = -1
        restoreFocus = false
        viewModelScope.launch { gridState.scrollToItem(0) }
        loadMore()
    }
}
