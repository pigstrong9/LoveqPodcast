@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.loveq.podcast.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.Layout
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
import kotlin.math.roundToInt

internal val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

private val PanelShape = RoundedCornerShape(24.dp)

/**
 * 封面边长占面板宽度的比例。
 * 高度不够时 [COVER_MIN] / 剩余高度会自动把它压下来，所以这里可以放得比较宽：
 * 车机 1280×720dp 的右侧栏有 660dp 高，比例太小会让封面缩在中间、上下大片空白。
 */
private const val COVER_WIDTH_FRACTION = 0.85f

/** 封面边长下限：再挤也不能缩成邮票 */
private val COVER_MIN = 150.dp

/** 极矮面板（如 360dp 高的分屏窄条）的封面下限：保住播放键在首屏可见比封面大小更重要 */
private val COVER_MIN_SHORT = 96.dp

/** 封面与底部内容之间的最小间距 */
private val BOTTOM_GAP = 12.dp

/** 间距上限：富余高度优先给封面，剩下的才摊到间距上，避免封面和内容脱节 */
private val GAP_MAX = 36.dp

/** 底部内容与容器下沿之间的额外余量（系统手势条由外层 insets 处理，这里只留呼吸空间） */
private val BOTTOM_CLEARANCE = 12.dp

/** 封面上下的最小边距，避免内容贴到横线或屏幕边缘 */
private val EDGE_MARGIN = 8.dp

/** 封面边长的收尾折扣：让封面离面板边缘留一点呼吸 */
private const val COVER_FILL = 0.96f

/**
 * 全屏播放面板走「左封面 + 右控件」分栏所需的最小宽高比。
 * 低于它说明窗口接近方形（车机分屏就是这种），分栏会让封面按高度撑成巨无霸、
 * 控件被挤到右侧变形，不如回到上下堆叠并保证水平居中。
 */
private const val LANDSCAPE_SPLIT_RATIO = 1.5f

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
                .padding(start = 26.dp, end = 26.dp, bottom = 10.dp)
        )
    }
}

/**
 * 窄屏（竖屏 / 分屏半幅）：全屏播放面板。
 * 收起只有「点顶部小横条」这一条路（外加返回键），不做任何拖拽 ——
 * 拖动会和进度条等控件的手势打架，收益不值这个风险。
 * 横屏（宽 > 高，车机全屏播放就是这种）时改用左右分栏：封面在左、控件在右。
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
    FullScreenPlayerDialog(onClose = onClose) {
        BoxWithConstraints {
            // 只有真正「宽扁」的窗口才值得左右分栏；车机分屏拿到的窗口接近方形，
            // 分栏会让封面按高度撑大、把控件挤到边上，不如回到上下堆叠并水平居中。
            val horizontal = maxWidth > maxHeight * LANDSCAPE_SPLIT_RATIO
            if (horizontal) {
                LandscapePlayerContent(
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
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                )
            } else {
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
                        .fillMaxSize()
                        .padding(horizontal = 26.dp)
                        // 底部额外留出系统手势条/导航栏的高度，否则底部设置栏会被它压住
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 12.dp)
                )
            }
        }
    }
}

/** 横屏全屏播放器：左封面右控件的分栏布局，车机矮屏专用 */
@Composable
private fun LandscapePlayerContent(
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .padding(horizontal = 34.dp, vertical = 14.dp)
            .fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面：边长按可用高度的 5 成，且不超过宽度的 4 成，
        // 给右侧控件组留出稳定空间（宽扁窗口下高度是稀缺项，按高度算更容易失控）
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val side = (maxHeight * 0.5f).coerceAtMost(maxWidth * 0.4f)
            Box(
                Modifier
                    .size(side)
                    .clip(PanelShape)
            ) {
                CoverArt(Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(38.dp))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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

/**
 * 全屏播放面板的宿主：全屏 Dialog + 顶部小横条。
 * 面板本身不参与任何手势识别，所以拖进度条、拖任何控件都不会误触收起。
 */
@Composable
private fun FullScreenPlayerDialog(onClose: () -> Unit, panel: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = CARD_TOP_MARGIN)
                .clip(
                    RoundedCornerShape(
                        topStart = CARD_CORNER,
                        topEnd = CARD_CORNER,
                        bottomStart = 0.dp,
                        bottomEnd = 0.dp
                    )
                )
                .background(AppColors.Player)
                // 同上：状态栏已隐藏，只避让导航栏，否则顶部会多一条空白
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(Modifier.fillMaxSize()) {
                SheetHandle(onCollapse = onClose)
                Box(Modifier.weight(1f).fillMaxWidth()) { panel() }
            }
        }
    }
}

/** 全屏播放卡片顶部留出的缝隙，露出后面的内容，配合圆角更像 iOS 的全屏播放器 */
private val CARD_TOP_MARGIN = 10.dp

/** 卡片顶部圆角（iOS 风格的大圆角） */
private val CARD_CORNER = 28.dp

