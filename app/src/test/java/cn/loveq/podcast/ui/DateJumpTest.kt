package cn.loveq.podcast.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * findTargetPage 决定「跳转到某年/某月」落在哪一页，错了就是「没有找到」或者跳错年份。
 *
 * 用假归档驱动：每年固定若干期，页大小 26，页首键沿页码单调不增 —— 和真实站点一致。
 * guess 一栏专门覆盖「本地索引和网站条目数不一致」的偏移，这是真机踩过的坑：
 * 2023 年能跳、2010 年报「没有找到」。
 */
class DateJumpTest {

    private val pageSize = 26
    private val yearsPerCount = 104  // 每年 104 期 = 4 页

    /** 2026 年排到 2003 年，每年 104 期。返回第 page 页最新一年的年份（0 = 空页） */
    private fun headOf(page: Int, oldest: Int = 2003): Int? {
        val totalYears = 2026 - oldest + 1
        val total = totalYears * yearsPerCount
        val start = (page - 1) * pageSize
        if (start >= total) return null
        val offsetFromNewest = start  // 第 0 条最新
        return 2026 - offsetFromNewest / yearsPerCount
    }

    private fun headKey(oldest: Int = 2003): suspend (Int) -> Int? = { headOf(it, oldest) }

    private val lastPage get() = (2026 - 2003 + 1) * yearsPerCount / pageSize  // 94

    private fun find(target: Int, guess: Int?, oldest: Int = 2003, last: Int = lastPage) =
        runBlocking { findTargetPage(target, 1, last, guess, headKey(oldest)) }

    /** 目标年份的第一期所在页（不含跨页回看），用于对照 */
    private fun expectedPage(target: Int): Int {
        val offsetFromNewest = (2026 - target) * yearsPerCount
        return offsetFromNewest / pageSize + 1
    }

    @Test
    fun `索引准确时 guess 直接命中，省掉二分`() {
        val target = 2020
        val exact = expectedPage(target)
        assertEquals(exact, find(target, guess = exact))
    }

    @Test
    fun `索引偏小导致 guess 估早了也不能漏掉边界页`() {
        val target = 2010
        val exact = expectedPage(target)
        // 本地索引少收几千条 => 换算出的页码明显偏小
        assertEquals(exact, find(target, guess = exact - 9))
    }

    @Test
    fun `索引偏大导致 guess 估晚了也要退回到正确页`() {
        val target = 2015
        val exact = expectedPage(target)
        assertEquals(exact, find(target, guess = exact + 7))
    }

    @Test
    fun `没有索引时纯二分也要落在同一页`() {
        listOf(2026, 2023, 2015, 2010, 2004).forEach { y ->
            assertEquals("$y 年", expectedPage(y), find(y, guess = null))
        }
    }

    @Test
    fun `guess 越界时按边界夹住，不影响结果`() {
        assertEquals(expectedPage(2019), find(2019, guess = -5))
        assertEquals(expectedPage(2019), find(2019, guess = 9999))
    }

    @Test
    fun `目标比最新一期还新时停在第一页，由收集环节判定没有这一期`() {
        // 找不到某一年是「收集阶段一条都没收到」决定的，不是找页决定的；
        // 这里必须返回 1 而不是 0，否则用户比归档最新一期还超前时会被指到不存在的页
        assertEquals(1, find(2035, guess = null))
        assertEquals(1, find(2035, guess = 1))
    }

    @Test
    fun `目标比最老一期还旧时判定为不存在`() {
        assertEquals(0, find(1990, guess = null))
    }

    @Test
    fun `目标是最新那一年时就是第一页`() {
        assertEquals(1, find(2026, guess = 1))
        assertEquals(1, find(2026, guess = null))
    }

    @Test
    fun `目标是最老那一年时能找到`() {
        assertEquals(expectedPage(2003), find(2003, guess = null))
        assertEquals(expectedPage(2003), find(2003, guess = expectedPage(2003)))
    }

    @Test
    fun `归档只有零星几页时也能定位`() {
        // 2026、2025 各 104 期 = 8 页
        val last = 8
        val r = runBlocking { findTargetPage(2025, 1, last, null, headKey(oldest = 2025)) }
        assertEquals(expectedPage(2025), r)
    }

    @Test
    fun `空归档不崩也不死循环`() {
        val r = runBlocking { findTargetPage(2020, 1, 0, guess = 3) { null } }
        assertEquals(0, r)
    }

    @Test
    fun `每月粒度用 yearMonth 键时同样成立`() {
        // 键 = 年*100+月，2026.10 开始往下递减，每月 2 期
        suspend fun key(page: Int): Int? {
            val totalMonths = (2026 - 2003 + 1) * 12
            val start = (page - 1) * pageSize
            if (start >= totalMonths * 2) return null
            val monthsFromNewest = start / 2
            val absolute = 2026 * 12 + 9 - monthsFromNewest
            return (absolute / 12) * 100 + (absolute % 12) + 1
        }
        val target = 2010 * 100 + 3
        val r = runBlocking { findTargetPage(target, 1, 400, null) { key(it) } }
        val first = runBlocking { key(r) }!!
        val prev = runBlocking { if (r == 1) null else key(r - 1) }
        assertEquals("边界页首页必须已不晚于目标", true, first <= target)
        assertEquals("上一页必须还晚于目标", true, prev == null || prev > target)
    }
}
