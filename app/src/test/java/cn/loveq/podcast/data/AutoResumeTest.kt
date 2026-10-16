package cn.loveq.podcast.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动自动续播的闸门。
 *
 * 这是唯一一处「没人操作就自己出声」的地方，所以判断要抠清楚：
 * 关着的时候绝不能出声；上次那期已经听完也不能接 —— 服务对听完的一期是从 0 重新播，
 * 每次点火都把上一期从头再放一遍，用户会觉得「它自己乱放」。
 */
class AutoResumeTest {

    private fun progress(id: Long, done: Boolean) = Progress(
        id = id,
        positionMs = if (done) 3_600_000L else 600_000L,
        durationMs = 3_600_000L,
        done = done,
        title = "t$id",
        date = "2026.01.01",
        updatedAt = 1_000L
    )

    @Test
    fun `开关打开且上次那期没听完——续播`() {
        assertTrue(shouldAutoResume(enabled = true, progress = progress(1, done = false)))
    }

    @Test
    fun `开关关着绝不出声——即使有没听完的记录`() {
        assertFalse(shouldAutoResume(enabled = false, progress = progress(1, done = false)))
    }

    @Test
    fun `上次那期已经听完——不续播，否则每次都从头再放一遍`() {
        assertFalse(shouldAutoResume(enabled = true, progress = progress(1, done = true)))
    }

    @Test
    fun `还没有任何收听记录——不续播`() {
        assertFalse(shouldAutoResume(enabled = true, progress = null))
        assertFalse(shouldAutoResume(enabled = false, progress = null))
    }
}
