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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

    var yearDialogOpen by rememberSaveable { mutableStateOf(false) }
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
                // 状态栏是主动隐藏的，但 DiLink 仍会上报非零的 statusBars inset，
                // 跟着 pad 就会在顶部留一条浅色空白，所以只避让导航栏/手势条
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
                            onYear = { yearDialogOpen = true },
                            onCache = { settingsOpen = true },
                            cacheLabel = cacheBytes?.let { "缓存 ${formatSize(it)} · 上限 2 GB" },
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
                        onYear = { yearDialogOpen = true },
                        onCache = { settingsOpen = true }
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
                        modifier = Modifier.weight(1f),
                        compact = true,
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
                }
            )
        }
    }

    if (yearDialogOpen) {
        YearPickerDialog(
            currentYear = Calendar.getInstance().get(Calendar.YEAR),
            onPick = { year ->
                yearDialogOpen = false
                viewModel.jumpToYear(year)
            },
            onDismiss = { yearDialogOpen = false }
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
        CacheDialog(
            cacheBytes = cacheBytes,
            downloads = downloads,
            onRemove = viewModel::removeOffline,
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
        modifier = Modifier.widthIn(max = 460.dp),
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

@Composable
private fun YearPickerDialog(
    currentYear: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val years = (currentYear downTo 2003).toList()
    DialogHost(
        onDismiss = onDismiss,
        title = "跳转到年份",
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                itemsIndexed(years) { _, year ->
                    Text(
                        "$year 年",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(year) }
                            .padding(horizontal = 14.dp, vertical = 13.dp)
                    )
                }
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

@Composable
private fun CacheDialog(
    cacheBytes: Long?,
    downloads: List<DownloadManager.Entry>,
    onRemove: (Long) -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }

    DialogHost(
        onDismiss = onDismiss,
        title = "缓存管理",
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                            TextButton(onClick = { onRemove(entry.episode.id) }) {
                                Text("删除", color = AppColors.Muted)
                            }
                        }
                    }
                }
                if (clearing) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmText = if (clearing) "清理中…" else "清理缓存",
        confirmEnabled = !clearing && (cacheBytes ?: 0L) > 0,
        onConfirm = {
            clearing = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    MediaCache.clear(context)
                }
                clearing = false
                onCleared()
            }
        },
        dismissText = "关闭"
    )
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
