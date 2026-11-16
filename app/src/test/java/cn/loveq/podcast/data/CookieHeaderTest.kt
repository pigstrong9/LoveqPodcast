package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话 Cookie 的合并规则。
 *
 * 官网登录成功只靠服务端下发的 `LQ_ID`，这里一旦把 Cookie 弄丢或合并错，
 * 现象就是「登录显示成功，但播放仍被 302」—— 极难从日志看出来，必须有单测兜着。
 */
class CookieHeaderTest {

    @Test
    fun `空会话直接收下 Set-Cookie 的 name=value`() {
        val merged = CookieHeader.merge(
            existing = null,
            setCookies = listOf("LQ_ID=abc123; path=/; domain=.loveq.cn; HttpOnly")
        )

        assertEquals("LQ_ID=abc123", merged)
    }

    @Test
    fun `同名 Cookie 后到的覆盖先到的`() {
        val merged = CookieHeader.merge(
            existing = "LQ_ID=old; other=keep",
            setCookies = listOf("LQ_ID=new; path=/")
        )

        assertEquals("LQ_ID=new; other=keep", merged)
    }

    @Test
    fun `空值 deleted 和 Max-Age=0 都按删除处理`() {
        val existing = "a=1; b=2; c=3"

        assertEquals(
            "b=2; c=3",
            CookieHeader.merge(existing, listOf("a="))
        )
        assertEquals(
            "a=1; c=3",
            CookieHeader.merge(existing, listOf("b=deleted; path=/"))
        )
        assertEquals(
            "a=1; b=2",
            CookieHeader.merge(existing, listOf("c=3; Max-Age=0"))
        )
    }

    @Test
    fun `没有等号的坏条目被忽略`() {
        val merged = CookieHeader.merge(
            existing = "a=1",
            setCookies = listOf("novalue", "=x", "b=2")
        )

        assertEquals("a=1; b=2", merged)
    }
}
