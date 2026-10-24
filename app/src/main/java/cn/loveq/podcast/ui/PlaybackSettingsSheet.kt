package cn.loveq.podcast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.loveq.podcast.playback.SleepTimer
import cn.loveq.podcast.playback.VolumeBoost
import cn.loveq.podcast.ui.theme.AppColors
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 倍速可选范围（0.1 一级） */
internal const val SPEED_MIN = 0.5f
internal const val SPEED_MAX = 3.0f

private const val SPEED_STEPS = 25 // (3.0 - 0.5) / 0.1
private const val SLEEP_MAX_MINUTES = 90
private const val SLEEP_STEP_MINUTES = 5
private const val SLEEP_STEPS = SLEEP_MAX_MINUTES / SLEEP_STEP_MINUTES

/** 滑动比例（0..1）→ 倍速，吸附到 0.1；纯函数，便于单测 */
internal fun speedFromFraction(fraction: Float): Float {
    val step = (fraction.coerceIn(0f, 1f) * SPEED_STEPS).roundToInt()
    // 用整数十分位再除回 10，避免 0.5f + 8 * 0.1f 这类浮点尘埃把标签写成 1.3000001×
    return ((SPEED_MIN * 10f + step) / 10f).coerceIn(SPEED_MIN, SPEED_MAX)
}

/** 倍速 → 滑动比例，供刻度尺定位；纯函数，便于单测 */
internal fun speedToFraction(speed: Float): Float =
    ((speed.coerceIn(SPEED_MIN, SPEED_MAX) - SPEED_MIN) / (SPEED_MAX - SPEED_MIN))

/** 滑动比例（0..1）→ 睡眠定时分钟；0 表示关 */
private fun sleepMinutesFromFraction(fraction: Float): Int =
    (fraction.coerceIn(0f, 1f) * SLEEP_STEPS).roundToInt() * SLEEP_STEP_MINUTES

/**
 * 小宇宙式的播放设置面板：从底部滑出、深色、抓条可下拉关闭。
 *
 * 内容顺序：跳过空白 / 人声增强 / 音量增强三块磁贴 → 倍速刻度尺 → 睡眠定时。
 * 手机竖屏占满宽度；车机横屏由调用方限制在右侧播放面板同宽（见 AppScreen）。
 */
@Composable
internal fun PlaybackSettingsSheet(
    speed: Float,
    boost: Int,
    skipBlank: Boolean,
    skipBlankMinutes: Int,
    vocalBoost: Boolean,
    sleep: SleepTimer.State,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    onSkipBlank: (Boolean) -> Unit,
    onVocalBoost: (Boolean) -> Unit,
    onSleepMinutes: (Int?) -> Unit,
    onSleepAtEnd: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 面板最大高度占比：车机矮屏也能完整看到全部内容 */
    maxHeightFraction: Float = 0.92f
) {    BoxWithConstraints(modifier) {
        // 高度上限按「扣掉导航栏后的可用高度」算：面板底部贴屏幕底（背景铺到手势条下），
        // 若直接用整屏高度做 max，内容恰好在可用高度之上、上限之下时会既滚不动又被屏幕裁掉
        // （MIUI 大字号下实测睡眠卡片被拦腰截断）
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val availableHeight = maxHeight - navBottom
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = availableHeight * maxHeightFraction)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(AppColors.Player)
                // 面板背景铺到屏幕底（含手势条区域），内容避开导航栏——
                // 否则深色面板底下会露出一条浅色应用背景（5:5 分屏尤其明显）
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp)
        ) {
            SheetGrabber(onDismiss)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "播放设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.PlayerTitle,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.PlayerSub,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))

            // ---- 三块磁贴：跳过空白 / 人声增强 / 音量增强 ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleTile(
                    name = "跳过空白",
                    state = if (skipBlank) "ON" else "OFF",
                    on = skipBlank,
                    subtitle = "${skipBlankMinutes} 分钟",
                    onClick = { onSkipBlank(!skipBlank) },
                    modifier = Modifier.weight(1f)
                )
                ToggleTile(
                    name = "人声增强",
                    state = if (vocalBoost) "ON" else "OFF",
                    on = vocalBoost,
                    subtitle = "实时",
                    onClick = { onVocalBoost(!vocalBoost) },
                    modifier = Modifier.weight(1f)
                )
                VolumeTile(
                    boost = boost,
                    onBoost = onBoost,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))

            // ---- 倍速 ----
            SheetCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "倍速播放",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.PlayerSub,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatSpeed(speed),
                        style = MaterialTheme.typography.labelLarge,
                        // 红色点缀：非默认倍速一眼可辨
                        color = if (speed != 1.0f) AppColors.Red else AppColors.PlayerTitle,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                SpeedRuler(speed = speed, onSpeed = onSpeed)
            }
            Spacer(Modifier.height(8.dp))

            // ---- 睡眠定时 ----
            SheetCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "睡眠定时",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppColors.PlayerSub,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        sleepHeaderValue(sleep),
                        style = MaterialTheme.typography.labelLarge,
                        // 红色点缀：倒计时或「本集结束」生效中
                        color = if (sleep.minutes != null || sleep.stopAtEpisodeEnd) {
                            AppColors.Red
                        } else {
                            AppColors.PlayerMeta
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                SleepRuler(
                    minutes = sleep.minutes,
                    onMinutes = { minutes ->
                        onSleepMinutes(minutes.takeIf { it > 0 })
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSleepAtEnd(!sleep.stopAtEpisodeEnd) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "定时到本集结束",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.PlayerSub
                    )
                    Spacer(Modifier.weight(1f))
                    PillSwitch(checked = sleep.stopAtEpisodeEnd)
                }
            }
        }
    }
}

