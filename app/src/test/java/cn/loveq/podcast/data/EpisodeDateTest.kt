package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日期解析是「按年/月定位」的地基：jumpToDate 用 year 或 yearMonth 作为
 * 「随页码单调不增」的排序键做二分查找，键算错就会跳到错的年份或者二分收敛不了。
 * 官网日期是 `2026.10.12` 这种点分格式，个别条目会缺字段。
 */
class EpisodeDateTest {

    private fun ep(date: String) = Episode(1L, date, "标题")

    @Test
    fun `点分日期解析出年和月`() {
        val e = ep("2026.10.12")
        assertEquals(2026, e.year)
        assertEquals(10, e.month)
        assertEquals(202_610, e.yearMonth)
    }

    @Test
    fun `月份是一位数时也要解析出来，否则一月会被当成无月`() {
        assertEquals(1, ep("2026.1.05").month)
        assertEquals(202_601, ep("2026.1.05").yearMonth)
    }

    @Test
    fun `缺字段或脏数据时给 0 而不是抛异常`() {
        assertEquals(0, ep("").year)
        assertEquals(0, ep("").month)
        assertEquals(0, ep("2026").month)
        assertEquals(0, ep("待定").year)
    }

    @Test
    fun `yearMonth 在跨年时仍然单调，可以直接当二分查找的键`() {
        val dates = listOf("2026.10.12", "2026.01.05", "2025.12.31", "2003.06.15")
        val keys = dates.map { ep(it).yearMonth }
        assertTrue("必须严格递减：$keys", keys.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun `整年筛选用 year 做键时跨年同样单调`() {
        val keys = listOf("2026.01.01", "2025.12.31", "2003.06.15").map { ep(it).year }
        assertTrue(keys.zipWithNext().all { (a, b) -> a > b })
    }
}
