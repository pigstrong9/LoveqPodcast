package cn.loveq.podcast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.loveq.podcast.LoveqApp
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.PageResult
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.playback.PlaybackEvents
import cn.loveq.podcast.playback.PlayerConnection
import cn.loveq.podcast.playback.VolumeBoost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** 分页加载失败（区别于整表加载失败的 [error]）；footer 据此显示重试，空闲态不再误报失败 */
    val loadMoreError: Boolean = false,
    /** 列表来自本地索引而非官网：断网兜底，需要在界面上说清楚 */
    val offline: Boolean = false,
    val scrollResetToken: Int = 0,
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

    /** 软件音量增强档位（100–200%），与倍速一样全局记忆 */
    val boostPercent: StateFlow<Int> = VolumeBoost.percent

    fun setBoost(percent: Int) {
        VolumeBoost.apply(percent)
        store.boostPercent = percent
    }

    private val pageCache = mutableMapOf<Int, MutableMap<Int, PageResult>>()
    private val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state

    /** 整表加载（首屏/刷新/跳年份）共用一个 Job，新操作取消旧操作 */
    private var loadJob: Job? = null

    /** 整表替换代数：分类切换/刷新/跳转时递增，分页等旧任务据此丢弃过期结果 */
    private var loadGen = 0

    init {
        loveqApp.indexStore.ensureIndex(repo)
        loadInitial()
    }

    fun switchCategory(category: Category) {
        if (_state.value.category.id == category.id) return
        loadJob?.cancel()
        _state.update {
            it.copy(
                category = category,
                episodes = emptyList(),
                startPage = 1,
                loadedEndPage = 0,
                totalPages = 1,
                initialLoading = true,
                refreshing = false,
                loadingDown = false,
                loadingUp = false,
                jumping = false,
                loadMoreError = false,
                scrollResetToken = it.scrollResetToken + 1,
                error = null
            )
        }
        loadInitial()
    }

    private fun cached(catId: Int, page: Int): PageResult? =
        pageCache[catId]?.get(page)

    private fun cache(catId: Int, page: Int, result: PageResult) {
        pageCache.getOrPut(catId) { mutableMapOf() }[page] = result
    }

    private fun loadInitial() {
        loadJob?.cancel()
        val gen = ++loadGen
        val catId = _state.value.category.id
        loadJob = viewModelScope.launch {
            try {
                val result = repo.loadPage(1, catId)
                if (loadGen != gen) return@launch
                cache(catId, 1, result)
                _state.update {
                    it.copy(
                        episodes = result.episodes,
                        startPage = 1,
                        loadedEndPage = 1,
                        totalPages = result.totalPages,
                        initialLoading = false,
                        offline = false,
                        loadMoreError = false,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen != gen) return@launch
                // 断网不该只给一屏错误：粤语节目有全库磁盘索引，
                // 离线时照样能翻列表、续播已缓存的期
                val fallback = if (catId == Category.CANTONESE_ID) {
                    loveqApp.indexStore.episodes.value
                } else {
                    emptyList()
                }
                if (fallback.isEmpty()) {
                    _state.update {
                        it.copy(initialLoading = false, error = "加载失败：${e.message ?: "网络异常"}")
                    }
                } else {
                    _state.update {
                        it.copy(
                            episodes = fallback.take(PAGE_SIZE),
                            startPage = 1,
                            loadedEndPage = 1,
                            totalPages = 1,
                            initialLoading = false,
                            loadMoreError = false,
                            offline = true,
                            error = null
                        )
                    }
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
        loadJob?.cancel()
        val gen = ++loadGen
        val catId = _state.value.category.id
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(refreshing = true, loadingDown = false, loadingUp = false, jumping = false)
            }
            try {
                val result = repo.loadPage(1, catId)
                if (loadGen != gen) return@launch
                // 官网只增不删：刷新后旧的第 2..N 页缓存已整体后移，
                // 继续复用它会和新的第 1 页拼出缺口，所以整类缓存只保留刚取到的第 1 页
                pageCache[catId] = mutableMapOf(1 to result)
                _state.update {
                    it.copy(
                        episodes = result.episodes,
                        startPage = 1,
                        loadedEndPage = 1,
                        totalPages = result.totalPages,
                        initialLoading = false,
                        offline = false,
                        refreshing = false,
                        loadMoreError = false,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen != gen) return@launch
                _state.update { it.copy(refreshing = false, initialLoading = false) }
                PlaybackEvents.tryEmit("刷新失败，请检查网络")
            }
        }
    }

    fun loadNextPage() {
        val s = _state.value
        if (s.loadingDown || s.jumping || s.loadedEndPage >= s.totalPages) return
        val catId = s.category.id
        val gen = loadGen
        val next = s.loadedEndPage + 1
        viewModelScope.launch {
            _state.update { it.copy(loadingDown = true, loadMoreError = false) }
            try {
                val result = cached(catId, next)
                    ?: repo.loadPage(next, catId).also { cache(catId, next, it) }
                if (loadGen != gen) return@launch
                _state.update {
                    // 基准页不匹配说明列表已被刷新/跳转替换，丢弃这页避免拼出缺口
                    if (it.loadedEndPage != next - 1) {
                        it.copy(loadingDown = false)
                    } else {
                        // 去重兜底：万一页边界重叠，重复 id 会让 LazyColumn 的 key 冲突崩溃
                        val existing = it.episodes.mapTo(HashSet<Long>(it.episodes.size * 2)) { e -> e.id }
                        it.copy(
                            episodes = it.episodes + result.episodes.filterNot { e -> e.id in existing },
                            loadedEndPage = next,
                            totalPages = result.totalPages,
                            loadingDown = false
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen == gen) _state.update { it.copy(loadingDown = false, loadMoreError = true) }
            }
        }
    }

    fun loadPreviousPage() {
        val s = _state.value
        if (s.loadingUp || s.jumping || s.startPage <= 1) return
        val catId = s.category.id
        val gen = loadGen
        val prev = s.startPage - 1
        viewModelScope.launch {
            _state.update { it.copy(loadingUp = true) }
            try {
                val result = cached(catId, prev)
                    ?: repo.loadPage(prev, catId).also { cache(catId, prev, it) }
                if (loadGen != gen) return@launch
                _state.update {
                    if (it.startPage != prev + 1) {
                        it.copy(loadingUp = false)
                    } else {
                        val existing = it.episodes.mapTo(HashSet<Long>(it.episodes.size * 2)) { e -> e.id }
                        it.copy(
                            episodes = result.episodes.filterNot { e -> e.id in existing } + it.episodes,
                            startPage = prev,
                            loadingUp = false
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen == gen) _state.update { it.copy(loadingUp = false) }
            }
        }
    }

    fun jumpToYear(year: Int) {
        if (_state.value.jumping) return
        loadJob?.cancel()
        val gen = ++loadGen
        val catId = _state.value.category.id
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    jumping = true,
                    initialLoading = false,
                    refreshing = false,
                    loadingDown = false,
                    loadingUp = false,
                    loadMoreError = false
                )
            }
            try {
                var totalPages = _state.value.totalPages
                var page = 1
                var episodes: List<Episode>? = null
                while (page <= totalPages) {
                    val cachedResult = cached(catId, page)
                    val result = if (cachedResult != null) {
                        cachedResult
                    } else {
                        repo.loadPage(page, catId).also {
                            cache(catId, page, it)
                            delay(REQUEST_DELAY_MS)
                        }
                    }
                    totalPages = result.totalPages
                    val idx = result.episodes.indexOfFirst { it.year <= year }
                    if (idx >= 0) {
                        episodes = result.episodes.subList(idx, result.episodes.size)
                        break
                    }
                    page++
                }
                if (loadGen != gen) return@launch
                val found = episodes
                if (found != null) {
                    _state.update {
                        it.copy(
                            episodes = found,
                            startPage = page,
                            loadedEndPage = page,
                            totalPages = totalPages,
                            scrollResetToken = it.scrollResetToken + 1
                        )
                    }
                } else {
                    PlaybackEvents.tryEmit("没有找到 $year 年的节目")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen == gen) PlaybackEvents.tryEmit("跳转失败，请检查网络")
            } finally {
                if (loadGen == gen) _state.update { it.copy(jumping = false) }
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

    /** 离线缓存条目：列表徽标与缓存页共用这一份状态 */
    val downloads = loveqApp.downloads.downloads

    fun offlineEpisodes(episodes: List<Episode>) = loveqApp.downloads.enqueue(episodes)

    /** 手动删除，或听完由服务自动调用 */
    fun removeOffline(episodeId: Long) = loveqApp.downloads.remove(episodeId)

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

        /** 官网一页的条数，离线兜底时按同样的长度切一片 */
        const val PAGE_SIZE = 26
        const val REQUEST_DELAY_MS = 200L
    }
}