/** 顶部抓条：点击或下拉关闭；下拉只挂在抓条这一条上，不与内容抢手势 */
@Composable
private fun SheetGrabber(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > 36.dp.toPx()) onDismiss()
                    }
                ) { _, delta ->
                    dragged += delta
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.PlayerTrack)
        )
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.PlayerRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        content()
    }
}

/** 跳过空白 / 人声增强的开关磁贴：整块可点 */
@Composable
private fun ToggleTile(
    name: String,
    state: String,
    on: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(
        targetValue = if (on) AppColors.Red else AppColors.PlayerControl,
        animationSpec = tween(180),
        label = "tileBg"
    )
    val nameColor by animateColorAsState(
        targetValue = if (on) Color.White else AppColors.PlayerSub,
        animationSpec = tween(180),
        label = "tileName"
    )
    val stateColor by animateColorAsState(
        targetValue = if (on) Color.White else AppColors.PlayerMeta,
        animationSpec = tween(180),
        label = "tileState"
    )
    Column(
        modifier
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = nameColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (on) Color.White.copy(alpha = 0.72f) else AppColors.PlayerMeta,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Text(
            state,
            style = MaterialTheme.typography.titleMedium,
            color = stateColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.End)
        )
    }
}

/** 音量增强磁贴：标题 + 档位独占一行（磁贴只有三分之一宽，标题同行放不下 200% 这类长值）+ 滑杆贴底 */
@Composable
private fun VolumeTile(
    boost: Int,
    onBoost: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.PlayerControl)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            "音量增强",
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.PlayerSub,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            "$boost%",
            style = MaterialTheme.typography.titleMedium,
            color = if (boost > VolumeBoost.MIN_PERCENT) Color.White else AppColors.PlayerMeta,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.weight(1f))
        MiniSlider(
            value = (boost - VolumeBoost.MIN_PERCENT).toFloat() /
                (VolumeBoost.MAX_PERCENT - VolumeBoost.MIN_PERCENT),
            onValueChange = { fraction ->
                onBoost(
                    (VolumeBoost.MIN_PERCENT + fraction *
                        (VolumeBoost.MAX_PERCENT - VolumeBoost.MIN_PERCENT))
                        .roundToInt()
                        .coerceIn(VolumeBoost.MIN_PERCENT, VolumeBoost.MAX_PERCENT)
                )
            }
        )
    }
}

