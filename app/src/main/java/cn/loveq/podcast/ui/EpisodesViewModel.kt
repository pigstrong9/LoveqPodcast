package cn.loveq.podcast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.loveq.podcast.LoveqApp
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.EpisodeCacheStore
import cn.loveq.podcast.data.LoveqParser
import cn.loveq.podcast.data.PageResult
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.data.RateLimitedException
import cn.loveq.podcast.data.ThemeMode
import cn.loveq.podcast.data.shouldAutoResume
import cn.loveq.podcast.playback.PlaybackEvents
import cn.loveq.podcast.playback.PlayerConnection
import cn.loveq.podcast.playback.SleepTimer
import cn.loveq.podcast.playback.VocalBoost
import cn.loveq.podcast.playback.VolumeBoost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** 日期筛选：0 = 未筛选；[filterMonth] 为 0 表示整年 */
    val filterYear: Int = 0,
    val filterMonth: Int = 0,
    /** 筛选后已经翻到归档末尾，往下不会再有更早的节目 */
    val filterExhausted: Boolean = false,
    val scrollResetToken: Int = 0,
    /** 非空的定位目标：列表数据就位后滚动到这一期（用于「继续收听」跳回原处） */
    val scrollToId: Long? = null,
    val error: String? = null
) {
    /** 是否处于日期筛选态：此时按相邻月份/年份加载，也不该显示官网的总期数 */
    val filtering: Boolean get() = filterYear != 0

    val endReached: Boolean
        // 筛选态按「月/年」一节一节地加载，页游标不参与判断：
        // 只有真的翻到归档最早一段才算到底
        get() = if (filtering) filterExhausted
        else loadedEndPage >= totalPages && !initialLoading
}

class EpisodesViewModel(app: Application) : AndroidViewModel(app) {

    private val loveqApp = app as LoveqApp
    private val repo = loveqApp.repository
    private val store = loveqApp.progressStore

    /** 在线升级的状态与动作。放在这里而不是直接让界面拿 LoveqApp：
         跟 immersive / autoResume 一样，界面只跟 ViewModel 打交道 */
    val updateStatus = loveqApp.updates.status

    fun checkUpdate() = loveqApp.updates.check(manual = true)

    fun downloadAndInstallUpdate() = loveqApp.updates.downloadAndInstall()

    fun installPendingUpdate() = loveqApp.updates.installPending()

    fun currentVersionName(): String = loveqApp.updates.currentVersionName()

    fun openReleasePage() = loveqApp.updates.openReleasePage()

    fun unknownSourcesIntent() = loveqApp.updates.unknownSourcesIntent()

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

    /** 是否隐藏顶部状态栏：车机隐藏，手机/平板可关掉以看到时间与通知图标 */
    val immersive: StateFlow<Boolean> = store.immersive

    fun setImmersive(hidden: Boolean) = store.setImmersive(hidden)

    /** 启动时是否自动续播上次收听的那期 */
    val autoResume: StateFlow<Boolean> = store.autoResume

    fun setAutoResume(on: Boolean) = store.setAutoResume(on)

    /** 外观模式：跟随系统 / 浅色 / 深色 */
    val themeMode: StateFlow<ThemeMode> = store.themeMode

    fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)

    fun setBoost(percent: Int) {
        VolumeBoost.apply(percent)
        store.boostPercent = percent
    }

    /** 跳过空白：默认关，开关在播放设置面板，时长在设置页（2–5 分钟） */
    val skipBlank: StateFlow<Boolean> = store.skipBlank

    fun setSkipBlank(on: Boolean) = store.setSkipBlank(on)

    val skipBlankMinutes: StateFlow<Int> = store.skipBlankMinutes

    fun setSkipBlankMinutes(minutes: Int) = store.setSkipBlankMinutes(minutes)

    /** 人声增强：开关要同时落到音频线程与本地存储 */
    val vocalBoost: StateFlow<Boolean> = store.vocalBoost

    fun setVocalBoost(on: Boolean) {
        VocalBoost.apply(on)
        store.setVocalBoost(on)
    }

    /** 睡眠定时是进程级状态，不落盘；计时本身在 PlaybackService 里 */
    val sleepState: StateFlow<SleepTimer.State> = SleepTimer.state

    fun setSleepMinutes(minutes: Int?) = SleepTimer.setMinutes(minutes)

    fun setSleepAtEpisodeEnd(on: Boolean) = SleepTimer.setStopAtEpisodeEnd(on)

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
        restoreListPosition()
        autoResumeOnLaunch()
    }

    /**
     * 启动时恢复上次浏览的列表位置（分类 + 年月筛选）。
     *
     * 用户翻到 2010 年的某个月后退出，再打开时若从最新一期重新开始，
     * 想找回去要翻几十页。这里在首屏加载完成后把列表定位回上次的时段。
     */
    private fun restoreListPosition() {
        val saved = store.listPosition()
        if (saved.year == 0) return
        // 自动续播会把列表定位到正在播的那期，这里不再重复跳
        if (shouldAutoResume(store.autoResume.value, store.lastId.value?.let(store::get))) return
        viewModelScope.launch {
            // 等首屏加载结束，否则会被 jumpToDate 里的 loadJob.cancel 打断
            state.first { !it.initialLoading }
            if (saved.categoryId != 0 && saved.categoryId != _state.value.category.id) {
                Category.ALL.firstOrNull { it.id == saved.categoryId }?.let { switchCategory(it) }
                state.first { !it.initialLoading }
            }
            jumpToDate(saved.year, saved.month)
        }
    }

    /**
     * 启动自动续播（设置里可关，默认关）。
     *
     * 两处讲究：
     * · 只在 ViewModel 构造时决定这一次 —— ViewModel 随 Activity 存活，转屏、切后台回来
     *   都不会重放，只有用户真的重新打开应用（Activity 重建）才再来一次。
     * · 判断与起播交给 [PlayerConnection.resumeOnLaunch]：要等服务连上才知道服务里
     *   是不是已经有队列，否则会把正在听的节目重建一遍、位置退回上次落盘点。
     */
    private fun autoResumeOnLaunch() {
        if (!shouldAutoResume(store.autoResume.value, store.lastId.value?.let(store::get))) return
        connection.resumeOnLaunch { resumeLast() }
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
                // 日期筛选是「这个分类的某段时间」，换分类后继续生效会指向错的节目
                filterYear = 0,
                filterMonth = 0,
                filterExhausted = false,
                scrollResetToken = it.scrollResetToken + 1,
                scrollToId = null,
                error = null
            )
        }
        // 换分类后回到该分类的最新一期，上次的筛选位置作废
        store.setListPosition(category.id, 0, 0)
        loadInitial()
    }

    /**
     * 左右滑动列表切换分类。越界不回绕，停在首尾；
     * 返回 false 表示已经在边界，调用方好决定要不要给触觉反馈。
     */
    fun switchCategoryBy(delta: Int): Boolean {
        val all = Category.ALL
        val current = _state.value.category.id
        val i = all.indexOfFirst { it.id == current }
        val next = (i + delta).coerceIn(0, all.lastIndex)
        if (next == i) return false
        switchCategory(all[next])
        return true
    }

    private fun cached(catId: Int, page: Int): PageResult? =
        pageCache[catId]?.get(page)

    private fun cache(catId: Int, page: Int, result: PageResult) {
        pageCache.getOrPut(catId) { mutableMapOf() }[page] = result
    }

    /**
     * 仅在调用方明确要求时退出日期筛选。
     * 必须跟在「替换列表」的那次 update 上，不能单独提前清，理由见 [loadInitial]。
     */
    private fun ListUiState.withoutFilter(clear: Boolean) =
        if (clear) copy(filterYear = 0, filterMonth = 0, filterExhausted = false) else this

    /**
     * 整表替换（首屏 / 重试 / 退出筛选）。
     *
     * [clearFilter] 为真时顺带退出日期筛选，而且是**在换数据的那一次 update 里一起清**。
     * 先单独把筛选标志清掉会留下筛选时的分页窗口（startPage=41 之类），新数据到位之前的
     * 那一帧里 loadPreviousPage 的筛选闸门已经失效，会把完全不相邻的页拼到列表头上。
     */
    private fun loadInitial(clearFilter: Boolean = false) {
        loadJob?.cancel()
        val gen = ++loadGen
        val catId = _state.value.category.id
        // 退出筛选/重试都意味着回到最新一期，记住的新位置也要一起作废
        if (clearFilter) store.setListPosition(catId, 0, 0)
        // 被这里打断的跳转/翻页，其 finally 会因为 loadGen 变了而跳过复位标志，
        // 所以「整表替换」必须自己把所有加载标志归零。
        // 漏掉 jumping 的代价最难查：它会永久停 in true，
        // 之后每一次日期跳转、每一次上下翻页都被它挡在门口，顶部进度条还一直转
        _state.update {
            it.copy(
                jumping = false,
                refreshing = false,
                loadingDown = false,
                loadingUp = false,
                loadMoreError = false
            )
        }
        // 抓过第 1 页就先立刻画出来，再后台对一遍新数据。
        // 分类来回切、或者从筛选态退出来的时候，不该每次都干等一次网络往返
        cached(catId, 1)?.let { first ->
            _state.update {
                it.copy(
                    episodes = first.episodes,
                    startPage = 1,
                    loadedEndPage = 1,
                    totalPages = first.totalPages,
                    initialLoading = false,
                    offline = false,
                    loadMoreError = false,
                    error = null
                ).withoutFilter(clearFilter)
            }
        }
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
                    ).withoutFilter(clearFilter)
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
                    // 屏幕上已经有缓存画出来的列表时，不能因为一次刷新失败
                    // 就把整屏换成错误页 —— 那是把能看到的东西弄没了。
                    // 这条分支列表没变，所以也不能清筛选：清了标题就和列出不符了
                    if (_state.value.episodes.isNotEmpty()) {
                        PlaybackEvents.report("刷新失败，显示的是上次加载的内容")
                    } else {
                        _state.update {
                            it.copy(
                                initialLoading = false,
                                error = if (e is RateLimitedException) {
                                    rateLimitText()
                                } else {
                                    "加载失败：${e.message ?: "网络异常"}"
                                }
                            ).withoutFilter(clearFilter)
                        }
                    }
                } else {
                    _state.update {
                        it.copy(
                            episodes = fallback.take(LoveqParser.PAGE_SIZE),
                            startPage = 1,
                            loadedEndPage = 1,
                            // 这个 1 是「一共多少页还不知道」，不是「真只有一页」。
                            // jumpToDate 进来会先校准它，别处别拿它当二分查找的上界
                            totalPages = 1,
                            initialLoading = false,
                            loadMoreError = false,
                            offline = true,
                            error = null
                        ).withoutFilter(clearFilter)
                    }
                }
            }
        }
    }

    /** 错误页的「重试」：语义和下拉刷新一样是回到最新一期，筛选态一并退掉 */
    fun retry() {
        _state.update { it.copy(initialLoading = true, error = null) }
        loadInitial(clearFilter = true)
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
                        // 下拉刷新的语义是「回到最新」，筛选态必须一起退掉
                        filterYear = 0,
                        filterMonth = 0,
                        filterExhausted = false,
                        scrollToId = null,
                        error = null
                    )
                }
                // 刷新后列表回到最新一期，上次的筛选位置作废
                store.setListPosition(catId, 0, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen != gen) return@launch
                _state.update { it.copy(refreshing = false, initialLoading = false) }
                PlaybackEvents.report(
                    if (e is RateLimitedException) "刷新失败：${rateLimitText()}"
                    else "刷新失败，请检查网络"
                )
            }
        }
    }

    /**
     * 被官网限流时的统一说辞。
     *
     * 这类失败最容易被误读成「你网络有问题」——用户于是去重启路由器、换 4G，
     * 而实际上什么都做不了，只能等。所以要说清是「等」，不是「修」。
     */
    private fun rateLimitText(): String {
        // 冷却可能刚好在「抛出异常」与「拼文案」之间的几毫秒里到期；不兜底就会拼出
        //「约 后自动恢复」这种残句
        val remaining = loveqApp.rateLimitGuard.describeRemaining()
        return if (remaining.isEmpty()) "官网限制了访问，请稍后再试"
        else "官网限制了访问，约 ${remaining}后自动恢复"
    }

    fun loadNextPage() {
        val s = _state.value
        if (s.loadingDown || s.jumping) return
        // 筛选态按相邻月份/年份续接，页游标只服务于未筛选的连续浏览
        if (s.filtering) {
            loadAdjacentPeriod(older = true)
            return
        }
        // 翻完全库就停：筛选态不再走这条路径，也就不需要再拿筛选标志兜底
        if (s.loadedEndPage >= s.totalPages) return
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
                // 页脚那个「加载失败，点此重试」对限流是误导：点多少次都还是失败
                if (e is RateLimitedException) PlaybackEvents.report(rateLimitText())
            }
        }
    }

    fun loadPreviousPage() {
        val s = _state.value
        if (s.loadingUp || s.jumping) return
        if (s.filtering) {
            loadAdjacentPeriod(older = false)
            return
        }
        if (s.startPage <= 1) return
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

    /**
     * 筛选态继续往相邻的月份/年份加载。
     *
     * 筛选只是一个起点：往下滑接更早的一个月，往上滑接更新的一个月（整年筛选时单位是年）。
     * 边界从列表首尾**实际的那一期**算，而不是一直用最初选中的时段 ——
     * 连续滑过几个月之后仍然接得上。
     */
    private fun loadAdjacentPeriod(older: Boolean) {
        val s = _state.value
        if (!s.filtering) return
        if (older && (s.loadingDown || s.filterExhausted)) return
        if (!older && s.loadingUp) return
        val catId = s.category.id
        val gen = loadGen
        var target = boundaryPeriod(s, older) ?: return
        val (curYear, curMonth) = currentPeriod()

        viewModelScope.launch {
            _state.update {
                if (older) it.copy(loadingDown = true, loadMoreError = false)
                else it.copy(loadingUp = true)
            }
            try {
                var found: List<Episode>? = null
                var atArchiveEnd = false
                var toSave: Pair<Pair<Int, Int>, PeriodResult>? = null
                var skipped = 0
                while (skipped < MAX_EMPTY_PERIODS) {
                    if (loadGen != gen) return@launch
                    if (!withinPeriodBounds(target, older, curYear, curMonth)) {
                        if (older) atArchiveEnd = true
                        break
                    }
                    val hit = cachedPeriod(catId, target.first, target.second)
                    if (hit != null) {
                        found = hit.episodes
                        atArchiveEnd = older && isArchiveStart(target.first, target.second)
                        break
                    }
                    val result = fetchPeriod(catId, target.first, target.second)
                    if (result.episodes.isNotEmpty()) {
                        found = result.episodes
                        atArchiveEnd = older &&
                            (result.atArchiveEnd || isArchiveStart(target.first, target.second))
                        toSave = target to result
                        break
                    }
                    // 这个月/年没有节目（停更、换季）：继续往前找，不要在这里断掉
                    if (result.atArchiveEnd) {
                        atArchiveEnd = true
                        break
                    }
                    target = shiftPeriod(target.first, target.second, older)
                    skipped++
                }
                if (loadGen != gen) return@launch
                val fresh = found
                if (fresh != null) {
                    _state.update {
                        val existing = it.episodes.mapTo(HashSet<Long>(it.episodes.size * 2)) { e -> e.id }
                        val added = fresh.filterNot { e -> e.id in existing }
                        it.copy(
                            episodes = if (older) it.episodes + added else added + it.episodes,
                            loadingDown = if (older) false else it.loadingDown,
                            loadingUp = if (older) it.loadingUp else false,
                            filterExhausted = if (older) atArchiveEnd else it.filterExhausted,
                            loadMoreError = if (older) false else it.loadMoreError
                        )
                    }
                    // 整段落盘：下次再滑到这里（或直接选这个月）不再联网
                    toSave?.let { (period, result) ->
                        withContext(Dispatchers.IO) {
                            loveqApp.episodeCache.save(
                                catId, period.first, period.second,
                                result.episodes, result.totalPages
                            )
                        }
                    }
                } else {
                    _state.update {
                        if (older) it.copy(loadingDown = false, filterExhausted = atArchiveEnd)
                        else it.copy(loadingUp = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen == gen) {
                    _state.update {
                        if (older) it.copy(loadingDown = false, loadMoreError = true)
                        else it.copy(loadingUp = false)
                    }
                    if (e is RateLimitedException) PlaybackEvents.report(rateLimitText())
                }
            }
        }
    }

    /** 列表首/尾那一期所在的时段往相邻方向挪一格；年筛选的单位是年 */
    private fun boundaryPeriod(s: ListUiState, older: Boolean): Pair<Int, Int>? {
        val yearOnly = s.filterMonth == 0
        val usable: (Episode) -> Boolean =
            if (yearOnly) ({ e -> e.year != 0 }) else ({ e -> e.year != 0 && e.month != 0 })
        val edge = if (older) s.episodes.lastOrNull(usable) else s.episodes.firstOrNull(usable)
        val year = edge?.year ?: s.filterYear
        if (year == 0) return null
        val month = if (yearOnly) 0 else (edge?.month ?: s.filterMonth)
        return shiftPeriod(year, month, older)
    }

    private fun shiftPeriod(year: Int, month: Int, older: Boolean): Pair<Int, Int> {
        if (month == 0) return (year + if (older) -1 else 1) to 0
        return if (older) {
            if (month <= 1) (year - 1) to 12 else year to (month - 1)
        } else {
            if (month >= 12) (year + 1) to 1 else year to (month + 1)
        }
    }

    private fun withinPeriodBounds(
        target: Pair<Int, Int>,
        older: Boolean,
        curYear: Int,
        curMonth: Int
    ): Boolean {
        val (y, m) = target
        return if (older) {
            if (m == 0) y >= ARCHIVE_START_YEAR
            else y * 100 + m >= ARCHIVE_START_YEAR * 100 + ARCHIVE_START_MONTH
        } else {
            if (m == 0) y <= curYear
            else y * 100 + m <= curYear * 100 + curMonth
        }
    }

    private fun isArchiveStart(year: Int, month: Int): Boolean =
        if (month == 0) year <= ARCHIVE_START_YEAR
        else year * 100 + month <= ARCHIVE_START_YEAR * 100 + ARCHIVE_START_MONTH

    private fun currentPeriod(): Pair<Int, Int> {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.YEAR) to (cal.get(java.util.Calendar.MONTH) + 1)
    }

    /**
     * 读磁盘上已加载过的整月/整年。
     *
     * 当前月（年）还在更新，不能信任缓存；历史时段才直接返回。
     * 只存过整年时也能拆出某个月，避免为了一个月再把整年翻一遍。
     */
    private suspend fun cachedPeriod(catId: Int, year: Int, month: Int): EpisodeCacheStore.Entry? {
        if (!loveqApp.episodeCache.isStable(year, month)) return null
        return withContext(Dispatchers.IO) {
            loveqApp.episodeCache.load(catId, year, month) ?: if (month > 0) {
                loveqApp.episodeCache.load(catId, year, 0)?.let { yearEntry ->
                    val inMonth = yearEntry.episodes.filter { it.yearMonth == year * 100 + month }
                    if (inMonth.isEmpty()) null
                    else EpisodeCacheStore.Entry(inMonth, yearEntry.totalPages, yearEntry.savedAt)
                }
            } else {
                null
            }
        }
    }

    private data class PeriodResult(
        val episodes: List<Episode>,
        val totalPages: Int,
        val atArchiveEnd: Boolean
    )

    /**
     * 从官网取某个整月/整年。
     *
     * 列表按日期新→旧排布，所以「页内最新一期」随页码单调不增，可以用二分定位边界页：
     * 粤语节目有全库本地索引，通常 1～2 次请求就能命中；其余分类最多 7 次。
     */
    private suspend fun fetchPeriod(catId: Int, year: Int, month: Int): PeriodResult {
        val granular = month > 0
        val target = if (granular) year * 100 + month else year
        val key: (Episode) -> Int = if (granular) Episode::yearMonth else Episode::year
        // 日期解析不出来的期（官网偶有）不能拿来定位边界：
        // 它的 key 是 0（或「年*100」这种缺月的假值），比真实位置旧一大截，
        // 当成页首会把二分边界甩到很后面，当成页尾会让这一段被提前判定结束
        val usable: (Episode) -> Boolean =
            if (granular) ({ e -> e.year != 0 && e.month != 0 }) else ({ e -> e.year != 0 })

        var totalPages = _state.value.totalPages
        suspend fun pageAt(p: Int): PageResult {
            // 缓存命中也要校准：state 里的 totalPages 可能是离线兜底写的 1，
            // 只有真实页里的值才能把它纠正过来
            cached(catId, p)?.let { hit ->
                if (hit.totalPages != totalPages) totalPages = hit.totalPages
                return hit
            }
            // 这里不再额外 sleep：二分最多 7 次请求，而且用户正盯着屏幕等结果。
            // 请求间隔交给 LoveqRepository 里的全局闸门统一兜底，够客气了
            return repo.loadPage(p, catId).also { r ->
                cache(catId, p, r)
                if (r.totalPages != totalPages) totalPages = r.totalPages
            }
        }

        // 先把总页数校准再定搜索区间。断网启动过一次之后 state 里是兜底的 1，
        // 直接拿它当上界的话区间就是 [1,1]，跳任何年份都只会「没有找到」，
        // 而且永远学不乖。第 1 页通常早就在缓存里，这一步一般是零成本
        pageAt(1)
        // 索引只用来加速：换算出候选页交给 findTargetPage，它会用真实页首校验后才收窄
        val guess = if (catId != Category.CANTONESE_ID) {
            null
        } else {
            val idx = index.value
            val pos = if (idx.isEmpty()) -1
            else idx.indexOfFirst { usable(it) && key(it) <= target }
            if (pos < 0) null else pos / LoveqParser.PAGE_SIZE + 1
        }
        val hitPage = findTargetPage(
            target = target,
            firstPage = 1,
            lastPage = totalPages,
            guess = guess,
            headKey = { pageAt(it).episodes.firstOrNull(usable)?.let(key) }
        )
        // 归档里根本没有这一段，基本都是已经越过最早一期
        if (hitPage == 0) return PeriodResult(emptyList(), totalPages, atArchiveEnd = true)

        // 目标那一段的开头可能正好压在上页末尾，所以从上一页开始收
        val from = (hitPage - 1).coerceAtLeast(1)
        val matched = ArrayList<Episode>()
        var page = from
        var atEnd = false
        while (page <= totalPages) {
            val r = pageAt(page)
            matched += r.episodes.filter { key(it) == target }
            // 页尾也只认日期可用的一期：脏日期会让「已经越过目标」凭空成立，
            // 于是这一段被悄悄截短
            val last = r.episodes.lastOrNull(usable)
            if (last == null || key(last) < target) break
            page++
        }
        // 一路收到最后一页都没越过目标 = 这一段就是归档的尾巴
        if (page > totalPages) atEnd = true
        val seen = HashSet<Long>(matched.size * 2)
        return PeriodResult(matched.filter { seen.add(it.id) }, totalPages, atEnd)
    }

    /**
     * 定位到某年 / 某年某月。
     *
     * 已经加载过的历史时段直接从磁盘读，不再联网 —— 节目列表只会新增，
     * 历史月份的内容不会变；当前月（年）还在更新，仍然走官网。
     * 没缓存时才去官网二分定位，逻辑见 [fetchPeriod]。
     */
    fun jumpToDate(year: Int, month: Int = 0, scrollToId: Long? = null) {
        if (_state.value.jumping) return
        loadJob?.cancel()
        val gen = ++loadGen
        val catId = _state.value.category.id
        val label = if (month > 0) "$year 年 $month 月" else "$year 年"

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
                val cached = cachedPeriod(catId, year, month)
                val result = if (cached != null) {
                    PeriodResult(
                        episodes = cached.episodes,
                        totalPages = cached.totalPages,
                        atArchiveEnd = isArchiveStart(year, month)
                    )
                } else {
                    fetchPeriod(catId, year, month)
                }
                if (loadGen != gen) return@launch
                if (result.episodes.isEmpty()) {
                    _state.update { it.copy(totalPages = result.totalPages) }
                    PlaybackEvents.report("没有找到 $label 的节目")
                    return@launch
                }
                _state.update {
                    it.copy(
                        episodes = result.episodes,
                        // 筛选态一节一节加载，页游标不参与；给个一致值即可
                        startPage = 1,
                        loadedEndPage = 1,
                        totalPages = result.totalPages,
                        filterYear = year,
                        filterMonth = month,
                        filterExhausted = result.atArchiveEnd || isArchiveStart(year, month),
                        scrollResetToken = it.scrollResetToken + 1,
                        scrollToId = scrollToId
                    )
                }
                // 记住这次浏览的时段，下次打开应用直接回到这里
                store.setListPosition(catId, year, month)
                // 月份跳转至少再带上前一个月：最新月份往往只有几集，
                // 单独一屏会显得空，换个月份也只看到零散几期
                if (month > 0) loadAdjacentPeriod(older = true)
                if (cached == null) {
                    withContext(Dispatchers.IO) {
                        loveqApp.episodeCache.save(
                            catId, year, month, result.episodes, result.totalPages
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadGen == gen) {
                    PlaybackEvents.report(
                        if (e is RateLimitedException) "跳转失败：${rateLimitText()}"
                        else "跳转失败，请检查网络"
                    )
                }
            } finally {
                if (loadGen == gen) _state.update { it.copy(jumping = false) }
            }
        }
    }

    /** 退出日期筛选：回到最新一期（第 1 页有缓存就秒回） */
    fun clearDateFilter() {
        // 这里只动滚动。筛选标志交给 loadInitial 在换数据的那一次 update 里一起清：
        // 提前单独清会留下「标志已经清了、屏幕上还是筛选时那一段、分页窗口停在第 41 页」
        // 的空档，那一帧里 loadPreviousPage 的筛选闸门失效，会把不相邻的页拼到列表头上
        _state.update {
            it.copy(
                // 「看全部」的语义是回到最新一期，不重置滚动会停在筛选时的位置
                scrollResetToken = it.scrollResetToken + 1
            )
        }
        loadInitial(clearFilter = true)
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

    /**
     * 继续收听上次那期：先把列表定位到那一期所在的年月并滚到它，
     * 再开始播放。否则听 2010 年的节目时，列表还停在最新的 2026 年，
     * 想找到正在播的这一期得翻几十页。
     */
    fun resumeLast() {
        val id = lastId.value ?: return
        moveListToEpisode(id)
        playEpisodeById(id)
    }

    /** 把列表定位到某一期所在的年月，列表数据就位后滚动到它 */
    private fun moveListToEpisode(id: Long) {
        val inList = _state.value.episodes.firstOrNull { it.id == id }
        val inIndex = if (inList == null) index.value.firstOrNull { it.id == id } else null
        val episode = inList ?: inIndex
            ?: store.get(id)?.let { Episode(id, it.date, it.title) }
            ?: return
        if (episode.year == 0) return
        val s = _state.value
        if (s.filterYear == episode.year && s.filterMonth == episode.month) {
            // 已经在同一时段，直接滚到那一期
            _state.update {
                it.copy(
                    scrollResetToken = it.scrollResetToken + 1,
                    scrollToId = id
                )
            }
            return
        }
        viewModelScope.launch {
            // 主归档的节目不在当前分类时先切回粤语节目，
            // 否则 jumpToDate 会在错的分类里找，最后只报一句「没有找到」
            if (inIndex != null && _state.value.category.id != Category.CANTONESE_ID) {
                Category.ALL.firstOrNull { it.id == Category.CANTONESE_ID }?.let { switchCategory(it) }
                state.first { !it.initialLoading }
            }
            jumpToDate(episode.year, episode.month, scrollToId = id)
        }
    }

    /** 离线缓存条目：列表徽标与缓存页共用这一份状态 */
    val downloads = loveqApp.downloads.downloads

    fun offlineEpisodes(episodes: List<Episode>) = loveqApp.downloads.enqueue(episodes)

    /** 手动删除，或听完由服务自动调用 */
    fun removeOffline(episodeId: Long) = loveqApp.downloads.remove(episodeId)

    /** 缓存失败的期重新排队（条目仍在列表里，不重试就再也不会自己开始） */
    fun retryOffline(episodeId: Long) = loveqApp.downloads.retry(episodeId)

    fun togglePlayPause() = connection.togglePlayPause()
    fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
    fun seekBack() = connection.seekBack()
    fun seekForward() = connection.seekForward()
    fun setSpeed(value: Float) = connection.setSpeed(value)

    /** 清空已加载的节目列表（磁盘上的月份缓存 + 本次进程里的页缓存） */
    fun clearEpisodeCache() {
        pageCache.clear()
        viewModelScope.launch(Dispatchers.IO) {
            loveqApp.episodeCache.clear()
            PlaybackEvents.report("节目列表缓存已清理")
        }
    }

    override fun onCleared() {
        connection.release()
    }

    private companion object {
        const val QUEUE_AHEAD = 50

        /** 归档最早一期是 2003.06.15，再往前就没有更早的月份了 */
        const val ARCHIVE_START_YEAR = 2003
        const val ARCHIVE_START_MONTH = 6

        /** 相邻月份没有节目时最多再尝试几个时段，避免停更分类连续打一串请求 */
        const val MAX_EMPTY_PERIODS = 3
    }
}
