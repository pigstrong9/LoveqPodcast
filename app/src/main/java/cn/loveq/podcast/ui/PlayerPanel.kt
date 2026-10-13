@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.loveq.podcast.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.loveq.podcast.R
import cn.loveq.podcast.playback.VolumeBoost
import cn.loveq.podcast.ui.theme.AppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

private val PanelShape = RoundedCornerShape(24.dp)

/** 控制键与底部设置栏之间的固定小间距，取代原来的空白弹簧 */
private val BOTTOM_GAP = 12.dp

/**
 * 封面边长占面板可用高度的比例。
 * 面板高度里，标题+日期+进度+控制键+底部设置栏合计约占一半（真机 384×800dp 面板实测），
 * 所以封面只取 45%，既不会压住标题，也不会把底部设置栏挤出屏幕。
 */
private const val COVER_HEIGHT_FRACTION = 0.45f

/** 封面是正方形：两个方向都取小值，面板尺寸未知（首帧）时先用一个安全值 */
private fun coverSize(boxSize: IntSize, density: Density): Dp {
    if (boxSize == IntSize.Zero) return 140.dp
    val width = with(density) { boxSize.width.toDp() }
    val height = with(density) { boxSize.height.toDp() }
    return minOf(width * 0.92f, height * COVER_HEIGHT_FRACTION)
}

/** 宽屏右侧常驻播放面板（仅在有节目播放且未收起时挂载） */
@Composable
fun PlayerSidePane(
    title: String,
    date: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    boost: Int,
    onToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    tuningVisible: Boolean,
    onRequestTuning: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(AppColors.Player)
    ) {
        SheetHandle(onCollapse = onCollapse)
        PlayerPanel(
            title = title,
            date = date,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            boost = boost,
            onToggle = onToggle,
            onSeekTo = onSeekTo,
            onSeekBack = onSeekBack,
            onSeekForward = onSeekForward,
            onSpeed = onSpeed,
            onBoost = onBoost,
            onRequestTuning = onRequestTuning,
            tuningVisible = tuningVisible,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 26.dp, end = 26.dp, bottom = 22.dp),
            stretch = true
        )
    }
}

/**
 * 窄屏（分屏半幅 / 竖屏）：底部弹出的深色播放面板。
 * 顶部横线可单击收起（下滑收起交给面板自带的拖拽），回到迷你播放条。
 */
@Composable
fun PlayerSheet(
    title: String,
    date: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    boost: Int,
    onToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    tuningVisible: Boolean,
    onRequestTuning: () -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 先用面板自带的收起动画滑下去，再摘掉组件，避免点横条时组件被突然销毁
    val collapse: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onClose()
        }
        Unit
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = AppColors.Player,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { SheetHandle(onCollapse = collapse) }
    ) {
        PlayerPanel(
            title = title,
            date = date,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            boost = boost,
            onToggle = onToggle,
            onSeekTo = onSeekTo,
            onSeekBack = onSeekBack,
            onSeekForward = onSeekForward,
            onSpeed = onSpeed,
            onBoost = onBoost,
            onRequestTuning = onRequestTuning,
            tuningVisible = tuningVisible,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(bottom = 34.dp)
        )
    }
}

/**
 * 面板顶部横线：整条顶部区域（≥44dp 高）都可点，点一下即收起。
 * 下滑收起交给 ModalBottomSheet 自带的拖拽处理，这里不叠手势检测器，
 * 避免两个识别器互相抢事件导致点了没反应。
 */
@Composable
private fun SheetHandle(onCollapse: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onCollapse),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.38f))
        )
    }
}

/**
 * 拆出来的倍速 + 音量增强设置层：默认不占播放面板位置，
 * 由面板底部的小按钮弹出，选完点一下外面（或「完成」）即缩回。
 */
@Composable
private fun PlaybackSettingsDialog(
    speed: Float,
    boost: Int,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TuningContent(
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth(),
            speed = speed,
            boost = boost,
            onSpeed = onSpeed,
            onBoost = onBoost,
            onDone = onClose
        )
    }
}