/** 磁贴里的迷你滑杆：视觉 4dp、热区 22dp，点哪跳哪 */
@Composable
private fun MiniSlider(value: Float, onValueChange: (Float) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var change = down
                    val width = size.width.toFloat()
                    fun apply(x: Float) = onValueChange((x / width).coerceIn(0f, 1f))
                    apply(change.position.x)
                    while (change.pressed) {
                        change.consume()
                        change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id } ?: break
                        apply(change.position.x)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val shown by animateFloatAsState(
            targetValue = value.coerceIn(0f, 1f),
            animationSpec = tween(100),
            label = "miniBoost"
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.PlayerTrack)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .height(4.dp)
                    .background(Color.White)
            )
        }
        Box(
            Modifier
                .offset(x = (maxWidth * shown).coerceIn(0.dp, maxWidth - 10.dp))
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * 倍速刻度尺：0.5–3.0、0.1 一级。
 * 拖动/点击任意位置即吸附到最近的 0.1，当前值浮在刻度条上方。
 */
@Composable
private fun SpeedRuler(speed: Float, onSpeed: (Float) -> Unit) {
    val fraction = speedToFraction(speed)
    // 齿轮感：每跨过 0.1 一档哒一下
    val clockTick = rememberClockTick()
    Column {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var change = down
                        val width = size.width.toFloat()
                        var lastStep = Int.MIN_VALUE
                        fun apply(x: Float) {
                            val next = speedFromFraction(x / width)
                            val step = ((next - SPEED_MIN) / 0.1f).roundToInt()
                            if (step != lastStep) {
                                lastStep = step
                                clockTick()
                            }
                            onSpeed(next)
                        }
                        apply(change.position.x)
                        while (change.pressed) {
                            change.consume()
                            change = awaitPointerEvent().changes
                                .firstOrNull { it.id == down.id } ?: break
                            apply(change.position.x)
                        }
                    }
                }
        ) {
            val thumbX = maxWidth * fraction
            // AppColors 是 @Composable getter，先在组合里取值再进绘制 lambda
            val majorTick = AppColors.PlayerMeta
            val minorTick = AppColors.PlayerTrack
            val accentTick = AppColors.Red
            Canvas(Modifier.fillMaxSize()) {
                val bottom = size.height - 1.dp.toPx()
                for (i in 0..SPEED_STEPS) {
                    val x = size.width * i / SPEED_STEPS
                    val major = i % 5 == 0
                    drawLine(
                        color = if (major) majorTick else minorTick,
                        start = Offset(x, bottom - (if (major) 14.dp.toPx() else 9.dp.toPx())),
                        end = Offset(x, bottom),
                        strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx()
                    )
                }
                // 当前档位刻度：主题色、加高加粗（小宇宙用金色标记当前档位）
                val cx = size.width * fraction
                drawRoundRect(
                    color = accentTick,
                    topLeft = Offset(cx - 1.25.dp.toPx(), bottom - 18.dp.toPx()),
                    size = Size(2.5.dp.toPx(), 18.dp.toPx()),
                    cornerRadius = CornerRadius(1.25.dp.toPx(), 1.25.dp.toPx())
                )
            }
            // 当前值气泡：跟着拇指走，靠近两端时贴边；非默认倍速用红色点缀
            Text(
                formatSpeed(speed),
                style = MaterialTheme.typography.labelSmall,
                color = if (speed != 1.0f) AppColors.Red else AppColors.PlayerTitle,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(x = (thumbX - 18.dp).coerceIn(0.dp, maxWidth - 36.dp))
                    .width(36.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("0.5", "1.0", "1.5", "2.0", "2.5", "3.0").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.PlayerMeta
                )
            }
        }
    }
}

/**
 * 睡眠定时刻度尺（小宇宙式）：主刻度是带圈数字（关/30/60/90），次刻度是圆点；
 * 选中后从左端充填主题色粗胶囊直到所选位置，白色竖线压在填充末端，
 * 走过的带圈数字被填充盖住（与小宇宙一致）。点击/拖动按 5 分钟一级取值。
 */
