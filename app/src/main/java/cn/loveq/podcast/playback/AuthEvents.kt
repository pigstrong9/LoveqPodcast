package cn.loveq.podcast.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 官网要求登录时的一次提示。
 *
 * 和 [PlaybackEvents] 一样用 StateFlow：播放服务可能在息屏、后台或车机启动时
 * 就撞上登录墙，回到界面后这次提示仍要能补上，不能像 SharedFlow 那样丢掉。
 *
 * 带上「登录成功后重试什么」：撞墙可能发生在播放解析，也可能发生在离线缓存，
 * 二者重试入口不同。同时打开期间只保留第一条，避免多个失败叠出多个登录框。
 */
object AuthEvents {

    enum class Retry {
        /** 列表/索引等页面撞墙：登录后没有要续播或续传的任务 */
        NONE,
        CURRENT_PLAYBACK,
        DOWNLOAD
    }

    data class Request(
        val retry: Retry,
        /** 离线缓存撞墙时用；播放重试沿用当前队列，不需要 ID */
        val episodeId: Long? = null
    )

    private val _loginRequired = MutableStateFlow<Request?>(null)

    val loginRequired: StateFlow<Request?> = _loginRequired

    fun requestLogin(
        retry: Retry = Retry.CURRENT_PLAYBACK,
        episodeId: Long? = null
    ) {
        if (_loginRequired.value == null) {
            _loginRequired.value = Request(retry, episodeId)
        }
    }

    fun dismissLogin() {
        _loginRequired.value = null
    }
}