/** 深色圆角设置面板：音量增强滑杆 + 倍速分段 */
@Composable
private fun TuningContent(
    speed: Float,
    boost: Int,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.PlayerRaised)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            "播放设置",
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.PlayerTitle
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "音量增强",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerMeta,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$boost%",
                style = MaterialTheme.typography.labelSmall,
                color = if (boost > VolumeBoost.MIN_PERCENT) Color.White else AppColors.PlayerSub,
                fontWeight = FontWeight.Bold
            )
        }
        FlatSlider(
            value = (boost - VolumeBoost.MIN_PERCENT).toFloat() /
                (VolumeBoost.MAX_PERCENT - VolumeBoost.MIN_PERCENT),
            onValueChange = { fraction ->
                onBoost(
                    (VolumeBoost.MIN_PERCENT + fraction *
                        (VolumeBoost.MAX_PERCENT - VolumeBoost.MIN_PERCENT))
                        .roundToInt()
                        .coerceIn(VolumeBoost.MIN_PERCENT, VolumeBoost.MAX_PERCENT)
                )
            },
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "倍速",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerMeta,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "全局记忆",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerSub
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SPEED_OPTIONS.forEach { option ->
                val selected = option == speed
                val cellBackground by animateColorAsState(
                    targetValue = if (selected) AppColors.Red else AppColors.PlayerControl,
                    animationSpec = tween(200),
                    label = "speedCell"
                )
                val textColour by animateColorAsState(
                    targetValue = if (selected) Color.White else AppColors.PlayerOption,
                    animationSpec = tween(200),
                    label = "speedText"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cellBackground)
                        .clickable { onSpeed(option) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        formatSpeed(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColour,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.PlayerControl)
                .clickable(onClick = onDone)
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "完成",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * stretch=true 时是三栏常驻面板的竖向布局：封面区吃掉剩余高度、封面居中，
 * 标题/进度/控制/设置栏依次排在下面；否则跟随宽度铺满并可滚动。
 */
@Composable
private fun PlayerPanel(
    title: String,
    date: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    boost: Int,
    onToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    onRequestTuning: () -> Unit,
    tuningVisible: Boolean,
    modifier: Modifier = Modifier,
    stretch: Boolean = false
) {
    if (stretch) {
        val density = LocalDensity.current
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        val artSize = coverSize(boxSize, density)
        Box(
            modifier
                .fillMaxSize()
                .onSizeChanged { boxSize = it }
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 封面区拿走面板高度与内容之间的差值，封面在其中垂直居中
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Cover(artSize)
                }
                ContentBlock(
                    title = title,
                    date = date,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    speed = speed,
                    boost = boost,
                    tuningVisible = tuningVisible,
                    onToggle = onToggle,
                    onSeekTo = onSeekTo,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onRequestTuning = onRequestTuning,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else {
        Column(
            modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Cover(null)
            ContentBlock(
                title = title,
                date = date,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                speed = speed,
                boost = boost,
                tuningVisible = tuningVisible,
                onToggle = onToggle,
                onSeekTo = onSeekTo,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                onRequestTuning = onRequestTuning,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (tuningVisible) {
        PlaybackSettingsDialog(
            speed = speed,
            boost = boost,
            onSpeed = onSpeed,
            onBoost = onBoost,
            onClose = onRequestTuning
        )
    }
}

/** 标题 / 日期 / 进度 / 控制 / 底部设置栏，常驻面板与滑层共用同一份实现 */
@Composable
private fun ContentBlock(
    title: String,
    date: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    boost: Int,
    tuningVisible: Boolean,
    onToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onRequestTuning: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        NowPlaying(title, date, center = true)
        Scrubber(positionMs, durationMs, onSeekTo, Modifier.padding(top = 14.dp))
        Controls(
            isPlaying = isPlaying,
            onToggle = onToggle,
            onSeekBack = onSeekBack,
            onSeekForward = onSeekForward
        )
        Spacer(Modifier.height(BOTTOM_GAP))
        TuningFooter(
            speed = speed,
            boost = boost,
            enabled = !tuningVisible,
            onClick = onRequestTuning
        )
    }
}

/**
 * 封面固定正方形：给了 artSize 就精确用这个边长（只挂 size 一个尺寸修饰符），
 * 否则铺满可用宽度（此时才用 aspectRatio）。两者绝不同时出现，避免互相打架。
 */
@Composable
private fun Cover(artSize: Dp? = null) {
    val shape = Modifier
        .then(if (artSize != null) Modifier.size(artSize) else Modifier.fillMaxWidth())
        .clip(PanelShape)
    if (artSize != null) {
        CoverArt(shape)
        return
    }
    BoxWithConstraints(shape.aspectRatio(1f)) { CoverArt(Modifier.fillMaxSize()) }
}

@Composable
private fun CoverArt(modifier: Modifier) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.hosts_cover),
            contentDescription = "一些事一些情",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        val radiusPx = with(LocalDensity.current) { 200.dp.toPx() }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(radiusPx * 0.78f, radiusPx * 0.18f),
                        radius = radiusPx * 0.55f
                    )
                )
        )
    }
}

@Composable
private fun NowPlaying(title: String, date: String, center: Boolean = false) {
    val alignment = if (center) Alignment.CenterHorizontally else Alignment.Start
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        horizontalAlignment = alignment
    ) {
        Text(
            title.ifBlank { "正在加载…" },
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.PlayerTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start
        )
        if (date.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                date,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.PlayerSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (center) TextAlign.Center else TextAlign.Start
            )
        }
    }
}

