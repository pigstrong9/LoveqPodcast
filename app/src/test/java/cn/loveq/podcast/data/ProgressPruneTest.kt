package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度历史的裁剪规则。车机连开几年，done_/time_/meta_ 这些 key 只会越攒越多，
 * 裁错了要么白裁（历史突然变少），要么把「继续收听」指向的那期裁没了。
 */
class ProgressPruneTest {

    private var clock = 1_000L

    private fun done(id: Long): Progress {
        clock += 1_000
        return Progress(
            id = id,
            positionMs = 100,
            durationMs = 100,
            done = true,
            title = "t$id",
            date = "2026.01.01",
            updatedAt = clock
        )
    }

    private fun listening(id: Long): Progress {
        clock += 1_000
        return Progress(
            id = id,
            positionMs = 10,
            durationMs = 100,
            done = false,
            title = "t$id",
            date = "2026.01.01",
            updatedAt = clock
        )
    }

    @Test
    fun `没超过上限时什么都不裁`() {
        val items = (1..10).map { done(it.toLong()) }
        assertTrue(progressIdsToPrune(items, maxDone = 10, keepId = null).isEmpty())
        assertTrue(progressIdsToPrune(items, maxDone = 20, keepId = null).isEmpty())
    }

    @Test
    fun `超过上限只裁最旧的听完记录`() {
        // updatedAt 递增 => id=1 最旧
        val items = (1..5).map { done(it.toLong()) }
        assertEquals(setOf(1L, 2L, 3L), progressIdsToPrune(items, maxDone = 2, keepId = null))
    }

    @Test
    fun `在听的记录一律保留，不占用听完名额`() {
        val items = listOf(listening(1), listening(2)) + (3..6).map { done(it.toLong()) }
        // 只留最近 1 条听完（id=6）；在听的 1、2 一条都不能动
        assertEquals(setOf(3L, 4L, 5L), progressIdsToPrune(items, maxDone = 1, keepId = null))
    }

    @Test
    fun `正在收听的那一期即使很旧也不裁`() {
        val items = (1..4).map { done(it.toLong()) }
        // keepId=1 是最旧的一条，正常会被裁掉，但它是当前正在听的
        assertEquals(setOf(2L, 3L), progressIdsToPrune(items, maxDone = 1, keepId = 1L))
    }

    @Test
    fun `空表不崩`() {
        assertTrue(progressIdsToPrune(emptyList(), maxDone = 0, keepId = null).isEmpty())
    }
}