@Composable
private fun SleepRuler(minutes: Int?, onMinutes: (Int) -> Unit) {
    val current = (minutes ?: 0).coerceIn(0, SLEEP_MAX_MINUTES)
    val fraction = current.toFloat() / SLEEP_MAX_MINUTES
    // 齿轮感：每跨过 5 分钟一档哒一下
    val clockTick = rememberClockTick()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var change = down
                    val width = size.width.toFloat()
                    var lastStep = Int.MIN_VALUE
                    fun apply(x: Float) {
                        val next = sleepMinutesFromFraction(x / width)
                        if (next != lastStep) {
                            lastStep = next
                            clockTick()
                        }
                        onMinutes(next)
                    }
                    apply(change.position.x)
                    while (change.pressed) {
                        change.consume()
                        change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id } ?: break
                        apply(change.position.x)
                    }
                }
            }
    ) {
        val trackWidth = maxWidth
        val circleSize = 30.dp
        val accent = AppColors.Red
        val circleBorder = AppColors.PlayerMeta
        val dotColor = AppColors.PlayerMeta

        // 次刻度圆点：主刻度之间均分 4 个
        listOf(0, 30, 60).forEach { base ->
            for (k in 1..4) {
                val f = (base + k * (30f / 5)) / SLEEP_MAX_MINUTES
                Box(
                    Modifier
                        .offset(x = (trackWidth * f - 1.5.dp).coerceIn(0.dp, trackWidth - 3.dp))
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .align(Alignment.CenterStart)
                )
            }
        }
        // 主刻度：带圈数字
        listOf(0 to "关", 30 to "30", 60 to "60", 90 to "90").forEach { (value, label) ->
            val f = value.toFloat() / SLEEP_MAX_MINUTES
            Box(
                Modifier
                    .offset(x = (trackWidth * f - circleSize / 2).coerceIn(0.dp, trackWidth - circleSize))
                    .size(circleSize)
                    .clip(CircleShape)
                    .background(AppColors.PlayerControl)
                    .border(1.dp, circleBorder, CircleShape)
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.PlayerTitle,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // 选中后的粗胶囊填充：从左端到所选位置，盖过走过的刻度
        if (current > 0) {
            Box(
                Modifier
                    .offset(x = 0.dp)
                    .width(trackWidth * fraction)
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(accent)
                    .align(Alignment.CenterStart)
            )
        }
    }
}

/** 小胶囊开关，深色面板专用 */
@Composable
private fun PillSwitch(checked: Boolean) {
    val track by animateColorAsState(
        targetValue = if (checked) AppColors.Red else AppColors.PlayerTrack,
        animationSpec = tween(160),
        label = "switchTrack"
    )
    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(160),
        label = "switchThumb"
    )
    Box(
        Modifier
            .width(34.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(track)
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .offset(x = (34.dp - 20.dp) * offset)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

private fun formatSleep(state: SleepTimer.State): String {
    val minutes = state.minutes ?: return "OFF"
    if (minutes <= 0) return "OFF"
    val totalSeconds = ((state.remainingMs + 999) / 1000).coerceAtLeast(0)
    return String.format(
        Locale.ROOT,
        "%d:%02d",
        totalSeconds / 60,
        totalSeconds % 60
    )
}

/** 睡眠卡片右上角的档位文案：倒计时剩余 / 只开了本集结束 / 关 */
private fun sleepHeaderValue(state: SleepTimer.State): String = when {
    state.minutes != null -> formatSleep(state)
    state.stopAtEpisodeEnd -> "本集结束"
    else -> "OFF"
}

/**
 * 承载面板的 Dialog：只有一层窗口，但从底部升起。
 *
 * [sheetWidth] 为 null 时铺满窗口（手机/分屏）；车机三栏传入右侧播放面板宽度，
 * 面板就贴着右边缘、与播放器同宽升起。[alignEnd] 控制贴哪边。
 *
 * [windowMaxHeight] 由调用方（Activity 作用域，insets 可信）按「屏幕 − 状态栏 −
 * 导航栏」算好传入：MIUI 会把弹窗窗口按内容尺寸居中放进扣除系统栏的父区域，
 * 内容不钳制时窗口底伸到屏幕外，贴底的面板内容被物理屏幕裁掉。
 */
@Composable
internal fun PlaybackSettingsSheetDialog(
    sheetWidth: Dp?,
    alignEnd: Boolean,
    windowMaxHeight: Dp,
    speed: Float,
    boost: Int,
    skipBlank: Boolean,
    skipBlankMinutes: Int,
    vocalBoost: Boolean,
    sleep: SleepTimer.State,
    onSpeed: (Float) -> Unit,
    onBoost: (Int) -> Unit,
    onSkipBlank: (Boolean) -> Unit,
    onVocalBoost: (Boolean) -> Unit,
    onSleepMinutes: (Int?) -> Unit,
    onSleepAtEnd: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val close = {
        if (visible) {
            visible = false
            scope.launch {
                delay(200)
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                // 顺序不能反：fillMaxSize 在前会把高度定死成整屏、heightIn 的钳制被无视
                .heightIn(max = windowMaxHeight)
                .fillMaxSize()
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(160))
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = close
                        )
                )
            }
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140)),
                modifier = Modifier
                    .align(if (alignEnd) Alignment.BottomEnd else Alignment.BottomCenter)
                    .then(
                        if (sheetWidth != null) Modifier.width(sheetWidth)
                        else Modifier.fillMaxWidth()
                    )
            ) {
                PlaybackSettingsSheet(
                    speed = speed,
                    boost = boost,
                    skipBlank = skipBlank,
                    skipBlankMinutes = skipBlankMinutes,
                    vocalBoost = vocalBoost,
                    sleep = sleep,
                    onSpeed = onSpeed,
                    onBoost = onBoost,
                    onSkipBlank = onSkipBlank,
                    onVocalBoost = onVocalBoost,
                    onSleepMinutes = onSleepMinutes,
                    onSleepAtEnd = onSleepAtEnd,
                    onDismiss = close
                )
            }
        }
    }
}
