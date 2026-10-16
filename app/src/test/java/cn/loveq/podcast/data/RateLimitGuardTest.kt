package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 限流冷却的退避阶梯、跨重启语义，以及「冷却不会被在途的成功抹掉」。
 *
 * 这一块是「别把自己反复钉在封禁里」的全部保障，弄错的方式都很隐蔽：
 * 档位不升、重启后忘了冷却、被一个在途的成功把刚落的冷却撤掉 ——
 * 三种都表现为「应用偷偷继续敲官网」。
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

    /** 默认每次都新建一份存储：多数用例只关心单个实例内的行为 */
    private fun guard(store: RateLimitStore? = MemoryStore()) = RateLimitGuard(store) { now }

    private val minute = 60_000L

    @Test
    fun `没被拒过就不冷却`() {
        val g = guard()
        assertFalse(g.isBlocked())
        assertEquals(0L, g.blockedForMs())
        assertEquals("", g.describeRemaining())
    }

    @Test
    fun `第一次被拒冷却 30 分钟`() {
        val g = guard()
        assertEquals(30 * minute, g.onRateLimited())
        assertTrue(g.isBlocked())
        assertEquals(30 * minute, g.blockedForMs())
    }

    @Test
    fun `冷却到点自动解除，不需要谁去清`() {
        val g = guard()
        g.onRateLimited()
        now += 30 * minute
        assertFalse(g.isBlocked())
        assertEquals(0L, g.blockedForMs())
    }

    @Test
    fun `连续被拒逐级升级，最高 6 小时`() {
        val g = guard()
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
        val g = guard()
        g.onRateLimited()
        now += 25 * minute
        // 还剩 5 分钟时又被拒：新期限是「现在 + 60 分钟」，不是「原期限 + 60 分钟」
        assertEquals(60 * minute, g.onRateLimited())
        assertEquals(60 * minute, g.blockedForMs())
    }

    /**
     * 这次修复的核心。冷却期内到达的成功，只可能来自冷却开始前就已经在途的请求
     * （冷却期内根本不发请求），它不能撤销刚记下的拒绝 ——
     * 否则慢网 + 抓取期间常态并发的场景下会变成 405 → 成功 → 再发 → 405 的节奏。
     */
    @Test
    fun `冷却期内的成功不解除冷却`() {
        val g = guard()
        g.onRateLimited()
        g.onRateLimited() // 停在 60 分钟档
        now += 100L       // 在途的那个请求 100ms 后才成功返回
        g.onSuccess()
        assertTrue(g.isBlocked())
        assertEquals(60 * minute - 100L, g.blockedForMs())
    }

    @Test
    fun `冷却过后第一次成功才清档位，之后再被拒从最短档重来`() {
        val g = guard()
        g.onRateLimited()
        g.onRateLimited() // 60 分钟
        now += 60 * minute
        assertFalse(g.isBlocked())
        g.onSuccess()
        // 档位归零：偶发的一次拒绝不该让用户背上 1 小时冷却
        assertEquals(30 * minute, g.onRateLimited())
    }

    @Test
    fun `冷却跨进程重启仍然有效`() {
        val store = MemoryStore()
        guard(store).onRateLimited()
        guard(store).onRateLimited()

        // 模拟进程被杀后重开：新实例只有落盘的数据可依赖
        val restarted = guard(store)
        assertTrue(restarted.isBlocked())
        assertEquals(60 * minute, restarted.blockedForMs())
        // 档位也要一起活下来，否则重启会把退避打回第一档，等于给冷却开了个后门
        assertEquals(120 * minute, restarted.onRateLimited())
    }

    @Test
    fun `重启后在途请求的成功同样不解除冷却`() {
        val store = MemoryStore()
        guard(store).onRateLimited()
        // 重启后，冷却开始前发出的那个请求才回来
        guard(store).onSuccess()
        assertTrue(guard(store).isBlocked())
    }

    @Test
    fun `冷却过后的成功会落盘，重启后从最短档起步`() {
        val store = MemoryStore()
        guard(store).onRateLimited()
        now += 30 * minute
        guard(store).onSuccess()
        assertFalse(guard(store).isBlocked())
        assertEquals(30 * minute, guard(store).onRateLimited())
    }

    @Test
    fun `同一段冷却只提示一次`() {
        val g = guard()
        g.onRateLimited()
        assertTrue(g.shouldAnnounceCooldown())
        assertFalse(g.shouldAnnounceCooldown())
        // 又被拒、冷却延长 => 是新的一段，可以再说一次
        g.onRateLimited()
        assertTrue(g.shouldAnnounceCooldown())
    }

    @Test
    fun `没在冷却时不做提示`() {
        val g = guard()
        assertFalse(g.shouldAnnounceCooldown())
        g.onRateLimited()
        assertTrue(g.shouldAnnounceCooldown())
        now += 30 * minute
        assertFalse(g.shouldAnnounceCooldown())
    }

    @Test
    fun `剩余时间文案`() {
        val a = guard()
        a.onRateLimited()
        assertEquals("30 分钟", a.describeRemaining())

        val b = guard()
        b.onRateLimited()
        now += 10 * minute
        assertEquals("20 分钟", b.describeRemaining())

        val c = guard()
        c.onRateLimited()
        c.onRateLimited() // 60 分钟
        assertEquals("1 小时", c.describeRemaining())

        val d = guard()
        repeat(3) { d.onRateLimited() } // 120 分钟
        assertEquals("2 小时", d.describeRemaining())

        val e = guard()
        repeat(3) { e.onRateLimited() } // 120 分钟
        now += 30 * minute
        assertEquals("1 小时 30 分钟", e.describeRemaining())
    }
}
