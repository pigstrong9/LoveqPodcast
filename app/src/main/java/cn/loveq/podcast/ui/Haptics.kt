package cn.loveq.podcast.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 「哒哒哒」的齿轮刻度震动（CLOCK_TICK）。
 *
 * 拖动倍速/睡眠/进度条时，每跨过一个档位调一次；返回的函数可在非组合线程安全调用。
 * 走 View 层的 CLOCK_TICK 而不是 Compose 的 HapticFeedbackType——后者没有这种短促刻度感。
 */
@Composable
internal fun rememberClockTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}
