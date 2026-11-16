package cn.loveq.podcast.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话 Cookie 的收发范围：只给需要登录的官网请求带，列表页和第三方域名不带。
 *
 * 列表页如果误带 Cookie，会把匿名浏览行为绑到账号上；
 * 播放链接如果漏带，会被官网 302 到登录页，用户看到"解析失败"。
 */
class SessionRequestTest {

    private val site = "www.loveq.cn"

    @Test
    fun `列表页不带 Cookie`() {
        assertFalse(isSessionRequest(site, "/program.html"))
        // 带查询参数不影响：encodedPath 不含 query，仍是 /program.html
        assertFalse(isSessionRequest(site, "/program.html"))
    }

    @Test
    fun `播放链接带 Cookie`() {
        assertTrue(isSessionRequest(site, "/program_download-12345.html"))
    }

    @Test
    fun `登录与验证码相关页带 Cookie`() {
        assertTrue(isSessionRequest(site, "/user-login.html"))
        assertTrue(isSessionRequest(site, "/user.php"))
        assertTrue(isSessionRequest(site, "/get_img.php"))
    }

    @Test
    fun `program_download 不被列表规则误伤`() {
        // 回归防线：曾用 contains("program") 判断，会把播放链接一起排除
        assertTrue(isSessionRequest(site, "/program_download-1.html"))
    }

    @Test
    fun `第三方域名不带 Cookie`() {
        assertFalse(isSessionRequest("api.github.com", "/repos/pigstrong9/LoveqPodcast"))
        assertFalse(isSessionRequest("gh-proxy.com", "/anything"))
        assertFalse(isSessionRequest("example.loveq.cn.evil.com", "/program_download-1.html"))
    }

    @Test
    fun `官网子域名带 Cookie`() {
        assertTrue(isSessionRequest("loveq.cn", "/program_download-1.html"))
        assertTrue(isSessionRequest("m.loveq.cn", "/user.php"))
    }
}
