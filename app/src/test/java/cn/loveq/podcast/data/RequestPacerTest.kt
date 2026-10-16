package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局闸门的排程。
 *
 * 它保证的是「任何调用路径叠加起来也不超过 2 请求/秒」，而这里最容易写错的两处
 * 都是**安静的**：空闲时白等（用户点播放要干等半秒），以及两个调用者拿到同一个槽
 * （看起来在限速，实际上限失效）。
 */
class RequestPacerTest {

    private val interval = RequestPacer.MIN_REQUEST_INTERVAL_MS

    @Test
    fun `空闲时立刻可发，不白等`() {
        assertEquals(0L, RequestPacer().reserve(1_000L))
    }

    @Test
    fun `连续预约严格按最小间隔排开`() {
        val pacer = RequestPacer()
        val now = 10_000L
        assertEquals(0L, pacer.reserve(now))
        assertEquals(interval, pacer.reserve(now))
        assertEquals(interval * 2, pacer.reserve(now))
    }

    @Test
    fun `同一瞬间的突发里，第 i 个调用者拿到第 i 个槽而不是同一个槽`() {
        val pacer = RequestPacer()
        val waits = List(20) { pacer.reserve(0L) }
        // 若某次忘了在旧槽上累加，这里就会出现两个相等的等待值
        assertEquals(List(20) { it.toLong() * interval }, waits)
    }

    @Test
    fun `时间过去之后不再为上一次的排程补等`() {
        val pacer = RequestPacer()
        pacer.reserve(0L)
        pacer.reserve(0L)
        // 隔了 5 秒才来的请求：闸门早就空了
        assertEquals(0L, pacer.reserve(5_000L))
    }

    @Test
    fun `永不返回负等待`() {
        // delay() 收到负数会直接抛 IllegalArgumentException，所以这条不是理论洁癖
        val pacer = RequestPacer()
        assertTrue(pacer.reserve(0L) >= 0L)
        assertTrue(pacer.reserve(0L) >= 0L)
        assertTrue(pacer.reserve(Long.MAX_VALUE / 2) >= 0L)
    }
}
