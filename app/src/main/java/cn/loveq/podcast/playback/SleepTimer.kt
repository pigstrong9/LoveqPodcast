package cn.loveq.podcast.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 睡眠定时（进程级单例，刻意**不落盘**）。
 *
 * · 倒计时与「本集播完暂停」互不冲突：倒计时先到就先暂停；
 *   两者都没到，播到可听终点时由「本集结束」接管。
 * · 界面只负责写状态，真正的计时与暂停在 [PlaybackService] 里做——
 *   用户退出界面后播放还在继续，计时不能跟着 ViewModel 一起死。
 * · 不跨重启：几天后突然被「上次设的 30 分钟」暂停，比定时失效更糟。
 */
object SleepTimer {

    data class State(
        /** 运行中的倒计时分钟数；null = 未设定时 */
        val minutes: Int? = null,
        /** 距暂停还剩多少毫秒（仅 UI 展示，用整秒递减） */
        val remainingMs: Long = 0L,
        /** 本集播完就暂停（与倒计时相互独立） */
        val stopAtEpisodeEnd: Boolean = false,
        /**
         * 每次设定档位（含关闭）自增。
         * 服务端监听它而不是 minutes：同值重选（剩 5 分钟时再点 30）时
         * minutes 不变、StateFlow 不发射，倒计时无从重启；generation 保证每次
         * 设定都触发服务端重启循环。
         */
        val generation: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val minutes: Int? get() = _state.value.minutes

    fun setMinutes(minutes: Int?) {
        _state.update {
            if (minutes == null || minutes <= 0) {
                it.copy(
                    minutes = null,
                    remainingMs = 0L,
                    generation = it.generation + 1
                )
            } else {
                it.copy(
                    minutes = minutes,
                    remainingMs = minutes * 60_000L,
                    generation = it.generation + 1
                )
            }
        }
    }

    fun setStopAtEpisodeEnd(on: Boolean) {
        _state.update { it.copy(stopAtEpisodeEnd = on) }
    }

    /** 服务端每秒回写剩余时间；只更新展示字段，不动用户配置 */
    fun tick(remainingMs: Long) {
        _state.update { it.copy(remainingMs = remainingMs.coerceAtLeast(0)) }
    }

    /** 倒计时已到：清掉倒计时，但保留「本集结束」开关 */
    fun finishCountdown() {
        _state.update { it.copy(minutes = null, remainingMs = 0L) }
    }

    /** 播到本集末尾时取用一次并关闭（一次性行为，不然后续每集都会停） */
    fun consumeStopAtEpisodeEnd(): Boolean {
        if (!_state.value.stopAtEpisodeEnd) return false
        _state.update { it.copy(stopAtEpisodeEnd = false) }
        return true
    }
}