@Composable
private fun Controls(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkipButton(Icons.Filled.Replay30, "后退 30 秒", onSeekBack)
        Spacer(Modifier.width(26.dp))
        Box(
            Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = isPlaying, label = "playIcon") { playing ->
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = AppColors.Player,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.width(26.dp))
        SkipButton(Icons.Filled.Forward30, "前进 30 秒", onSeekForward)
    }
}

@Composable
private fun SkipButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Column(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(AppColors.PlayerControl)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(
            "30s",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 白色细轨道 + 圆点，触控热区 30dp */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val played = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val shown = dragFraction ?: played
    // 进度每 500ms 才回传一次，补间让指针连续走动；手指拖动时立即响应
    val indicator by animateFloatAsState(
        targetValue = shown,
        animationSpec = if (dragFraction == null) {
            tween(500, easing = LinearEasing)
        } else {
            tween(0)
        },
        label = "scrub"
    )
    val enabled = durationMs > 0

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(durationMs) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var change = down
                                val width = size.width.toFloat()
                                while (change.pressed) {
                                    change = awaitPointerEvent()
                                        .changes
                                        .firstOrNull { it.id == down.id }
                                        ?: break
                                    if (change.pressed) {
                                        dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                                    }
                                }
                                val target = dragFraction
                                dragFraction = null
                                if (target != null) {
                                    onSeekTo((target * durationMs).toLong().coerceAtLeast(0))
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackWidth = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppColors.PlayerTrack)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(indicator)
                        .fillMaxHeight()
                        .background(Color.White)
                )
            }
            if (enabled) {
                Box(
                    Modifier
                        .size(16.dp)
                        .offset(x = (trackWidth * indicator).coerceIn(0.dp, trackWidth - 16.dp), y = 0.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatMillis(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerMeta
            )
            Text(
                if (durationMs > 0) formatMillis(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerMeta
            )
        }
    }
}

/** 面板底部的设置入口（整行铺满，视觉重心落在底部）；非默认时速带出当前档位 */
@Composable
private fun TuningFooter(
    speed: Float,
    boost: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val speedChanged = speed != 1.0f
    val boostChanged = boost > VolumeBoost.MIN_PERCENT
    val highlighted = speedChanged || boostChanged
    val container by animateColorAsState(
        targetValue = if (highlighted) AppColors.PlayerControl else AppColors.PlayerRaised,
        animationSpec = tween(200),
        label = "tuningFooter"
    )

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            tint = if (highlighted) Color.White else AppColors.PlayerOption,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "播放设置",
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.PlayerOption
        )
        Spacer(Modifier.weight(1f))
        Text(
            buildList {
                if (boostChanged) add("音量 $boost%")
                if (speedChanged) add(formatSpeed(speed))
            }.joinToString(" · ").ifEmpty { "默认" },
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) Color.White else AppColors.PlayerMeta,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** 与进度条同风格的横向滑杆，触控热区 30dp，点击即定位 */
@Composable
private fun FlatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var change = down
                    val width = size.width.toFloat()
                    fun fractionOf(x: Float) = (x / width).coerceIn(0f, 1f)
                    while (change.pressed) {
                        onValueChange(fractionOf(change.position.x))
                        change.consume()
                        change = awaitPointerEvent()
                            .changes
                            .firstOrNull { it.id == down.id }
                            ?: break
                    }
                    onValueChange(fractionOf(change.position.x))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = maxWidth
        val shown by animateFloatAsState(
            targetValue = value.coerceIn(0f, 1f),
            animationSpec = tween(120),
            label = "boostThumb"
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AppColors.PlayerTrack)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .background(Color.White)
            )
        }
        Box(
            Modifier
                .size(16.dp)
                .offset(x = (trackWidth * shown).coerceIn(0.dp, trackWidth - 16.dp))
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * 迷你播放器。centered = true 时是横屏/平板用的悬浮胶囊：整体居中、宽度收窄，
 * 左边小封面 + 曲名，右边传输控件（Apple Music 样式）；否则是窄屏底部通栏。
 */
@Composable
fun MiniPlayer(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    boost: Int,
    onToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false
) {
    if (centered) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MiniPill(
                title = title,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
                boost = boost,
                onToggle = onToggle,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                onOpen = onOpen,
                modifier = modifier.widthIn(max = 640.dp)
            )
        }
        return
    }
    MiniBar(
        title = title,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
        boost = boost,
        onToggle = onToggle,
        onOpen = onOpen,
        modifier = modifier
    )
}

