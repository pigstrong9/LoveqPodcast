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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.data.canResume
import cn.loveq.podcast.ui.theme.AppColors
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(16.dp)

/** 左右滑动切分类：拖到面板宽度的这个比例即判定切换 */
private const val SWIPE_FRACTION = 0.22f

/** 甩动速度（dp/秒）达到这个值时，距离不到阈值也算切换 */
private val SWIPE_FLING_VELOCITY_DP = 900.dp

/** 双击标题回顶：超过这个条目数就不做逐格动画，直接瞬移 */
private const val SNAP_TO_TOP_AFTER = 30

/** 拖动时列表跟随手指的位移比例：跟得太死会让人以为整页要翻走 */
private const val RUBBER_BAND = 0.12f

/** 判定甩动所用的速度取样窗口：末事件太慢会漏判，窗口太长会把慢拖算成甩动 */
private const val SWIPE_VELOCITY_WINDOW_NS = 60_000_000L

/**
 * 净位移 → 方向 → 换分类。松手（end）和被打断（cancel）两条路都走这里，
 * 否则「划过去了却被系统打断」会变成完全没反应。
 * 边界分类时 switchCategoryBy 自己夹住并返回 false，所以这里不会重复触发。
 */
private fun commitSwipe(
    netDragPx: Float,
    onSwipeCategory: (Int) -> Boolean,
    haptics: HapticFeedback
) {
    if (netDragPx == 0f) return
    val dir = if (netDragPx < 0f) 1 else -1
    if (onSwipeCategory(dir)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
}

/**
 * 批量选择模式的一揽子参数：三栏与窄屏两处列表共用，避免八个参数抄两遍。
 * offlineIds = 已离线缓存的期，pendingIds = 排队/下载中的期，用于行尾徽标。
 */
data class SelectionArgs(
    val selecting: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val offlineIds: Set<Long> = emptySet(),
    val pendingIds: Set<Long> = emptySet(),
    val onEnterSelect: (Episode) -> Unit = {},
    val onToggleSelect: (Episode) -> Unit = {},
    val onExitSelect: () -> Unit = {},
    val onOfflineSelected: () -> Unit = {}
)
private val ContinueShape = RoundedCornerShape(18.dp)
private val YearShape = RoundedCornerShape(9.dp)

/**
 * 中间栏目：分类标题 + 继续收听 + 年份分组 + 节目列表
 *
 * [onSwipeCategory] 非空时启用「左右滑动列表换分类」（只在没有侧栏的窄屏启用），
 * [swipeHint] 用来取相邻分类的名字做滑动提示，边界处返回 null。
 */
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
    onPickDate: (Int) -> Unit,
    onClearDateFilter: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 状态栏避让量：背景画到屏幕最上沿，只把内容挪下来，这样顶部不断层 */
    topInset: WindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    /** 底部要避让的浮层高度（迷你胶囊浮在列表上时传入），默认不避让 */
    bottomInset: Dp = 0.dp,
    onSwipeCategory: ((Int) -> Boolean)? = null,
    swipeHint: (Int) -> String? = { null },
    /** 长按进入的批量选择模式 */
    selection: SelectionArgs = SelectionArgs()
) {
    val (
        selecting, selectedIds, offlineIds, pendingIds,
        onEnterSelect, onToggleSelect, onExitSelect, onOfflineSelected
    ) = selection
    val listState = rememberLazyListState()
    val episodeCount = state.episodes.size
    val startPage = state.startPage
    val shouldLoadNext by remember(episodeCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            episodeCount > 0 && last >= episodeCount - 6
        }
    }
    val shouldLoadPrev by remember(startPage, state.filtering) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
            // 筛选态的「上一段」是更新的月份，和官网页游标无关
            (state.filtering || startPage > 1) && first <= 1
        }
    }
    // 刚跳到某个月时列表正停在顶部：这时不能立刻把相邻月份拼进来，
    // 否则看起来像筛选没生效。
    var canLoadNewer by remember(state.filtering, state.scrollResetToken) {
        mutableStateOf(false)
    }
    // 每个滑动手势最多续接一段：一次下拉连拉好几个月会让人觉得停不下来。
    // 装载完成后置回 false，等下一次手势再开闸。
    var canLoadOlder by remember(state.filtering, state.scrollResetToken) {
        mutableStateOf(false)
    }
    if (state.filtering) {
        LaunchedEffect(state.filtering, state.scrollResetToken) {
            // 跳转定位是程序化滚动，别把它当成用户手势；等两帧落定
            withFrameNanos { }
            withFrameNanos { }
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) return@collect
                canLoadOlder = true
                // 要先离开顶部，才允许回顶时接更新的月份
                if (listState.firstVisibleItemIndex > 0) canLoadNewer = true
            }
        }
        LaunchedEffect(shouldLoadNext, canLoadOlder) {
            if (shouldLoadNext && canLoadOlder) {
                canLoadOlder = false
                loadNext()
            }
        }
        LaunchedEffect(shouldLoadPrev, canLoadNewer) {
            if (shouldLoadPrev && canLoadNewer) {
                canLoadNewer = false
                loadPrevious()
            }
        }
    } else {
        // 未筛选时仍是原来的整页翻页：一页 26 期，不会出现按月连续拉取的问题
        LaunchedEffect(shouldLoadNext) {
            if (shouldLoadNext) loadNext()
        }
        LaunchedEffect(shouldLoadPrev) {
            if (shouldLoadPrev) loadPrevious()
        }
    }
    LaunchedEffect(state.scrollResetToken, state.scrollToId) {
        if (state.scrollResetToken <= 0) return@LaunchedEffect
        val target = state.scrollToId
        val index = target?.let { id -> state.episodes.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            // 节目前面还有「继续收听」「向上加载」两个可选 item，滚动下标要加上它们
            val leading = (if (lastProgress != null && lastProgress.canResume) 1 else 0) +
                (if (state.loadingUp) 1 else 0)
            listState.scrollToItem(index + leading)
        } else {
            listState.scrollToItem(0)
        }
    }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 滑动切分类：位移驱动橡皮筋 + 相邻分类提示
    var dragPx by remember { mutableFloatStateOf(0f) }
    var thresholdPx by remember { mutableFloatStateOf(1f) }
    var paneWidthPx by remember { mutableFloatStateOf(1f) }
    // 批量选择模式在长按里开启，而检测器是 composition 时才挂/摘的：
    // 长按之后手指还会横着漂一点，那时旧的检测器还在收事件，会把分类切走，
    // 也会让 dragPx 卡在一个非 0 值上（列表永久歪着、提示气泡退不掉）。
    // 所以把 selecting 放进 key：进入选择模式立刻掐掉在途手势。
    val selectingNow = rememberUpdatedState(selecting)
    LaunchedEffect(state.category.id, selecting) {
        // 手势归零交给这里统一负责，不指望 onDragEnd/onDragCancel 一定跑到
        dragPx = 0f
    }
    val swipeModifier = if (onSwipeCategory != null && !selecting) {
        Modifier
            // fillMaxWidth 必须排在 pointerInput 之前：PointerInputScope.size 是
            // 「应用本 modifier 时节点的约束宽」。本面板在窄屏里就是整列宽，
            // 所以先限宽能拿到真实面板宽；写在后面则 size 会停在父级最大约束宽。
            // （weight(1f) 只能留在调用点：它是 ColumnScope 的成员扩展，
            //   在这个作用域里根本写不出来。）
            .fillMaxWidth()
            .graphicsLayer { translationX = dragPx * RUBBER_BAND }
            .pointerInput(state.category.id, selecting) {
                val flingPx = with(density) { SWIPE_FLING_VELOCITY_DP.toPx() }
                // detectHorizontalDragGestures 的 onDragEnd 不带速度，只能自己记。
                // 但不能拿「最后一个事件」当速度：抬手前手指本来就会减速，
                // 末事件算出来只有 60~240 px/s，够不到 900 dp 的门槛；
                // 而且 60Hz/120Hz 下同一记甩动对应的单事件位移差一倍。
                // 改成在最近 SWIPE_VELOCITY_WINDOW_NS 这一段窗口上取平均速度：
                // windowPx 只在窗口滚满时才挪，否则又会退化成末事件速度。
                // 它必须和 startPx 分开：窗口一滚满就把起点覆盖成「位移量」的话，
                // 之后的 dragPx = 当前坐标 − 位移量，符号都会算反 —— 左划变成切到上一个分类
                var startPx = 0f
                var windowPx = 0f
                var windowNanos = 0L
                var velocity = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        paneWidthPx = size.width.toFloat().coerceAtLeast(1f)
                        thresholdPx = (size.width * SWIPE_FRACTION)
                            .coerceAtLeast(viewConfiguration.touchSlop * 4f)
                        startPx = start.x
                        windowNanos = 0L
                        velocity = 0f
                    },
                    onHorizontalDrag = { change, _ ->
                        // 累计位移会在「划出去又划回来」时越攒越大，
                        // 抬手时明明回到原点却仍然判定为切换。用净位移。
                        dragPx = change.position.x - startPx
                        val now = System.nanoTime()
                        if (windowNanos == 0L) {
                            windowNanos = now
                            windowPx = dragPx
                        } else if (now - windowNanos >= SWIPE_VELOCITY_WINDOW_NS) {
                            val dt = now - windowNanos
                            velocity = (dragPx - windowPx) * 1_000_000_000f / dt
                            windowPx = dragPx
                            windowNanos = now
                        }
                    },
                    // 松手前被系统打断（返回手势、车机抢焦点）或第二根手指落下，
                    // 都会走 cancel 而不是 end。以前这里只把位移清零，
                    // 用户明明已经划过去一大截却毫无反应，只能重划一次。
                    onDragCancel = {
                        if (!selectingNow.value && abs(dragPx) >= thresholdPx) {
                            commitSwipe(dragPx, onSwipeCategory, haptics)
                        }
                        dragPx = 0f
                    },
                    onDragEnd = {
                        val far = abs(dragPx) >= thresholdPx
                        val flung = velocity * dragPx > 0f && abs(velocity) >= flingPx
                        if (!selectingNow.value && (far || flung)) {
                            commitSwipe(dragPx, onSwipeCategory, haptics)
                        }
                        dragPx = 0f
                    }
                )
            }
    } else {
        Modifier.fillMaxWidth()
    }
    val swipeHintLabel = if (dragPx <= 0f) swipeHint(1) else swipeHint(-1)
    val swipeProgress = ((abs(dragPx) / paneWidthPx) / SWIPE_FRACTION).coerceIn(0f, 1f)

    Column(
        modifier
            .fillMaxSize()
            // 底色铺满（含状态栏底下），内容再避让 —— 与侧栏/顶栏同一套做法
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(topInset)
    ) {
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
                // 双击标题回到最顶部（最新一期）：翻了几百期之后单手划回去很费劲，
                // 这里空出一块永远在原地的大热区。单击不做任何事，避免和双击抢判定
                Text(
                    state.category.name,
                    style = if (compact) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.displaySmall,
                    color = AppColors.Ink,
                    modifier = Modifier
                        .alignByBaseline()
                        .combinedClickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onDoubleClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    // animateScrollToItem 会一格一格滚过去：
                                    // 翻了几百期之后「回顶部」要磨蹭好几秒，超过阈值直接瞬移
                                    if (listState.firstVisibleItemIndex > SNAP_TO_TOP_AFTER) {
                                        listState.scrollToItem(0)
                                    } else {
                                        listState.animateScrollToItem(0)
                                    }
                                }
                            },
                            onClick = {}
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    buildString {
                        // 筛选态下官网总期数已经没有意义，说清楚现在看的是哪一段。
                        // 上下滑过相邻月份后，标题跟着显示实际加载到的范围
                        if (state.filtering) {
                            val monthOnly = state.filterMonth != 0
                            val anchorKey =
                                if (monthOnly) state.filterYear * 100 + state.filterMonth
                                else state.filterYear
                            fun keyOf(e: Episode) = if (monthOnly) e.yearMonth else e.year
                            fun labelOf(e: Episode) =
                                if (monthOnly) "${e.year} 年 ${e.month} 月" else "${e.year} 年"
                            val newest = state.episodes.firstOrNull()
                            val oldest = state.episodes.lastOrNull()
                            if (newest != null && oldest != null &&
                                (keyOf(newest) != anchorKey || keyOf(oldest) != anchorKey)
                            ) {
                                append(labelOf(oldest)).append(" – ").append(labelOf(newest))
                            } else {
                                append(
                                    if (monthOnly) "${state.filterYear} 年 ${state.filterMonth} 月"
                                    else "${state.filterYear} 年"
                                )
                            }
                            append(" · ${state.episodes.size} 期")
                        } else {
                            if (archiveCount > 0) append("$archiveCount 期")
                            else append("已加载 ${state.episodes.size} 期")
                            append(" · 新到旧")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AppColors.Muted,
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline()
                )
                if (state.filtering) {
                    TextButton(onClick = onClearDateFilter) {
                        Text("看全部", color = AppColors.Red, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 定位历史年份要发几次网络请求，进度条必须放在用户眼前这一屏：
        // 原先只有列表底部 footer 有转圈，而那时用户正停在列表顶部，什么都看不见
        if (state.jumping) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = AppColors.Red,
                trackColor = AppColors.RedMist
            )
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

        RefreshableBox(
            // 筛选态的顶部手势要留给「加载更新的月份」：再挂下拉刷新会抢同一个手势，
            // 而且刷新本来就会退出筛选，和当前浏览上下文冲突
            enabled = !state.filtering,
            isRefreshing = state.refreshing,
            onRefresh = refresh,
            // 纵向 weight(1f) 只能在 Column 作用域里写，留在调用点；
            // 横向 fillMaxWidth 必须先进 swipeModifier（见上面的说明）
            modifier = swipeModifier.weight(1f)
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
                        if (lastProgress != null && lastProgress.canResume) {
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
                                YearPill(episode.year, onPickDate)
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
                                    if (selecting) onToggleSelect(episode) else onPlay(index)
                                },
                                onLongClick = { onEnterSelect(episode) }
                            )
                        }
                        item(key = "footer") {
                            ListFooter(
                                loading = state.loadingDown || state.jumping,
                                endReached = state.endReached,
                                error = state.loadMoreError,
                                endText = "已经到底啦 · 2003.06.15",
                                onRetry = loadNext
                            )
                        }
                    }
                }
            }

            // 滑动切分类的落点提示：手指拖到哪一侧，那侧浮出下一个分类名
            if (swipeHintLabel != null && abs(dragPx) > 24f) {
                Text(
                    swipeHintLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.RedDeep,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(
                            if (dragPx < 0f) Alignment.CenterEnd else Alignment.CenterStart
                        )
                        .padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.RedMist.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .alpha(0.35f + 0.65f * swipeProgress)
                )
            }
        }
    }
}

/** 筛选态关掉下拉刷新（手势让给相邻月份加载），其余情况保持原样 */
@Composable
private fun RefreshableBox(
    enabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    if (enabled) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier
        ) { content() }
    } else {
        Box(modifier) { content() }
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

/**
 * 年份分组标记，同时是「跳到这一天」的入口：
 * 顶栏那个日历按钮去掉了，年份数字本身就在列表里，点它比绕道工具栏近得多。
 */
@Composable
private fun YearPill(year: Int, onPickDate: (Int) -> Unit) {
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
            .clickable(onClickLabel = "跳转到 $year 年") { onPickDate(year) }
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
        // 离线徽标与进度条共存：正在听的往往正是缓存下来的那期，两者不该互相挤掉
        if (offline || pending) {
            Spacer(Modifier.width(12.dp))
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
private fun ListFooter(
    loading: Boolean,
    endReached: Boolean,
    error: Boolean,
    endText: String,
    onRetry: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = AppColors.Red)
            endReached -> Text(
                endText,
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
