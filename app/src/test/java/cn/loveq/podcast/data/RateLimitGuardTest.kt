package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 限流冷却的退避阶梯与跨重启语义。
 *
 * 这一块是「别把自己反复钉在封禁里」的全部保障，弄错的方式很隐蔽：
 * 档位不升、成功不清零、重启后忘了冷却 —— 三种都表现为「应用偷偷继续敲官网」。
 */
class RateLimitGuardTest {

    private class MemoryStore : RateLimitStore {
        private val map = mutableMapOf<String, Long>()
        override fun readLong(key: String, def: Long): Long = map[key] ?: def
        override fun writeLong(key: String, value: Long) {
            map[key] = value
        }
    }

    private var now = 1_700_000_000_000L

    private fun guard(store: RateLimitStore?) = RateLimitGuard(store) { now }

    private val minute = 60_000L

    @Test
    fun `没被拒过就不冷却`() {
        val g = guard(MemoryStore())
        assertFalse(g.isBlocked())
        assertEquals(0L, g.blockedForMs())
        assertEquals("", g.describeRemaining())
    }

    @Test
    fun `第一次被拒冷却 30 分钟`() {
        val g = guard(MemoryStore())
        assertEquals(30 * minute, g.onRateLimited())
        assertTrue(g.isBlocked())
        assertEquals(30 * minute, g.blockedForMs())
    }

    @Test
    fun `冷却到点自动解除，不需要谁去清`() {
        val g = guard(MemoryStore())
        g.onRateLimited()
        now += 30 * minute
        assertFalse(g.isBlocked())
        assertEquals(0L, g.blockedForMs())
    }

    @Test
    fun `连续被拒逐级升级，最高 6 小时`() {
        val g = guard(MemoryStore())
        assertEquals(30 * minute, g.onRateLimited())
        assertEquals(60 * minute, g.onRateLimited())
        assertEquals(120 * minute, g.onRateLimited())
        assertEquals(240 * minute, g.onRateLimited())
        assertEquals(360 * minute, g.onRateLimited())
        // 封顶：再被拒也不会一路涨到几天
        assertEquals(360 * minute, g.onRateLimited())
        assertEquals(360 * minute, g.onRateLimited())
    }

    @Test
    fun `重新被拒会从当前时刻重新计时，而不是在旧期限上叠加`() {
        val g = guard(MemoryStore())
        g.onRateLimited()
        now += 25 * minute
        // 还剩 5 分钟时又被拒：新期限是「现在 + 60 分钟」，不是「原期限 + 60 分钟」
        assertEquals(60 * minute, g.onRateLimited())
        assertEquals(60 * minute, g.blockedForMs())
    }

    @Test
    fun `一次成功就清零，之后再被拒从最短档重来`() {
        val g = guard(MemoryStore())
        g.onRateLimited()
        g.onRateLimited()
        g.onSuccess()
        assertFalse(g.isBlocked())
        // 档位也归零：偶发的一次拒绝不该让用户背上 2 小时的冷却
        assertEquals(30 * minute, g.onRateLimited())
    }

    @Test
    fun `冷却跨进程重启仍然有效`() {
        val store = MemoryStore()
        val first = guard(store)
        first.onRateLimited()
        first.onRateLimited()

        // 模拟进程被杀后重开：新实例只有落盘的数据可依赖
        val restarted = guard(store)
        assertTrue(restarted.isBlocked())
        assertEquals(60 * minute, restarted.blockedForMs())
        // 档位也要一起活下来，否则重启会把退避打回第一档，等于给了个绕过冷却的后门
        assertEquals(120 * minute, restarted.onRateLimited())
    }

    @Test
    fun `成功状态落盘后重启不会又变成冷却中`() {
        val store = MemoryStore()
        guard(store).onRateLimited()
        guard(store).onSuccess()
        assertFalse(guard(store).isBlocked())
    }

    @Test
    fun `剩余时间文案`() {
        val g = guard(MemoryStore())
        g.onRateLimited()
        assertEquals("30 分钟", g.describeRemaining())

        now += 10 * minute
        assertEquals("20 分钟", g.describeRemaining())

        g.onSuccess()
        g.onRateLimited() // 30 分钟
        g.onRateLimited() // 60 分钟
        assertEquals("1 小时", g.describeRemaining())

        g.onSuccess()
        g.onRateLimited() // 30
        g.onRateLimited() // 60
        g.onRateLimited() // 120
        assertEquals("2 小时", g.describeRemaining())

        g.onSuccess()
        g.onRateLimited()
        g.onRateLimited()
        g.onRateLimited() // 120 分钟
        now += 30 * minute
        assertEquals("1 小时 30 分钟", g.describeRemaining())
    }
}
