package cn.loveq.podcast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.loveq.podcast.LoveqApp
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.playback.PlaybackEvents
import cn.loveq.podcast.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListUiState(
    val category: Category = Category.ALL.first(),
    val episodes: List<Episode> = emptyList(),
    val startPage: Int = 1,
    val loadedEndPage: Int = 0,
    val totalPages: Int = 1,
    val initialLoading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingDown: Boolean = false,
    val loadingUp: Boolean = false,
    val jumping: Boolean = false,
    val error: String? = null
) {
    val endReached: Boolean get() = loadedEndPage >= totalPages && !initialLoading
}

class EpisodesViewModel(app: Application) : AndroidViewModel(app) {

    private val loveqApp = app as LoveqApp
    private val repo = loveqApp.repository
    private val store = loveqApp.progressStore

    val connection = PlayerConnection(app)
    val progressMap: StateFlow<Map<Long, Progress>> = store.progressMap
    val lastId: StateFlow<Long?> = store.lastId

    /** 全库索引（粤语节目，主节目归档），供搜索使用 */
    val index: StateFlow<List<Episode>> = loveqApp.indexStore.episodes
    val indexing: StateFlow<Boolean> = loveqApp.indexStore.loading

    val history: StateFlow<List<Progress>> = store.progressMap
        .map { map -> map.values.sortedByDescending { it.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isPlaying = connection.isPlaying
    val playingMediaId = connection.mediaId
    val positionMs = connection.positionMs
    val durationMs = connection.durationMs
    val speed = connection.speed
    val nowTitle = connection.nowTitle
    val nowDate = connection.nowDate

    private val pageCache = mutableMapOf<Int, MutableMap<Int, List<Episode>>>()
    private val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state

    init {
        loveqApp.indexStore.ensureIndex(repo)
        loadInitial()
    }

    fun switchCategory(category: Category) {
        if (_state.value.category.id == category.id) return
        _state.update {
            it.copy(
                category = category,
                episodes = emptyList(),
                startPage = 1,
                loadedEndPage = 0,
                totalPages = 1,
                initialLoading = true,
                error = null
            )
        }
        loadInitial()
    }

    private fun cached(catId: Int, page: Int): List<Episode>? =
        pageCache[catId]?.get(page)

    private fun cache(catId: Int, page: Int, episodes: List<Episode>) {
        pageCache.getOrPut(catId) { mutableMapOf() }[page] = episodes
    }

    private fun loadInitial() {
        val catId = _state.value.category.id
        viewModelScope.launch {
            try {
                val result = repo.loadPage(1, catId)
                cache(catId, 1, result.episodes)
                _state.update {
                    it.copy(
                        episodes = result.episodes,
                        startPage = 1,
                        loadedEndPage = 1,
                        totalPages = result.totalPages,
                        initialLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(initialLoading = false, error = "加载失败：${e.message ?: "网络异常"}")
                }
            }
        }
    }

    fun retry() {
        _state.update { it.copy(initialLoading = true, error = null) }
        loadInitial()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        loveqApp.indexStore.refresh(repo)
        val catId = _state.value.category.id
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val result = repo.loadPage(1, catId)
                cache(catId, 1, result.episodes)
                _state.update {
                    it.copy(
                        episodes = result.episodes,
                        startPage = 1,
                        loadedEndPage = 1,
                        totalPages = result.totalPages,
                        refreshing = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false) }
                PlaybackEvents.tryEmit("刷新失败，请检查网络")
            }
        }
    }

    fun loadNextPage() {
        val s = _state.value
        if (s.loadingDown || s.jumping || s.loadedEndPage >= s.totalPages) return
        viewModelScope.launch {
            _state.update { it.copy(loadingDown = true) }
            try {
                val next = s.loadedEndPage + 1
                val episodes = cached(s.category.id, next)
                    ?: repo.loadPage(next, s.category.id).episodes.also { cache(s.category.id, next, it) }
                _state.update {
                    it.copy(
                        episodes = it.episodes + episodes,
                        loadedEndPage = next,
                        loadingDown = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingDown = false) }
            }
        }
    }

    fun loadPreviousPage() {
        val s = _state.value
        if (s.loadingUp || s.startPage <= 1) return
        viewModelScope.launch {
            _state.update { it.copy(loadingUp = true) }
            try {
                val prev = s.startPage - 1
                val episodes = cached(s.category.id, prev)
                    ?: repo.loadPage(prev, s.category.id).episodes.also { cache(s.category.id, prev, it) }
                _state.update {
                    it.copy(
                        episodes = episodes + it.episodes,
                        startPage = prev,
                        loadingUp = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingUp = false) }
            }
        }
    }

    fun jumpToYear(year: Int) {
        if (_state.value.jumping) return
        val catId = _state.value.category.id
        viewModelScope.launch {
            _state.update { it.copy(jumping = true) }
            try {
                val totalPages = _state.value.totalPages
                var page = 1
                var result: List<Episode>? = null
                while (page <= totalPages) {
                    val episodes = cached(catId, page)
                        ?: repo.loadPage(page, catId).episodes.also { cache(catId, page, it) }
                    val idx = episodes.indexOfFirst { it.year <= year }
                    if (idx >= 0) {
                        result = episodes.subList(idx, episodes.size)
                        break
                    }
                    page++
                }
                if (result != null) {
                    _state.update {
                        it.copy(
                            episodes = result,
                            startPage = page,
                            loadedEndPage = page
                        )
                    }
                } else {
                    PlaybackEvents.tryEmit("没有找到 $year 年的节目")
                }
            } catch (e: Exception) {
                PlaybackEvents.tryEmit("跳转失败，请检查网络")
            } finally {
                _state.update { it.copy(jumping = false) }
            }
        }
    }

    /**
     * 连播队列向上构建：列表是新→旧排布，听完当前一期后
     * 自动播放界面中更靠上（更新）的一期，即按时间顺序往后听。
     */
    private fun upwardQueue(episodes: List<Episode>, position: Int): List<Episode> {
        val start = (position - QUEUE_AHEAD + 1).coerceAtLeast(0)
        return episodes.subList(start, position + 1).asReversed()
    }

    fun playEpisode(position: Int) {
        val s = _state.value
        if (position !in s.episodes.indices) return
        connection.playQueue(upwardQueue(s.episodes, position), 0)
    }

    /** 列表、搜索、历史共用入口：优先当前列表，其次全库索引，最后按进度兜底 */
    fun playEpisodeById(id: Long) {
        val idxInList = _state.value.episodes.indexOfFirst { it.id == id }
        if (idxInList >= 0) {
            playEpisode(idxInList)
            return
        }
        val idx = index.value.indexOfFirst { it.id == id }
        if (idx >= 0) {
            connection.playQueue(upwardQueue(index.value, idx), 0)
            return
        }
        store.get(id)?.let { connection.playQueue(listOf(Episode(id, it.date, it.title)), 0) }
    }

    fun resumeLast() {
        lastId.value?.let { playEpisodeById(it) }
    }

    fun togglePlayPause() = connection.togglePlayPause()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun seekBack() = connection.seekBack()
    fun seekForward() = connection.seekForward()
    fun setSpeed(value: Float) = connection.setSpeed(value)

    override fun onCleared() {
        connection.release()
    }

    private companion object {
        const val QUEUE_AHEAD = 50
    }
}
