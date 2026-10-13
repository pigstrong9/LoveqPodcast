@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package cn.loveq.podcast.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.loveq.podcast.R
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.playback.MediaCache
import cn.loveq.podcast.playback.PlaybackEvents
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WideThreshold = 600.dp

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
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()
    val metaTitle by viewModel.nowTitle.collectAsStateWithLifecycle()
    val metaDate by viewModel.nowDate.collectAsStateWithLifecycle()

    val lastProgress = lastId?.let { progressMap[it] }

    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = remember(searchQuery, index) {
        val terms = searchQuery.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) emptyList()
        else index.filter { e ->
            terms.all { t -> e.title.contains(t, ignoreCase = true) || e.date.contains(t) }
        }
    }

    var yearDialogOpen by remember { mutableStateOf(false) }
    var playerSheetOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    val onPlay: (Int) -> Unit = { position ->
        onRequestNotificationPermission()
        viewModel.playEpisode(position)
    }
    val onResumeLast: () -> Unit = {
        onRequestNotificationPermission()
        viewModel.resumeLast()
    }

    ErrorToasts()

    BoxWithConstraints {
        val wide = maxWidth >= WideThreshold
        val sidePaneWidth = (maxWidth * 0.38f).coerceIn(300.dp, 420.dp)

        val currentEp = currentEpisode(state.episodes, playingMediaId, progressMap)
        val nowTitle = metaTitle ?: currentEp?.title?.ifBlank { currentEp.date } ?: ""
        val nowDate = metaDate ?: currentEp?.date ?: ""

        if (wide) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Column {
                        AppTopBar(
                            onSearch = { searchOpen = true },
                            onYear = { yearDialogOpen = true },
                            onRefresh = viewModel::refresh,
                            onSettings = { settingsOpen = true }
                        )
                        CategoryTabs(
                            selected = state.category,
                            onSelect = viewModel::switchCategory
                        )
                    }
                }
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    EpisodeListPane(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        state = state,
                        progressMap = progressMap,
                        playingMediaId = playingMediaId,
                        isPlaying = isPlaying,
                        lastProgress = lastProgress,
                        onPlay = onPlay,
                        onResumeLast = onResumeLast,
                        loadNext = viewModel::loadNextPage,
                        loadPrevious = viewModel::loadPreviousPage,
                        refresh = viewModel::refresh,
                        retry = viewModel::retry
                    )
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                    PlayerSidePane(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(sidePaneWidth),
                        hasEpisode = playingMediaId != null,
                        title = nowTitle,
                        date = nowDate,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        speed = speed,
                        lastProgress = lastProgress,
                        onToggle = viewModel::togglePlayPause,
                        onSeekTo = viewModel::seekTo,
                        onSeekBack = viewModel::seekBack,
                        onSeekForward = viewModel::seekForward,
                        onSpeed = viewModel::setSpeed,
                        onResumeLast = onResumeLast
                    )
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Column {
                        AppTopBar(
                            onSearch = { searchOpen = true },
                            onYear = { yearDialogOpen = true },
                            onRefresh = viewModel::refresh,
                            onSettings = { settingsOpen = true }
                        )
                        CategoryTabs(
                            selected = state.category,
                            onSelect = viewModel::switchCategory
                        )
                    }
                },
                bottomBar = {
                    AnimatedVisibility(visible = playingMediaId != null) {
                        MiniPlayer(
                            title = nowTitle,
                            isPlaying = isPlaying,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            speed = speed,
                            onToggle = viewModel::togglePlayPause,
                            onOpen = { playerSheetOpen = true }
                        )
                    }
                }
            ) { padding ->
                EpisodeListPane(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    state = state,
                    progressMap = progressMap,
                    playingMediaId = playingMediaId,
                    isPlaying = isPlaying,
                    lastProgress = lastProgress,
                    onPlay = onPlay,
                    onResumeLast = onResumeLast,
                    loadNext = viewModel::loadNextPage,
                    loadPrevious = viewModel::loadPreviousPage,
                    refresh = viewModel::refresh,
                    retry = viewModel::retry
                )
            }

            if (playerSheetOpen && playingMediaId != null) {
                PlayerSheet(
                    title = nowTitle,
                    date = nowDate,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    onToggle = viewModel::togglePlayPause,
                    onSeekTo = viewModel::seekTo,
                    onSeekBack = viewModel::seekBack,
                    onSeekForward = viewModel::seekForward,
                    onSpeed = viewModel::setSpeed,
                    onClose = { playerSheetOpen = false }
                )
            }
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
        CacheDialog(onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun CategoryTabs(selected: Category, onSelect: (Category) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = Category.ALL.indexOfFirst { it.id == selected.id }.coerceAtLeast(0),
        edgePadding = 16.dp,
        divider = {},
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Category.ALL.forEach { category ->
            Tab(
                selected = category.id == selected.id,
                onClick = { onSelect(category) },
                text = { Text(category.name) }
            )
        }
    }
}

