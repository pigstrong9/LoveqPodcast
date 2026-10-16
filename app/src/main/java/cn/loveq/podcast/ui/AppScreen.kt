@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package cn.loveq.podcast.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.playback.MediaCache
import cn.loveq.podcast.playback.PlaybackEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 达到这个宽度就用三栏（边栏 + 列表 + 播放面板），否则单栏 + 迷你播放条。
 * 车机 DiLink 4.0（1920×1080）按社区实测的 240dpi 算是 1280×720dp，
 * 三栏能分到侧栏 218 / 列表 678 / 播放 384，很宽松。
 * 阈值压到 780dp 是为了兼容「密度比 240 更高」的可能——真机密度没实测过之前，
 * 宁可让更窄的窗口也走三栏，也不要在大屏上退化成单栏。
 */
private val ThreePaneThreshold = 780.dp

/**
 * 三栏还要求宽度至少是高度的这个倍数。
 * 车机五五分屏拿到的区域接近方形（1280×720dp 分屏后是 640×720dp），
 * 这种比例下三栏会把列表挤得比手机还窄，不如走窄屏。
 */
private const val ThreePaneAspect = 1.45f

/** 收起态迷你胶囊浮在列表上，列表底部要避让的高度（胶囊实测 82dp + 上下留白） */
private val MINI_PILL_INSET = 104.dp

/** 边栏缓存占用的低频刷新间隔：播放中缓存持续增长，只统计一次会一直停在旧值 */
private const val CACHE_POLL_MS = 30_000L

