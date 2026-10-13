package cn.loveq.podcast.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 软件音量增强档位，100%–200%（即 +0…+6.02 dB）。
 * 音频线程只读 [gain]（volatile），UI 读写 [percent]，两者同进程共享，无需 IPC。
 */
object VolumeBoost {

    const val MIN_PERCENT = 100
    const val MAX_PERCENT = 200

    private val _percent = MutableStateFlow(MIN_PERCENT)
    val percent: StateFlow<Int> = _percent

    @Volatile
    var gain: Float = 1f
        private set

    fun apply(percent: Int) {
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        _percent.value = clamped
        gain = clamped / 100f
    }
}