@Composable
private fun ErrorToasts() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PlaybackEvents.errors.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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
private fun AppTopBar(
    onSearch: () -> Unit,
    onYear: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.loveq_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("一些事一些情", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "FM97.4 珠江经济台 · 2003 至今",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onYear) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "按年份跳转")
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "缓存管理")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun EpisodeListPane(
    modifier: Modifier = Modifier,
    state: ListUiState,
    progressMap: Map<Long, Progress>,
    playingMediaId: String?,
    isPlaying: Boolean,
    lastProgress: Progress?,
    onPlay: (Int) -> Unit,
    onResumeLast: () -> Unit,
    loadNext: () -> Unit,
    loadPrevious: () -> Unit,
    refresh: () -> Unit,
    retry: () -> Unit
) {
    val listState = rememberLazyListState()
    val episodeCount = state.episodes.size
    val startPage = state.startPage
    val shouldLoadNext by remember(episodeCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            episodeCount > 0 && last >= episodeCount - 6
        }
    }
    val shouldLoadPrev by remember(startPage) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            startPage > 1 && first <= 1
        }
    }
    LaunchedEffect(shouldLoadNext) {
        if (shouldLoadNext) loadNext()
    }
    LaunchedEffect(shouldLoadPrev) {
        if (shouldLoadPrev) loadPrevious()
    }
    val firstId = state.episodes.firstOrNull()?.id
    LaunchedEffect(firstId) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = refresh,
        modifier = modifier
    ) {
        when {
            state.initialLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = retry) { Text("重试") }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (lastProgress != null && !lastProgress.done) {
                        item(key = "continue") {
                            ContinueCard(progress = lastProgress, onResume = onResumeLast)
                        }
                    }
                    if (state.loadingUp) {
                        item(key = "loading_up") {
                            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                                CircularProgressIndicator(
                                    Modifier.align(Alignment.Center).size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                    itemsIndexed(state.episodes, key = { _, episode -> episode.id }) { index, episode ->
                        val prevYear = state.episodes.getOrNull(index - 1)?.year
                        if (episode.year != 0 && episode.year != prevYear) {
                            YearHeader(episode.year)
                        }
                        val playing = playingMediaId == episode.id.toString()
                        EpisodeRow(
                            episode = episode,
                            progress = progressMap[episode.id],
                            isPlaying = playing && isPlaying,
                            isCurrent = playing,
                            onClick = { onPlay(index) }
                        )
                    }
                    item(key = "footer") {
                        Footer(
                            loading = state.loadingDown || state.jumping,
                            endReached = state.endReached,
                            onRetry = loadNext
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YearHeader(year: Int) {
    Text(
        "$year",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 3.dp)
    )
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: Progress?,
    isPlaying: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val done = progress?.done == true
    val fraction = progress?.takeIf { it.durationMs > 0 }
        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) } ?: 0f

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title.ifBlank { episode.date },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (done && !isCurrent) 0.55f else 1f)
                )
                if (episode.title.isNotBlank() && episode.title != episode.date) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        episode.date,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                isCurrent -> Icon(
                    if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                done -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "已听完",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(0.6f)
                )
            }
        }
        if (fraction > 0.02f && fraction < 0.98f) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(progress: Progress, onResume: () -> Unit) {
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else 0f

    Card(
        onClick = onResume,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "继续收听",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                progress.title.ifBlank { progress.date },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${progress.date} · 已听 ${formatMillis(progress.positionMs)}" +
                    (if (progress.durationMs > 0) " / ${formatMillis(progress.durationMs)}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun Footer(loading: Boolean, endReached: Boolean, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            endReached -> Text(
                "已经到底啦 · 2003.06.15",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> TextButton(onClick = onRetry) { Text("加载失败，点此重试") }
        }
    }
}

@Composable
private fun YearPickerDialog(
    currentYear: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val years = (currentYear downTo 2003).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到年份") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                itemsIndexed(years) { _, year ->
                    Text(
                        "$year 年",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(year) }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CacheDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sizeBytes = MediaCache.sizeBytes(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("缓存管理") },
        text = {
            Column {
                Text(
                    "节目缓存：" + (sizeBytes?.let { formatSize(it) } ?: "统计中…"),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "收听过的片段自动存入本地，重听、续播、回退不再消耗流量。" +
                        "上限 2 GB，满后自动淘汰最久未听的节目。清理会保留正在收听的一期。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clearing = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            MediaCache.clear(context)
                            sizeBytes = MediaCache.sizeBytes(context)
                        }
                        clearing = false
                    }
                },
                enabled = !clearing && (sizeBytes ?: 0L) > 0
            ) {
                Text(if (clearing) "清理中…" else "清理缓存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
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
