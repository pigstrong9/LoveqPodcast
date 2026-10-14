@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package cn.loveq.podcast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.ui.theme.AppColors
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(16.dp)
private val ContinueShape = RoundedCornerShape(18.dp)
private val YearShape = RoundedCornerShape(9.dp)

/** 中间栏目：分类标题 + 继续收听 + 年份分组 + 节目列表 */
@Composable
fun EpisodeListPane(
    state: ListUiState,
    progressMap: Map<Long, Progress>,
    playingMediaId: String?,
    isPlaying: Boolean,
    lastProgress: Progress?,
    showContinue: Boolean,
    archiveCount: Int,
    onPlay: (Int) -> Unit,
    onResumeLast: () -> Unit,
    loadNext: () -> Unit,
    loadPrevious: () -> Unit,
    refresh: () -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 底部要避让的浮层高度（迷你胶囊浮在列表上时传入），默认不避让 */
    bottomInset: Dp = 0.dp,
    /** 长按进入的批量选择模式 */
    selecting: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    offlineIds: Set<Long> = emptySet(),
    pendingIds: Set<Long> = emptySet(),
    onEnterSelect: (Episode) -> Unit = {},
    onToggleSelect: (Long) -> Unit = {},
    onExitSelect: () -> Unit = {},
    onOfflineSelected: () -> Unit = {}
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
    LaunchedEffect(state.scrollResetToken) {
        if (state.scrollResetToken > 0) listState.scrollToItem(0)
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selectedIds.isEmpty()) "选择节目" else "已选 ${selectedIds.size} 期",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onOfflineSelected,
                    enabled = selectedIds.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selectedIds.isEmpty()) AppColors.Muted else AppColors.Red
                    )
                ) {
                    Text("离线缓存", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onExitSelect) { Text("取消", color = AppColors.Muted) }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    state.category.name,
                    style = if (compact) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.displaySmall,
                    color = AppColors.Ink,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    buildString {
                        if (archiveCount > 0) append("$archiveCount 期")
                        else append("已加载 ${state.episodes.size} 期")
                        append(" · 新到旧")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.Muted,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }

        // 断网兜底：列表来自本地索引而不是官网，必须说清楚，否则用户以为只有这么多期
        if (!selecting && state.offline) {
            Text(
                "离线中 · 显示本地索引，联网后下拉刷新",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.RedDeep,
                modifier = Modifier.padding(start = 26.dp, bottom = 6.dp)
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = refresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                state.initialLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppColors.Red)
                    }
                }

                state.error != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(state.error ?: "", color = AppColors.RedDeep)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = retry) { Text("重试") }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 14.dp + bottomInset
                        )
                    ) {
                        if (lastProgress != null && !lastProgress.done) {
                            item(key = "continue") {
                                ContinueCardSlot(
                                    visible = showContinue,
                                    progress = lastProgress,
                                    isPlaying = isPlaying,
                                    compact = compact,
                                    onResume = onResumeLast
                                )
                            }
                        }
                        if (state.loadingUp) {
                            item(key = "loading_up") {
                                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                                    CircularProgressIndicator(
                                        Modifier.align(Alignment.Center).size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = AppColors.Red
                                    )
                                }
                            }
                        }
                        itemsIndexed(state.episodes, key = { _, episode -> episode.id }) { index, episode ->
                            val prevYear = state.episodes.getOrNull(index - 1)?.year
                            if (episode.year != 0 && episode.year != prevYear) {
                                YearPill(episode.year)
                            }
                            val current = playingMediaId == episode.id.toString()
                            EpisodeRow(
                                episode = episode,
                                progress = progressMap[episode.id],
                                current = current,
                                isPlaying = isPlaying,
                                compact = compact,
                                selecting = selecting,
                                selected = selectedIds.contains(episode.id),
                                offline = offlineIds.contains(episode.id),
                                pending = pendingIds.contains(episode.id),
                                onClick = {
                                    if (selecting) onToggleSelect(episode.id) else onPlay(index)
                                },
                                onLongClick = { onEnterSelect(episode) }
                            )
                        }
                        item(key = "footer") {
                            ListFooter(
                                loading = state.loadingDown || state.jumping,
                                endReached = state.endReached,
                                error = state.loadMoreError,
                                onRetry = loadNext
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 独立出来才能用无作用域接收者的 AnimatedVisibility（LazyColumn item 内没有 ColumnScope） */
@Composable
private fun ContinueCardSlot(
    visible: Boolean,
    progress: Progress,
    isPlaying: Boolean,
    compact: Boolean,
    onResume: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240)) + expandVertically(tween(320)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(320))
    ) {
        ContinueCard(
            progress = progress,
            isPlaying = isPlaying,
            compact = compact,
            onResume = onResume
        )
    }
}

