package cn.loveq.podcast.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官网 `user.php?act=signin` 的返回解析。
 *
 * 登录成功与否只看 `error == 0`；`captcha == 1` 时界面要切到验证码输入。
 * 把这两个字段读错，用户会看到「登录失败」或者永远等不到验证码。
 */
class LoginReplyTest {

    @Test
    fun `error 为 0 判定成功`() {
        val reply = LoginReply.parse("""{"error":0,"message":"登录成功","captcha":0}""")

        assertEquals(0, reply.error)
        assertEquals("登录成功", reply.message)
        assertFalse(reply.captcha)
    }

    @Test
    fun `captcha 为 1 时需要验证码`() {
        val reply = LoginReply.parse("""{"error":1,"message":"请输入验证码","captcha":1}""")

        assertTrue(reply.captcha)
        assertEquals("请输入验证码", reply.message)
    }

    @Test
    fun `缺字段时给出安全默认值`() {
        val reply = LoginReply.parse("{}")

        assertEquals(-1, reply.error)
        assertEquals("", reply.message)
        assertFalse(reply.captcha)
    }

    @Test
    fun `非 JSON 响应抛给调用方处理`() {
        assertThrows(JSONException::class.java) {
            LoginReply.parse("<html>bad gateway</html>")
        }
    }
}
