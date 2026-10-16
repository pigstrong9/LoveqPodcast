package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像源的轮换。
 *
 * 这里写错的两种方式都不会报错，只会让人白等：试过的源又试一遍（多等一次超时），
 * 或者试完之后不返回 null（调用方以为还有机会）。所以逐个钉死。
 */
class UpdateSourcesTest {

    @Test
    fun `依次给出每个源，不重复`() {
        val rotation = SourceRotation(listOf("a", "b", "c"))
        assertEquals("a", rotation.next())
        assertEquals("b", rotation.next())
        assertEquals("c", rotation.next())
    }

    @Test
    fun `试完之后返回 null 而不是从头再来`() {
        val rotation = SourceRotation(listOf("a"))
        assertEquals("a", rotation.next())
        assertNull(rotation.next())
        assertNull(rotation.next())
    }

    @Test
    fun `空列表立刻返回 null`() {
        assertNull(SourceRotation(emptyList()).next())
    }

    @Test
    fun `promoted 把上次成功的源提到最前`() {
        val sources = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), UpdateSources.promoted(sources, "c"))
        assertEquals(listOf("b", "a", "c"), UpdateSources.promoted(sources, "b"))
    }

    @Test
    fun `从来没有成功过就保持原顺序`() {
        val sources = listOf("a", "b")
        assertEquals(sources, UpdateSources.promoted(sources, null))
    }

    @Test
    fun `记下的源已经不在名单里时保持原顺序`() {
        // 名单会腐坏，promoted 不能因为记住了一个被删掉的源就把它塞回来
        val sources = listOf("a", "b")
        assertEquals(sources, UpdateSources.promoted(sources, "已失效的源"))
    }

    @Test
    fun `直连用空前缀表示，也能被 promote`() {
        val sources = listOf("https://gh-proxy.com/", "")
        assertEquals(listOf("", "https://gh-proxy.com/"), UpdateSources.promoted(sources, ""))
    }

    @Test
    fun `两条名单都以直连收尾或开头，且不含已知劣质源`() {
        // 资产：镜像优先、直连垫底（直连在国内只有约 1/3 成功率，放前面就是白等）
        assertEquals("", UpdateSources.ASSET.last())
        // ghproxy.net 实测 120 秒只下到 4.04MB 就截断，明确不用
        assertTrue(UpdateSources.ASSET.none { it.contains("ghproxy.net") })
        assertTrue(UpdateSources.API.none { it.contains("ghproxy.net") })
        // 元的直连排第一：实测最快最稳
        assertEquals("", UpdateSources.API.first())
    }
}
