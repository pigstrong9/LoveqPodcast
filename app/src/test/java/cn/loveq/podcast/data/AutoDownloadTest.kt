package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 启动自动下载的选集规则。
 *
 * 区间是「当前那期 → 最新」（列表新→旧，往 0 走就是更新），已缓存/已排队的跳过，
 * 当前期不在表里就跳过本次。纯函数单测把这几条边界钉死。
 */
class AutoDownloadTest {

    private fun ep(id: Long) = Episode(id, "2026.01.01", "t$id")

    @Test
    fun `从当前期到最新——收集所有未缓存的`() {
        val list = listOf(ep(5), ep(4), ep(3), ep(2), ep(1))
        assertEquals(listOf(ep(3), ep(4), ep(5)), autoDownloadTargets(list, 3, emptySet(), 5))
    }

    @Test
    fun `当前期和更新的期里，已缓存与排队中的都跳过`() {
        val list = listOf(ep(5), ep(4), ep(3), ep(2), ep(1))
        // 3 已缓存、4 在排队：只剩 5
        assertEquals(listOf(ep(5)), autoDownloadTargets(list, 3, setOf(3, 4), 5))
    }

    @Test
    fun `超过上限就截断，优先离当前期最近的几期`() {
        val list = listOf(ep(5), ep(4), ep(3), ep(2), ep(1))
        assertEquals(listOf(ep(3), ep(4)), autoDownloadTargets(list, 3, emptySet(), 2))
    }

    @Test
    fun `当前期不在表里——分类不匹配或定位不到，跳过本次`() {
        val list = listOf(ep(5), ep(4), ep(3))
        assertEquals(emptyList<Episode>(), autoDownloadTargets(list, 99, emptySet(), 5))
    }

    @Test
    fun `当前期已经是最新——没有更新的可下`() {
        val list = listOf(ep(5), ep(4), ep(3))
        assertEquals(listOf(ep(5)), autoDownloadTargets(list, 5, emptySet(), 5))
    }

    @Test
    fun `当前期未缓存也纳入，已缓存则不重复排`() {
        val list = listOf(ep(5), ep(4), ep(3))
        assertEquals(listOf(ep(3), ep(4), ep(5)), autoDownloadTargets(list, 3, emptySet(), 5))
        assertEquals(listOf(ep(4), ep(5)), autoDownloadTargets(list, 3, setOf(3), 5))
    }

    @Test
    fun `上限为零或负数——什么都不下`() {
        val list = listOf(ep(5), ep(4), ep(3))
        assertEquals(emptyList<Episode>(), autoDownloadTargets(list, 3, emptySet(), 0))
        assertEquals(emptyList<Episode>(), autoDownloadTargets(list, 3, emptySet(), -1))
    }
}
