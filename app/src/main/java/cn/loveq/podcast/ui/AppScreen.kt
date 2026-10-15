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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.playback.MediaCache
import cn.loveq.podcast.playback.PlaybackEvents
import cn.loveq.podcast.ui.theme.AppColors
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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

    val lastProgress = lastId?.let { progressMap[it] }

    // 车机分屏调整尺寸会重建 Activity：弹层/收起态用 Saveable 持有，
    // 否则正在收听时面板会突然重新弹出、搜索层直接被关掉
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchResults = remember(searchQuery, index) {
        val terms = searchQuery.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) emptyList()
        else index.filter { e ->
            terms.all { t -> e.title.contains(t, ignoreCase = true) || e.date.contains(t) }
        }
    }

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

    // 边栏要常驻显示缓存占用，清理后重新统计
    val context = LocalContext.current
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var cacheTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheTick) {
        cacheBytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(context) }
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
                            cacheLabel = cacheBytes?.let { "缓存 ${formatSize(it)} · 上限 2 GB" },
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
            downloads = downloads,
            immersive = immersive,
            onImmersiveChange = viewModel::setImmersive,
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

@Composable
private fun DialogHost(
    onDismiss: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 弹窗是独立窗口，主窗口那套状态栏避让传不进来。设置里把「隐藏状态栏」
        // 关掉的一瞬间，状态栏就会直接压在标题和开关上，而 AlertDialog 默认
        // 不响应外部点击、没有遮罩可以救场，只能自己让开。
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            .widthIn(max = 460.dp),
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.Ink,
                fontWeight = FontWeight.Bold
            )
        },
        text = text,
        shape = RoundedCornerShape(24.dp),
        containerColor = AppColors.Card,
        titleContentColor = AppColors.Ink,
        textContentColor = AppColors.Body,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmText, color = AppColors.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it, color = AppColors.Muted) } }
        }
    )
}

/**
 * 年份 + 月份选择。
 *
 * 顶栏的日历按钮去掉后，入口变成列表里的年份胶囊，所以这里必须让人看清
 * 当前选的是哪一年；月份点一下就生效并关闭 —— 车机上少一步确认就少一次分心。
 */
@Composable
private fun DateFilterDialog(
    initialYear: Int,
    onDismiss: () -> Unit,
    onPick: (year: Int, month: Int) -> Unit
) {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val years = (currentYear downTo 2003).toList()
    var year by rememberSaveable { mutableIntStateOf(initialYear) }
    val yearRow = rememberLazyListState()
    // 打开就得看见当前选中的年份（可能是用户翻到 2016 年时点进来的），
    // 已经在视野里就别动，免得点选时整排年份跟着弹
    LaunchedEffect(year) {
        val i = years.indexOf(year)
        if (i >= 0 && yearRow.layoutInfo.visibleItemsInfo.none { it.index == i }) {
            yearRow.scrollToItem(i)
        }
    }

    DialogHost(
        onDismiss = onDismiss,
        title = "跳转到日期",
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 手机竖屏高度有限，月份铺开有四行，超高时得能滚
                    .verticalScroll(rememberScrollState())
            ) {
                Text("年份", style = MaterialTheme.typography.titleSmall, color = AppColors.Muted)
                Spacer(Modifier.height(8.dp))
                // 年份只占一行：23 个年份要是全部平铺，弹窗比屏幕还高，
                // 月份会被挤出屏幕外根本点不到
                LazyRow(
                    state = yearRow,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(years, key = { it }) { y ->
                        DateChip(label = "$y", selected = y == year, onClick = { year = y })
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("月份", style = MaterialTheme.typography.titleSmall, color = AppColors.Muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DateChip(label = "全年", selected = false, onClick = { onPick(year, 0) })
                    (1..12).forEach { m ->
                        DateChip(label = "$m 月", selected = false, onClick = { onPick(year, m) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "选好后列表只保留那一段时间，标题右边的「看全部」随时退回完整列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        ),
        color = if (selected) Color.White else AppColors.Body,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.Red else AppColors.Canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun SettingsDialog(
    cacheBytes: Long?,
    downloads: List<DownloadManager.Entry>,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    onRemove: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }

    DialogHost(
        onDismiss = onDismiss,
        title = "设置",
        text = {
            Column(
                Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
            ) {
                // ---- 显示 ----
                Text(
                    "显示",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "隐藏顶部状态栏",
                    subtitle = "车机建议开启，顶栏不占画面、内容顶到上沿；" +
                        "手机和平板建议关闭，才能一直看到时间和通知图标。",
                    checked = immersive,
                    onChange = onImmersiveChange
                )

                Spacer(Modifier.height(22.dp))

                // ---- 缓存 ----
                Text(
                    "收听缓存",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "节目缓存：" + (cacheBytes?.let { formatSize(it) } ?: "统计中…") + " / 2 GB",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "听过的片段自动存入本地，重听、续播、回退 30 秒不再消耗流量。" +
                        "满 2 GB 后自动淘汰最久未听的节目；清理会保留正在收听的一期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
                if (downloads.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "离线缓存 ${downloads.size} 期 · 听完自动删除",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.Ink
                    )
                    downloads.forEach { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.episode.title.ifBlank { entry.episode.date },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    entry.episode.date,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.Muted
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when (entry.state) {
                                    DownloadManager.State.DONE -> formatSize(entry.bytes)
                                    DownloadManager.State.DOWNLOADING ->
                                        "${(entry.progress * 100).roundToInt()}%"
                                    DownloadManager.State.QUEUED -> "排队中"
                                    DownloadManager.State.FAILED -> "失败"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.state == DownloadManager.State.FAILED) {
                                    AppColors.RedDeep
                                } else {
                                    AppColors.Muted
                                }
                            )
                            // 失败不会自己重来：条目还占着列表，
                            // 重新长按「缓存」又会被当成已存在而跳过，只能在这里重试
                            if (entry.state == DownloadManager.State.FAILED) {
                                TextButton(onClick = { onRetry(entry.episode.id) }) {
                                    Text("重试", color = AppColors.RedDeep)
                                }
                            }
                            TextButton(onClick = { onRemove(entry.episode.id) }) {
                                Text("删除", color = AppColors.Muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 清理是本页唯一的破坏性操作，不能放在对话框右下角那个
                    // 「主按钮」位置：拨完开关顺手点右下角就会把缓存清空
                    TextButton(
                        onClick = {
                            clearing = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    MediaCache.clear(context)
                                }
                                clearing = false
                                onCleared()
                            }
                        },
                        enabled = !clearing && (cacheBytes ?: 0L) > 0
                    ) {
                        Text(
                            if (clearing) "清理中…" else "清理收听缓存",
                            color = if (clearing || (cacheBytes ?: 0L) <= 0L) AppColors.Faint else AppColors.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (clearing) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

/** 设置页的开关行：左边标题 + 说明，右边开关 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    else -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
}

internal fun formatMillis(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
