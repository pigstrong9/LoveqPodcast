package cn.loveq.podcast.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳过空白的纯计算：片头起播位置、片尾可听终点。
 *
 * 两条底线在这里守住：时长未知不跳片尾（别把「没加载出时长」当「播完了」）；
 * 一期短于 2N 分钟整期不跳（首尾各砍 N 分钟会把内容切没）。
 */
class SkipBlankTest {

    private val minute = 60_000L

    @Test
    fun `开关关着不跳片头`() {
        assertEquals(
            minute,
            SkipBlank.startPositionMs(savedPositionMs = minute, enabled = false, minutes = 3)
        )
    }

    @Test
    fun `进度落在片头内——起播跳到 N 分钟`() {
        assertEquals(
            3 * minute,
            SkipBlank.startPositionMs(savedPositionMs = minute, enabled = true, minutes = 3)
        )
    }

    @Test
    fun `进度已过片头——原样保留`() {
        assertEquals(
            10 * minute,
            SkipBlank.startPositionMs(savedPositionMs = 10 * minute, enabled = true, minutes = 3)
        )
    }

    @Test
    fun `没有进度且关着——从 0 开始`() {
        assertEquals(0L, SkipBlank.startPositionMs(0L, enabled = false, minutes = 3))
        assertEquals(0L, SkipBlank.startPositionMs(-5L, enabled = true, minutes = 3))
    }

    @Test
    fun `没有进度但开着——跳过片头`() {
        assertEquals(
            3 * minute,
            SkipBlank.startPositionMs(savedPositionMs = 0L, enabled = true, minutes = 3)
        )
    }

    @Test
    fun `时长未知不跳片尾`() {
        assertEquals(0L, SkipBlank.endPositionMs(durationMs = 0L, enabled = true, minutes = 3))
        assertEquals(0L, SkipBlank.endPositionMs(durationMs = -1L, enabled = true, minutes = 3))
    }

    @Test
    fun `一期短于首尾各 N 分钟——整期不跳`() {
        // 6 分钟恰好等于 2N，再砍就没了
        assertEquals(0L, SkipBlank.endPositionMs(6 * minute, enabled = true, minutes = 3))
        assertEquals(0L, SkipBlank.endPositionMs(5 * minute, enabled = true, minutes = 3))
    }

    @Test
    fun `正常长度——终点是总时长减 N 分钟`() {
        assertEquals(
            57 * minute,
            SkipBlank.endPositionMs(60 * minute, enabled = true, minutes = 3)
        )
    }

    @Test
    fun `档位越界按 2 到 5 分钟钳制`() {
        // 传 1 当 2 算、传 99 当 5 算，防御性兜底
        assertEquals(
            58 * minute,
            SkipBlank.endPositionMs(60 * minute, enabled = true, minutes = 1)
        )
        assertEquals(
            55 * minute,
            SkipBlank.endPositionMs(60 * minute, enabled = true, minutes = 99)
        )
    }

    @Test
    fun `endPosition 为 0 时永不视为到达终点`() {
        assertFalse(SkipBlank.reachedEnd(positionMs = 999 * minute, endPositionMs = 0L))
    }

    @Test
    fun `到达或超过可听终点即结束`() {
        val end = 57 * minute
        assertFalse(SkipBlank.reachedEnd(end - 1, end))
        assertTrue(SkipBlank.reachedEnd(end, end))
        assertTrue(SkipBlank.reachedEnd(end + minute, end))
    }
}
