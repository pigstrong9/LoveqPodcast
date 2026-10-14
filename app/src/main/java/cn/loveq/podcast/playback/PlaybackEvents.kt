package cn.loveq.podcast.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次性错误提示通道。
 *
 * 用 StateFlow 而不是 SharedFlow：车机上错误往往发生在息屏、后台或隧道里，
 * 没有订阅者时 SharedFlow 会直接把消息丢掉，回到界面什么也看不到。
 * 存成「最近一条未展示的错误」，界面回来时仍能补上这一次提示。
 */
object PlaybackEvents {

    private val _error = MutableStateFlow<String?>(null)

    val error: StateFlow<String?> = _error

    /** 后到的错误覆盖先前的：提示本身就是「最近出了什么问题」 */
    fun report(message: String) {
        _error.value = message
    }

    fun consume() {
        _error.value = null
    }
}