@Composable
fun AppScreen(
    viewModel: EpisodesViewModel = viewModel(),
    onRequestNotificationPermission: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progressMap by viewModel.progressMap.collectAsStateWithLifecycle()
    val lastId by viewModel.lastId.collectAsStateWithLifecycle()
    val playingMediaId by viewModel.playingMediaId.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val boostPercent by viewModel.boostPercent.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val metaTitle by viewModel.nowTitle.collectAsStateWithLifecycle()
    val metaDate by viewModel.nowDate.collectAsStateWithLifecycle()
    val immersive by viewModel.immersive.collectAsStateWithLifecycle()
    val autoResume by viewModel.autoResume.collectAsStateWithLifecycle()

    val lastProgress = lastId?.let { progressMap[it] }

    // 车机分屏调整尺寸会重建 Activity：弹层/收起态用 Saveable 持有，
    // 否则正在收听时面板会突然重新弹出、搜索层直接被关掉
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    /** 日期筛选弹窗：>0 表示从列表里哪个年份胶囊点开，0 = 用当前年兜底 */
    var dateDialogYear by rememberSaveable { mutableIntStateOf(0) }
    var playerSheetOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // 音量增强 / 倍速设置层：常驻面板与迷你面板共用同一个开关
    var tuningOpen by rememberSaveable { mutableStateOf(false) }
    val toggleTuning: () -> Unit = { tuningOpen = !tuningOpen }
    // 播放面板收起后统一缩到底部的迷你胶囊（横竖屏行为一致）
    var playerCollapsed by rememberSaveable { mutableStateOf(false) }

    // 长按列表节目进入批量选择，用于把整集离线缓存到本地
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(listOf<Episode>()) }
    val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val offlineIds = remember(downloads) {
        downloads.filter { it.state == DownloadManager.State.DONE }
            .mapTo(mutableSetOf()) { it.episode.id }
    }
    val pendingIds = remember(downloads) {
        downloads.filter { it.state != DownloadManager.State.DONE }
            .mapTo(mutableSetOf()) { it.episode.id }
    }
    val exitSelect = {
        selecting = false
        selected = emptyList()
    }
    val enterSelect: (Episode) -> Unit = { e ->
        selecting = true
        selected = listOf(e)
    }
    val toggleSelect: (Episode) -> Unit = { e ->
        selected = if (selected.any { it.id == e.id }) {
            selected.filterNot { it.id == e.id }
        } else {
            selected + e
        }
    }
    val offlineSelected = {
        viewModel.offlineEpisodes(selected)
        exitSelect()
    }
    val selectionArgs = SelectionArgs(
        selecting = selecting,
        selectedIds = selectedIds,
        offlineIds = offlineIds,
        pendingIds = pendingIds,
        onEnterSelect = enterSelect,
        onToggleSelect = toggleSelect,
        onExitSelect = exitSelect,
        onOfflineSelected = offlineSelected
    )

    // 边栏要常驻显示缓存占用：清理后、打开设置时立即重算，其余时间低频轮询——
    // 播放中缓存会边听边增长，只算一次的话这个数字永远停在启动那一刻。
    // 一个 effect 兼管两件事：key 变化会重启循环，于是先立即重算、再进入轮询
    val context = LocalContext.current
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var cacheTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheTick, settingsOpen) {
        while (true) {
            cacheBytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(context) }
            delay(CACHE_POLL_MS)
        }
    }

    val onPlay: (Int) -> Unit = { position ->
        onRequestNotificationPermission()
        viewModel.playEpisode(position)
    }
    val onResumeLast: () -> Unit = {
        onRequestNotificationPermission()
        viewModel.resumeLast()
    }

    ErrorToasts()

    val currentEp = currentEpisode(state.episodes, playingMediaId, progressMap)
    val nowTitle = metaTitle ?: currentEp?.title?.ifBlank { currentEp.date } ?: ""
    val nowDate = metaDate ?: currentEp?.date ?: ""
    val archiveCount = if (state.category.id == Category.CANTONESE_ID) index.size else 0
    val nowSub = listOf(state.category.name, nowDate).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * 状态栏要不要让出来。
     *
     * 关键：让位置的时候不能整根 Box 一起 pad —— 那样侧栏的浅紫色、播放面板的深色
     * 都会从状态栏底下断一刀，看起来像贴了一条默认白条（就是「顶栏分层」的成因）。
     * 改成每个色块自己画到屏幕最上沿、只把自己的内容挪下来，才是系统自适应的样子。
     * 隐藏状态栏时 inset 为 0，车机仍是整屏无刘海的原样。
     */
    val topInset = if (immersive) WindowInsets(0.dp, 0.dp, 0.dp, 0.dp) else WindowInsets.statusBars

    BoxWithConstraints {
        // 三栏要求「够宽」且「够扁」：
        // 车机分屏后的半幅窗口虽然可能宽度过线，但接近方形，
        // 摆下侧栏+列表+播放面板后中间列表会比手机还窄，所以这种比例要走窄屏。
        val wide = maxWidth >= ThreePaneThreshold && maxWidth >= maxHeight * ThreePaneAspect
        val landscape = maxWidth > maxHeight
        val sidebarWidth = (maxWidth * 0.17f).coerceIn(196.dp, 252.dp)
        val playerWidth = (maxWidth * 0.30f).coerceIn(320.dp, 424.dp)

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // 这里只避让导航栏/手势条。状态栏由各色块自己避让（见 topInset）：
                // 在根上 pad 会让侧栏和播放面板从状态栏底下断开，看着像贴了条白条。
                // 另外 DiLink 明明把状态栏藏了却仍上报非零 statusBars inset，
                // 跟着 pad 会在顶部多一条浅色空白
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (wide) {
                // 用 Box 让迷你胶囊浮在列表之上：收起时列表保持满高，底部不留空档
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(
                            selected = state.category,
                            onSelect = viewModel::switchCategory,
                            onSearch = { searchOpen = true },
                            onCache = { settingsOpen = true },
                            cacheLabel = cacheBytes?.let {
                                "缓存 ${formatSize(it)} · 上限 ${formatSize(MediaCache.capBytes)}"
                            },
                            topInset = topInset,
                            modifier = Modifier.width(sidebarWidth)
                        )
                        EpisodeListPane(
                            state = state,
                            progressMap = progressMap,
                            playingMediaId = playingMediaId,
                            isPlaying = isPlaying,
                            lastProgress = lastProgress,
                            showContinue = playingMediaId == null,
                            archiveCount = archiveCount,
                            onPlay = onPlay,
                            onResumeLast = onResumeLast,
                            loadNext = viewModel::loadNextPage,
                            loadPrevious = viewModel::loadPreviousPage,
                            refresh = viewModel::refresh,
                            retry = viewModel::retry,
                            onPickDate = { dateDialogYear = it },
                            onClearDateFilter = viewModel::clearDateFilter,
                            topInset = topInset,
                            modifier = Modifier.weight(1f),
                            selection = selectionArgs,
                            // 收起时迷你胶囊浮在列表上，底部留出它的高度，避免盖住最后一期
                            bottomInset = if (playingMediaId != null && playerCollapsed) MINI_PILL_INSET else 0.dp
                        )
                        AnimatedVisibility(
                            visible = playingMediaId != null && !playerCollapsed,
                            enter = expandHorizontally(tween(280)) + fadeIn(tween(200)),
                            exit = shrinkHorizontally(tween(220)) + fadeOut(tween(160))
                        ) {
                            // 进度/时长在这里才订阅：否则 AppScreen 每 500ms 跟着重组一次，
                            // 整棵列表树都会白跑一遍（小米 14 上实测 24% 掉帧的主因）
                            val position by viewModel.positionMs.collectAsStateWithLifecycle()
                            val duration by viewModel.durationMs.collectAsStateWithLifecycle()
                            PlayerSidePane(
                                title = nowTitle,
                                date = nowSub,
                                isPlaying = isPlaying,
                                positionMs = position,
                                durationMs = duration,
                                speed = speed,
                                boost = boostPercent,
                                onToggle = viewModel::togglePlayPause,
                                onSeekTo = viewModel::seekTo,
                                onSeekBack = viewModel::seekBack,
                                onSeekForward = viewModel::seekForward,
                                onSpeed = viewModel::setSpeed,
                                onBoost = viewModel::setBoost,
                                tuningVisible = tuningOpen,
                                onRequestTuning = toggleTuning,
                                onCollapse = {
                                    tuningOpen = false
                                    playerCollapsed = true
                                },
                                topInset = topInset,
                                modifier = Modifier.fillMaxHeight().width(playerWidth)
                            )
                        }
                    }
                    // 收起后缩到底部的迷你胶囊（浮层，占位不挤压列表），点它重新展开
                    AnimatedVisibility(
                        visible = playingMediaId != null && playerCollapsed,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
                        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160))
                    ) {
                        val position by viewModel.positionMs.collectAsStateWithLifecycle()
                        val duration by viewModel.durationMs.collectAsStateWithLifecycle()
                        MiniPlayer(
                            title = nowTitle,
                            isPlaying = isPlaying,
                            positionMs = position,
                            durationMs = duration,
                            speed = speed,
                            boost = boostPercent,
                            onToggle = viewModel::togglePlayPause,
                            onSeekBack = viewModel::seekBack,
                            onSeekForward = viewModel::seekForward,
                            onOpen = { playerCollapsed = false },
                            centered = true
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PhoneHeader(
                        onSearch = { searchOpen = true },
                        onCache = { settingsOpen = true },
                        topInset = topInset
                    )
                    CategoryPills(
                        selected = state.category,
                        onSelect = viewModel::switchCategory
                    )
                    EpisodeListPane(
                        state = state,
                        progressMap = progressMap,
                        playingMediaId = playingMediaId,
                        isPlaying = isPlaying,
                        lastProgress = lastProgress,
                        showContinue = playingMediaId == null,
                        archiveCount = archiveCount,
                        onPlay = onPlay,
                        onResumeLast = onResumeLast,
                        loadNext = viewModel::loadNextPage,
                        loadPrevious = viewModel::loadPreviousPage,
                        refresh = viewModel::refresh,
                        retry = viewModel::retry,
                        onPickDate = { dateDialogYear = it },
                        onClearDateFilter = viewModel::clearDateFilter,
                        // 这里不能再传 topInset：竖屏是 PhoneHeader→胶囊→列表 纵向堆叠，
                        // PhoneHeader 已经避让过状态栏，而 windowInsetsPadding 只在自己的
                        // 子树里消费 inset、不会替后面的兄弟节点消费，再 pad 一次
                        // 就在胶囊和标题之间凭空多出一条状态栏高度的空白
                        modifier = Modifier.weight(1f),
                        compact = true,
                        // 没有侧栏可用，左右滑列表就是换分类的手势；
                        // 三栏有侧栏常驻，再让列表可滑只会误触
                        onSwipeCategory = { delta -> viewModel.switchCategoryBy(delta) },
                        swipeHint = { delta ->
                            val all = Category.ALL
                            val i = all.indexOfFirst { it.id == state.category.id }
                            all.getOrNull(i + delta)?.name
                        },
                        selection = selectionArgs
                    )
                    AnimatedVisibility(
                        visible = playingMediaId != null,
                        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(220)),
                        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180))
                    ) {
                        val position by viewModel.positionMs.collectAsStateWithLifecycle()
                        val duration by viewModel.durationMs.collectAsStateWithLifecycle()
                        MiniPlayer(
                            title = nowTitle,
                            isPlaying = isPlaying,
                            positionMs = position,
                            durationMs = duration,
                            speed = speed,
                            boost = boostPercent,
                            onToggle = viewModel::togglePlayPause,
                            onSeekBack = viewModel::seekBack,
                            onSeekForward = viewModel::seekForward,
                            onOpen = { playerSheetOpen = true },
                            // 横屏用 Apple Music 那种居中胶囊，竖屏仍是底部通栏
                            centered = landscape
                        )
                    }
                }
            }
        }

        if (!wide && playerSheetOpen && playingMediaId != null) {
            val position by viewModel.positionMs.collectAsStateWithLifecycle()
            val duration by viewModel.durationMs.collectAsStateWithLifecycle()
            PlayerSheet(
                title = nowTitle,
                date = nowSub,
                isPlaying = isPlaying,
                positionMs = position,
                durationMs = duration,
                speed = speed,
                boost = boostPercent,
                onToggle = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
                onSeekBack = viewModel::seekBack,
                onSeekForward = viewModel::seekForward,
                onSpeed = viewModel::setSpeed,
                onBoost = viewModel::setBoost,
                tuningVisible = tuningOpen,
                onRequestTuning = toggleTuning,
                onClose = {
                    tuningOpen = false
                    playerSheetOpen = false
                },
                topInset = topInset
            )
        }
    }

    if (dateDialogYear != 0) {
        DateFilterDialog(
            initialYear = dateDialogYear,
            onDismiss = { dateDialogYear = 0 },
            onPick = { year, month ->
                dateDialogYear = 0
                viewModel.jumpToDate(year, month)
            }
        )
    }

    if (searchOpen) {
        // 结果只在搜索面板真正打开时才算：面板关着时索引变化（首次建索引）
        // 没必要白白过滤一遍全库
        val searchResults = remember(searchQuery, index) {
            val terms = searchQuery.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (terms.isEmpty()) emptyList()
            else index.filter { e ->
                terms.all { t -> e.title.contains(t, ignoreCase = true) || e.date.contains(t) }
            }
        }
        SearchPanel(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            history = history,
            results = searchResults.take(100),
            resultTotal = searchResults.size,
            progressMap = progressMap,
            indexing = indexing,
            onPlay = { id ->
                onRequestNotificationPermission()
                viewModel.playEpisodeById(id)
                searchOpen = false
            },
            onClose = { searchOpen = false }
        )
    }

    if (settingsOpen) {
        SettingsDialog(
            cacheBytes = cacheBytes,
            // 上限在首次 get() 时按可用空间算定后就不再变，这里直接读那份快照
            cacheCapBytes = MediaCache.capBytes,
            downloads = downloads,
            immersive = immersive,
            onImmersiveChange = viewModel::setImmersive,
            autoResume = autoResume,
            onAutoResumeChange = viewModel::setAutoResume,
            onRemove = viewModel::removeOffline,
            onRetry = viewModel::retryOffline,
            onCleared = { cacheTick++ },
            onDismiss = { settingsOpen = false }
        )
    }
}

private fun currentEpisode(
    episodes: List<Episode>,
    playingMediaId: String?,
    progressMap: Map<Long, Progress>
): Episode? {
    val id = playingMediaId?.toLongOrNull() ?: return null
    return episodes.firstOrNull { it.id == id } ?: progressMap[id]?.let {
        Episode(it.id, it.date, it.title)
    }
}

@Composable
private fun ErrorToasts() {
    val context = LocalContext.current
    // 错误存成可回放的状态：息屏/后台期间发生的错误回到界面后仍能补提示，
    // 不像 SharedFlow 那样在没人订阅时被直接丢掉
    val message by PlaybackEvents.error.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        PlaybackEvents.consume()
    }
}
