package cn.loveq.podcast.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动自动缓存等待索引刷新的代数判定。
 *
 * 自动缓存要等本次强制增量刷新推进过一轮再读索引，才能感知官网新发的节目。
 * 判定条件写反会导致两种 bug：读到昨天的旧索引（漏下新集），或永远等不到白耗超时。
 */
class IndexRefreshSignalTest {

    @Test
    fun `代数刚好达到目标——判定为已刷新`() {
        assertTrue(advanceReachedEpoch(current = 1, target = 1))
    }

    @Test
    fun `代数超过目标——判定为已刷新`() {
        assertTrue(advanceReachedEpoch(current = 3, target = 1))
    }

    @Test
    fun `代数还没到——继续等`() {
        assertFalse(advanceReachedEpoch(current = 0, target = 1))
        assertFalse(advanceReachedEpoch(current = 1, target = 2))
    }

    @Test
    fun `全新安装从零发起刷新——目标是发起前的代数加一`() {
        // ViewModel 在 refresh 前记下代数 0，传 0+1=1 给等待方；
        // 第一轮推进后代数变 1，条件成立，自动缓存这才读索引
        val epochBeforeRefresh = 0L
        val target = epochBeforeRefresh + 1
        assertFalse(advanceReachedEpoch(current = epochBeforeRefresh, target = target))
        assertTrue(advanceReachedEpoch(current = epochBeforeRefresh + 1, target = target))
    }
}
