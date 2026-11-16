package cn.loveq.podcast.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 登录墙事件的合并规则。
 *
 * 播放和离线缓存可能同时撞墙：只保留第一条，登录成功后按它决定重试什么，
 * 避免多个失败各自弹一次框、或重试到用户没在等的任务上。
 */
class AuthEventsTest {

    @After
    fun tearDown() {
        AuthEvents.dismissLogin()
    }

    @Test
    fun `默认请求重试当前播放`() {
        AuthEvents.requestLogin()

        assertEquals(
            AuthEvents.Request(AuthEvents.Retry.CURRENT_PLAYBACK),
            AuthEvents.loginRequired.value
        )
    }

    @Test
    fun `离线缓存撞墙带上要重试的期号`() {
        AuthEvents.requestLogin(AuthEvents.Retry.DOWNLOAD, episodeId = 22911)

        assertEquals(
            AuthEvents.Request(AuthEvents.Retry.DOWNLOAD, 22911),
            AuthEvents.loginRequired.value
        )
    }

    @Test
    fun `浏览类页面撞墙不需要重试任务`() {
        AuthEvents.requestLogin(AuthEvents.Retry.NONE)

        assertEquals(
            AuthEvents.Request(AuthEvents.Retry.NONE),
            AuthEvents.loginRequired.value
        )
    }

    @Test
    fun `框还开着时后续撞墙不覆盖第一条`() {
        AuthEvents.requestLogin(AuthEvents.Retry.DOWNLOAD, episodeId = 1)
        AuthEvents.requestLogin()

        assertEquals(
            AuthEvents.Request(AuthEvents.Retry.DOWNLOAD, 1),
            AuthEvents.loginRequired.value
        )
    }

    @Test
    fun `关闭后可以再次发起`() {
        AuthEvents.requestLogin()
        AuthEvents.dismissLogin()

        assertNull(AuthEvents.loginRequired.value)

        AuthEvents.requestLogin(AuthEvents.Retry.DOWNLOAD, episodeId = 2)
        assertEquals(
            AuthEvents.Request(AuthEvents.Retry.DOWNLOAD, 2),
            AuthEvents.loginRequired.value
        )
    }
}
