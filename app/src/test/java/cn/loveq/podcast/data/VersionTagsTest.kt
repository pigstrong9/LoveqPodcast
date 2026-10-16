package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本名的解析与比较。
 *
 * 这里写错的方式都是安静的：用字符串比较会让 1.10 被当成比 1.9 旧（字典序），
 * 结果是发新版用户却收不到提示；把不可比的 tag 当成可解析，则可能把人引到一个
 * 来路不明的版本上。所以宁可判「不可比、不提示」，也不猜。
 */
class VersionTagsTest {

    @Test
    fun `v 前缀和各种写法都能解析`() {
        assertEquals(listOf(1, 5), VersionTags.parse("v1.5"))
        assertEquals(listOf(1, 5), VersionTags.parse("V1.5"))
        assertEquals(listOf(1, 5), VersionTags.parse("1.5"))
        assertEquals(listOf(1, 5), VersionTags.parse("  v1.5  "))
        assertEquals(listOf(1, 0, 0), VersionTags.parse("v1.0.0"))
    }

    @Test
    fun `10 大于 9 而不是按字典序`() {
        // 字典序下 "1.10" < "1.9"，这条就是为了钉死这个坑
        assertTrue(VersionTags.isNewer("v1.10", "1.9"))
        assertFalse(VersionTags.isNewer("v1.9", "1.10"))
    }

    @Test
    fun `1_5_0 与 1_5 视为相同`() {
        assertFalse(VersionTags.isNewer("v1.5.0", "1.5"))
        assertFalse(VersionTags.isNewer("v1.5", "1.5.0"))
    }

    @Test
    fun `更高版本判定为更新`() {
        assertTrue(VersionTags.isNewer("v1.6", "1.5"))
        assertTrue(VersionTags.isNewer("v2.0", "1.9.9"))
    }

    @Test
    fun `更低版本不判定为更新`() {
        assertFalse(VersionTags.isNewer("v1.4", "1.5"))
        assertFalse(VersionTags.isNewer("v1.5", "1.5"))
    }

    @Test
    fun `长的纯数字版本号照样能解析`() {
        // 段数多不是错误，不能因为「看着不像版本号」就判为不可比
        assertEquals(listOf(1, 5, 1, 2, 3), VersionTags.parse("1.5.1.2.3"))
    }

    @Test
    fun `不可比的 tag 一律解析不出`() {
        assertNull(VersionTags.parse("v1.5-beta"))
        assertNull(VersionTags.parse(""))
        assertNull(VersionTags.parse("v"))
        assertNull(VersionTags.parse("vv1.5"))
        assertNull(VersionTags.parse("v1..5"))
        assertNull(VersionTags.parse("latest"))
    }

    @Test
    fun `任一侧不可比都不提示升级`() {
        // 宁可漏一次提示，也不能按猜测去升
        assertFalse(VersionTags.isNewer("v1.5-beta", "1.5"))
        assertFalse(VersionTags.isNewer("v1.6", "whatever"))
    }
}
