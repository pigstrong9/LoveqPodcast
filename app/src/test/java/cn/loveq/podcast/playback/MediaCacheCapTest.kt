package cn.loveq.podcast.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收听缓存上限的取值。车机/平板/手机存储差别很大，
 * 算错只有两个后果：小存储设备被撑爆，或大存储设备上缓存小得可怜。
 */
class MediaCacheCapTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `可用空间充足时封顶 2GB`() {
        assertEquals(MediaCache.HARD_CAP, cacheCapBytes(100 * gb))
        assertEquals(MediaCache.HARD_CAP, cacheCapBytes(8 * gb)) // 8/4 恰好等于上限
    }

    @Test
    fun `可用空间紧张时取四分之一`() {
        assertEquals(512 * mb, cacheCapBytes(2 * gb))
        assertEquals(250 * mb, cacheCapBytes(1000 * mb))
    }

    @Test
    fun `取不到可用空间时退回硬上限而不是 0`() {
        assertEquals(MediaCache.HARD_CAP, cacheCapBytes(0L))
        assertEquals(MediaCache.HARD_CAP, cacheCapBytes(-1L))
    }

    @Test
    fun `任何输入都不超过硬上限`() {
        for (free in listOf(0L, mb, gb, 7 * gb, 9 * gb, 1000 * gb)) {
            assertTrue("free=$free", cacheCapBytes(free) <= MediaCache.HARD_CAP)
        }
    }
}