@Composable
private fun ContinueCard(
    progress: Progress,
    isPlaying: Boolean,
    compact: Boolean,
    onResume: () -> Unit
) {
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else 0f

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(ContinueShape)
            .background(AppColors.Red)
            .clickable(onClick = onResume)
            .padding(
                horizontal = if (compact) 15.dp else 18.dp,
                vertical = if (compact) 13.dp else 16.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EqBars(
            barCount = 5,
            height = if (compact) 22.dp else 26.dp,
            barWidth = 4.dp,
            color = Color.White,
            animate = isPlaying
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "继续收听",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.95f),
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                progress.title.ifBlank { progress.date },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!compact) {
                Spacer(Modifier.height(5.dp))
                Text(
                    buildString {
                        append(progress.date)
                        append(" · 已听 ").append(formatMillis(progress.positionMs))
                        if (progress.durationMs > 0) {
                            append(" / ").append(formatMillis(progress.durationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(
                fraction = fraction,
                track = Color.White.copy(alpha = 0.25f),
                fill = Color.White
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .size(if (compact) 44.dp else 52.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "继续播放",
                tint = AppColors.Red,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun YearPill(year: Int) {
    Text(
        "$year",
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            color = AppColors.RedDeep
        ),
        modifier = Modifier
            .padding(top = 8.dp, bottom = 6.dp)
            .clip(YearShape)
            .background(AppColors.RedMist)
            .padding(horizontal = 12.dp, vertical = 3.dp)
    )
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: Progress?,
    current: Boolean,
    isPlaying: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    offline: Boolean = false,
    pending: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val done = progress?.done == true && !current
    val fraction = progress?.takeIf { it.durationMs > 0 }
        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) } ?: 0f
    val showRail = !done && fraction > 0.02f && fraction < 0.98f

    val container by animateColorAsState(
        targetValue = if (current || selected) AppColors.RedWash else AppColors.Card,
        animationSpec = tween(240),
        label = "rowContainer"
    )
    val edge by animateColorAsState(
        targetValue = if (current || selected) AppColors.RedEdge else Color.Transparent,
        animationSpec = tween(240),
        label = "rowEdge"
    )
    val titleColor by animateColorAsState(
        targetValue = if (done) AppColors.Muted else AppColors.Ink,
        animationSpec = tween(240),
        label = "rowTitle"
    )
    val railFraction by animateFloatAsState(
        targetValue = if (showRail) fraction else 0f,
        animationSpec = tween(420),
        label = "rowRail"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(CardShape)
            .background(container)
            .border(2.dp, edge, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                horizontal = if (compact) 14.dp else 16.dp,
                vertical = if (compact) 11.dp else 13.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选模式：行首挂一个勾选框，点击整行即切换选中，不再触发播放
        if (selecting) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) AppColors.Red else AppColors.Canvas)
                    .border(
                        width = 2.dp,
                        color = if (selected) AppColors.Red else AppColors.Rail,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已选择",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                episode.title.ifBlank { episode.date },
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (episode.title.isNotBlank() && episode.title != episode.date) {
                Spacer(Modifier.height(3.dp))
                Text(
                    episode.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.Muted
                )
            }
            // 播放中的行：进度条挪到文字下方通栏显示（右侧让位给跳动条），
            // 否则迷你胶囊收起后在列表里找不到本期听到哪
            if (current && showRail) {
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AppColors.Rail)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(railFraction)
                            .fillMaxHeight()
                            .background(AppColors.Red)
                    )
                }
            }
        }
        when {
            current -> {
                Spacer(Modifier.width(14.dp))
                EqBars(
                    barCount = 3,
                    height = 18.dp,
                    barWidth = 3.dp,
                    color = AppColors.Red,
                    animate = isPlaying
                )
            }

            done -> {
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(AppColors.RedMist),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已听完",
                        tint = AppColors.RedDeep,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            offline || pending -> {
                // 已离线缓存 / 正在下载：车机上「哪几期没网也能听」必须一眼可见
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (offline) AppColors.RedMist else AppColors.Canvas),
                    contentAlignment = Alignment.Center
                ) {
                    if (!offline) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.Red
                        )
                    } else {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = "已离线缓存",
                            tint = AppColors.RedDeep,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            showRail -> {
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .width(if (compact) 90.dp else 130.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AppColors.Rail)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(railFraction)
                            .fillMaxHeight()
                            .background(AppColors.Red)
                    )
                }
            }
        }
    }
}

@Composable
private fun ListFooter(loading: Boolean, endReached: Boolean, error: Boolean, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = AppColors.Red)
            endReached -> Text(
                "已经到底啦 · 2003.06.15",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Muted
            )

            // 仅真正失败才提示重试；空闲态（尚未触发下一页、跳转后等）保持空白
            error -> TextButton(onClick = onRetry) { Text("加载失败，点此重试") }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, track: Color, fill: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(fill)
        )
    }
}

private val BAR_PATTERN = listOf(0.35f, 1f, 0.6f, 0.85f, 0.45f)

/** 播放指示条：暂停时静止，避免车机无谓重绘 */
@Composable
internal fun EqBars(
    barCount: Int,
    height: Dp,
    barWidth: Dp,
    color: Color,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val scales = List(barCount) { i ->
        if (animate) {
            transition.animateFloat(
                initialValue = BAR_PATTERN[i % BAR_PATTERN.size],
                targetValue = 0.3f + BAR_PATTERN[i % BAR_PATTERN.size] * 0.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 620,
                        delayMillis = (i * 110) % 400,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            ).value
        } else {
            BAR_PATTERN[i % BAR_PATTERN.size]
        }
    }
    Row(
        modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        scales.forEach { scale ->
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight((scale * 100).roundToInt() / 100f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
