package cn.loveq.podcast.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 睡眠定时状态机的纯逻辑。
 *
 * generation 是服务端重启倒计时的依据：同值重选（剩 5 分钟时再点 30）时
 * minutes 不变，全靠 generation 自增触发重启——这是「重复选同一档位不生效」
 * 缺陷的回归测试。SleepTimer 是进程级单例，代数断言全部用相对值。
 */
class SleepTimerTest {

    @Test
    fun `设定档位重置剩余时间并递增代数`() {
        val before = SleepTimer.state.value.generation
        SleepTimer.setMinutes(30)
        val state = SleepTimer.state.value
        assertEquals(30, state.minutes)
        assertEquals(30 * 60_000L, state.remainingMs)
        assertEquals(before + 1, state.generation)
    }

    @Test
    fun `重复设定同一档位也要递增代数——服务端据此重启倒计时`() {
        SleepTimer.setMinutes(30)
        val first = SleepTimer.state.value.generation
        SleepTimer.setMinutes(30)
        val second = SleepTimer.state.value
        assertEquals(first + 1, second.generation)
        // 剩余时间重新回到满额
        assertEquals(30 * 60_000L, second.remainingMs)
    }

    @Test
    fun `关闭档位清空剩余并递增代数`() {
        val before = SleepTimer.state.value.generation
        SleepTimer.setMinutes(30)
        SleepTimer.setMinutes(null)
        val state = SleepTimer.state.value
        assertEquals(null, state.minutes)
        assertEquals(0L, state.remainingMs)
        assertEquals(before + 2, state.generation)
    }

    @Test
    fun `tick只刷新剩余时间不动代数——服务端循环不会被打断`() {
        SleepTimer.setMinutes(30)
        val generation = SleepTimer.state.value.generation
        SleepTimer.tick(29 * 60_000L)
        val state = SleepTimer.state.value
        assertEquals(29 * 60_000L, state.remainingMs)
        assertEquals(generation, state.generation)
    }

    @Test
    fun `到点清空倒计时不影响本集结束开关`() {
        SleepTimer.setStopAtEpisodeEnd(true)
        SleepTimer.finishCountdown()
        val state = SleepTimer.state.value
        assertEquals(null, state.minutes)
        assertTrue(state.stopAtEpisodeEnd)
    }

    @Test
    fun `本集结束开关是一次性的`() {
        SleepTimer.setStopAtEpisodeEnd(true)
        assertTrue(SleepTimer.consumeStopAtEpisodeEnd())
        assertFalse(SleepTimer.state.value.stopAtEpisodeEnd)
        assertFalse(SleepTimer.consumeStopAtEpisodeEnd())
    }
}
