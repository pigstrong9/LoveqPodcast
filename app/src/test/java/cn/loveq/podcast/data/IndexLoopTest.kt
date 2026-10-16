package cn.loveq.podcast.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推进循环的语义。
 *
 * 测的不是「循环能转」，而是三条容易被改坏的约定：**续抓不改变发起者的语义**、
 * 没事就收手、预期外异常不许冒泡（冒到 launch 外面在 Android 上就是进程崩溃）。
 */
class IndexLoopTest {

    private val modes = mutableListOf<Boolean>()
    private val sleeps = mutableListOf<Long>()

    /** 按脚本返回每一轮的等待时长；脚本用完就表示「没事可做了」 */
    private fun scripted(vararg waits: Long): suspend (Boolean) -> Long? {
        var i = 0
        return { mode ->
            modes += mode
            if (i < waits.size) waits[i++] else null
        }
    }

    @Test
    fun `冷却等待之后仍按下拉刷新的语义继续，不会被降级成启动刷新`() = runBlocking {
        runIndexLoop(
            respectTtl = false,
            advance = scripted(1_000L),
            sleep = { sleeps += it }
        )
        // 上一版这里是 [false, true] —— 第二轮变成启动语义，
        // 索引完整且新鲜时直接判定「无事可做」，用户的下拉刷新永远不会真的发生
        assertEquals(listOf(false, false), modes)
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun `启动语义同样一路保持，不会被改写`() = runBlocking {
        runIndexLoop(respectTtl = true, advance = scripted(500L, 500L), sleep = { sleeps += it })
        assertEquals(listOf(true, true, true), modes)
    }

    @Test
    fun `没事可做就收手，一次也不等`() = runBlocking {
        runIndexLoop(respectTtl = true, advance = scripted(), sleep = { sleeps += it })
        assertEquals(1, modes.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `连续冷却会一直等下去，直到推进成功`() = runBlocking {
        runIndexLoop(
            respectTtl = true,
            advance = scripted(30_000L, 60_000L, 120_000L),
            sleep = { sleeps += it }
        )
        assertEquals(listOf(30_000L, 60_000L, 120_000L), sleeps)
        assertEquals(4, modes.size)
    }

    @Test
    fun `预期外异常就地收手，不许冒泡到未捕获处理器`() = runBlocking {
        runIndexLoop(
            respectTtl = true,
            advance = { mode -> modes += mode; throw IllegalStateException("解析器炸了") },
            sleep = { sleeps += it }
        )
        // runBlocking 没被打断，就说明异常没有冒出去
        assertEquals(1, modes.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `取消异常照常抛出，不能被当成普通失败吞掉`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runIndexLoop(
                    respectTtl = true,
                    advance = { throw CancellationException("用户离开了") },
                    sleep = { }
                )
            }
        }
    }
}