/**
 * 顶部小横条：整条顶部区域（≥44dp 高）都可点，点一下即收起。
 * 这是全屏面板唯一的收起入口，刻意不叠任何拖拽识别器。
 * 光秃一条 4dp 横线在常驻面板上没有「收起」暗示，补一枚向下的 chevron。
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "收起",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .size(width = 44.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.38f))
            )
        }
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
 * 播放面板：横竖屏、常驻侧栏与全屏滑层共用同一套布局。
 *
 * 用一次自定义测量同时得到「固定内容的真实高度」和「容器宽高」，然后：
 * 封面边长 = min(可用宽度, 可用高度 − 固定内容高度) —— 剩余空间全给封面，
 * 因此既不会在封面上下留出大片空白（封面会跟着长大），
 * 也不会把底部设置栏挤出屏幕（固定内容按实测高度预留）。
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
    modifier: Modifier = Modifier
) {
    // 极矮面板（封面按下限兜底后仍放不下全部内容）允许纵向滚动，正常尺寸无感。
    // verticalScroll 会把高度约束变成无穷、居中随之失效（内容顶在上、下面一大片空白），
    // 所以先用 BoxWithConstraints 拿面板真实高度，再用 heightIn(min=) 传进 Layout 当基准。
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Layout(
                modifier = Modifier.fillMaxWidth().heightIn(min = panelHeight),
                content = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Cover(null)
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
            ) { measurables, constraints ->
                // 面板高度（px）：滚动时 maxHeight 是无穷，但 heightIn(min=) 让 minHeight 是真实值
                val target = constraints.minHeight
                val widthConstraint = constraints.copy(minWidth = 0, minHeight = 0)
                // 先量「标题/进度/控制/设置栏」
                val content = measurables[1].measure(widthConstraint)
                val gapMin = BOTTOM_GAP.roundToPx()
                val edge = EDGE_MARGIN.roundToPx()
                val clearance = BOTTOM_CLEARANCE.roundToPx()
                val usable = (target - edge * 2 - clearance).coerceAtLeast(0)
                // 封面边长 = min(宽度比例, 剩余高度)，再兜一个下限。
                // 不按「高/矮面板」分档：剩余高度这一项本身就会在矮面板上把封面压下来，
                // 少一个魔法阈值就少一种在真实车机密度下翻车的可能。
                val coverWidthCap = (constraints.maxWidth * COVER_WIDTH_FRACTION).toInt()
                val heightCap = (usable - content.height - gapMin).coerceAtLeast(0)
                // 下限：极矮的面板按剩余高度会把封面压成邮票大小，
                // 保住最小可辨识度，放不下就交给外层滚动。
                // 下限本身也随面板高度收缩：360dp 高的分屏窄条里，
                // 让播放键留在首屏比封面体面更重要。
                val floor = maxOf(
                    COVER_MIN_SHORT.roundToPx(),
                    minOf(COVER_MIN.roundToPx(), (usable * 0.30f).toInt())
                ).coerceAtMost(usable)
                val lo = floor.coerceAtMost(constraints.maxWidth)
                val hi = (usable * COVER_FILL).toInt().coerceAtLeast(lo)
                val side = (minOf(coverWidthCap, maxOf(heightCap, floor)) * COVER_FILL)
                    .toInt()
                    .coerceIn(lo, hi)
                // maxWidth 也锁到 side：Cover 用 fillMaxWidth + aspectRatio(1f)，
                // 不锁宽的话它会被拉成「面板宽 × side」的扁图
                val cover = measurables[0].measure(
                    constraints.copy(
                        minWidth = 0,
                        maxWidth = side,
                        minHeight = side,
                        maxHeight = side
                    )
                )
                // 富余高度先摊一点到封面与内容的间距上（有上限），剩下的留给上下对称留白，
                // 这样高面板不会被塞满、矮面板也不会挤成一团
                val gap = (gapMin + (usable - content.height - cover.height) / 2)
                    .coerceIn(gapMin, GAP_MAX.roundToPx())
                val groupHeight = cover.height + gap + content.height
                // 放得下就整组垂直居中（上下空白对称）；放不下就顶到最上、交给外层滚动
                val top = ((target - groupHeight) / 2).coerceAtLeast(0)
                layout(constraints.maxWidth, maxOf(target, groupHeight)) {
                    cover.place(x = (constraints.maxWidth - cover.width) / 2, y = top)
                    content.place(x = 0, y = top + cover.height + gap)
                }
            }
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
            .padding(top = 12.dp),
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
            .padding(top = 12.dp),
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

/** 白色细轨道 + 圆点，视觉 6dp、触控热区 44dp */
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
                // 视觉仍是 6dp 细轨，热区扩到 44dp 触控下限（车机/颠簸环境好按）
                .heightIn(min = 44.dp)
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
                                        change.consume()
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

/** 与进度条同风格的横向滑杆，视觉 6dp、热区 44dp，点击即定位 */
@Composable
private fun FlatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
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
                modifier = modifier.widthIn(max = 760.dp)
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
        onSeekBack = onSeekBack,
        onSeekForward = onSeekForward,
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
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(AppColors.Player)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.hosts_cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(15.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "正在加载…" },
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.PlayerTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatMillis(positionMs))
                    if (durationMs > 0) append(" / ").append(formatMillis(durationMs))
                },
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.PlayerSub,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        TuningBadges(speed = speed, boost = boost)
        Spacer(Modifier.width(12.dp))
        PillIconButton(
            icon = Icons.Filled.Replay30,
            contentDescription = "后退 30 秒",
            onClick = onSeekBack
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = AppColors.Player,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
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
            .size(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = AppColors.PlayerOption,
            modifier = Modifier.size(26.dp)
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
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(AppColors.Player)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillIconButton(
            icon = Icons.Filled.Replay30,
            contentDescription = "后退 30 秒",
            onClick = onSeekBack
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(AppColors.Red)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        PillIconButton(
            icon = Icons.Filled.Forward30,
            contentDescription = "前进 30 秒",
            onClick = onSeekForward
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "正在加载…" },
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.PlayerTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatMillis(positionMs))
                    if (durationMs > 0) append(" / ").append(formatMillis(durationMs))
                },
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.PlayerSub,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        TuningBadges(speed = speed, boost = boost)
    }
}

internal fun formatSpeed(value: Float): String {
    val num = if (value % 1.0f == 0f) value.toInt().toString() else value.toString()
    return "${num}×"
}