/** 横屏悬浮胶囊：居中的一条，不用横跨整屏 */
@Composable
private fun MiniPill(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    boost: Int,
    onToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(AppColors.Player)
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.hosts_cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "正在加载…" },
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.PlayerTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatMillis(positionMs))
                    if (durationMs > 0) append(" / ").append(formatMillis(durationMs))
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerSub,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        TuningBadges(speed = speed, boost = boost)
        Spacer(Modifier.width(10.dp))
        PillIconButton(
            icon = Icons.Filled.Replay30,
            contentDescription = "后退 30 秒",
            onClick = onSeekBack
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = AppColors.Player,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        PillIconButton(
            icon = Icons.Filled.Forward30,
            contentDescription = "前进 30 秒",
            onClick = onSeekForward
        )
    }
}

@Composable
private fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = AppColors.PlayerOption,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 非默认的倍速 / 音量档位角标，默认状态不占位置 */
@Composable
private fun TuningBadges(speed: Float, boost: Int) {
    if (speed == 1.0f && boost <= VolumeBoost.MIN_PERCENT) return
    Text(
        buildList {
            if (speed != 1.0f) add(formatSpeed(speed))
            if (boost > VolumeBoost.MIN_PERCENT) add("音量 $boost%")
        }.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.PlayerOption,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** 窄屏底部通栏迷你条 */
@Composable
private fun MiniBar(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    boost: Int,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AppColors.Player)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(AppColors.Red)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "正在加载…" },
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.PlayerTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatMillis(positionMs))
                    if (durationMs > 0) append(" / ").append(formatMillis(durationMs))
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.PlayerSub,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        TuningBadges(speed = speed, boost = boost)
    }
}

internal fun formatSpeed(value: Float): String {
    val num = if (value % 1.0f == 0f) value.toInt().toString() else value.toString()
    return "${num}×"
}
