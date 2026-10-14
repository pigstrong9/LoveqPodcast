package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 官网 HTML 解析回归测试。
 *
 * 数据源是抓公开的 HTML 页面，没有 API 契约，官网改版是最现实的故障模式。
 * 这些 fixture 就是「契约」：解析规则一旦和页面对不上，这里先红。
 */
class LoveqParserTest {

    private val base = "https://www.loveq.cn"

    private val listPage = """
        <html><body>
        <dl id="program22915"><dt><a href="program_download-22915.html">2026.10.12</a></dt>
            <dd class="ct"><span>大家骗过另一半最久的事情是什么？</span></dd></dl>
        <dl id="program22913"><dt><a href="program_download-22913.html">2026.10.08</a></dt>
            <dd class="ct"><span>一个立志做渣男的少年</span></dd></dl>
        <dl id="program22911"><dt><a href="program_download-22911.html">2026.09.30</a></dt></dl>
        <a href="program.html?cat_id=1&page=2">2</a>
        <a href="program.html?cat_id=1&page=94">94</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `列表页解析出条目日期标题与总页数`() {
        val page = LoveqParser.parseList(listPage, base, catId = 1)

        assertEquals(3, page.episodes.size)
        assertEquals(94, page.totalPages)
        assertEquals(
            Episode(22915, "2026.10.12", "大家骗过另一半最久的事情是什么？"),
            page.episodes.first()
        )
        // 保持页面顺序（新到旧），连播方向依赖它
        assertEquals(listOf(22915L, 22913L, 22911L), page.episodes.map { it.id })
    }

    @Test
    fun `标题缺失时退化成日期而不是丢掉整条`() {
        val page = LoveqParser.parseList(listPage, base, catId = 1)
        val last = page.episodes.last()

        assertEquals("2026.09.30", last.title)
        assertEquals("2026.09.30", last.date)
    }

    @Test
    fun `列表结构变化时抛错而不是返回空页污染缓存`() {
        // 有详情链接、没有任何 dl[id^=program]：这是改版，不是「这页没内容」
        val restructured = """
            <html><body><div class="item">
              <a href="program_download-22915.html">2026.10.12</a>
            </div></body></html>
        """.trimIndent()

        assertThrows(java.io.IOException::class.java) {
            LoveqParser.parseList(restructured, base, catId = 1)
        }
    }

    @Test
    fun `真正没有节目的页面返回空列表而不是报错`() {
        val empty = "<html><body><p>暂无节目</p></body></html>"

        val page = LoveqParser.parseList(empty, base, catId = 5)

        assertEquals(0, page.episodes.size)
        assertEquals(1, page.totalPages)
    }

    @Test
    fun `详情页取带签名的节目直链与内容简介`() {
        val detail = """
            <html><body>
            <audio src="//dl2.loveq.cn:8090/live/program/2026/10/x.mp3?sign=abc&amp;timestamp=1"></audio>
            <ul><li>节目内容：本期聊了很多</li></ul>
            </body></html>
        """.trimIndent()

        val audio = LoveqParser.parseAudio(detail, base)

        assertEquals("https://dl2.loveq.cn:8090/live/program/2026/10/x.mp3?sign=abc&timestamp=1", audio.url)
        assertEquals("本期聊了很多", audio.title)
    }

    @Test
    fun `音频改用 source 子元素也能解析`() {
        val detail = """
            <html><body>
            <audio controls><source src="https://cdn.example.net/live/program/a.mp3?sign=1"></audio>
            </body></html>
        """.trimIndent()

        assertEquals(
            "https://cdn.example.net/live/program/a.mp3?sign=1",
            LoveqParser.parseAudio(detail, base).url
        )
    }

    @Test
    fun `音频只挂在普通链接上也能解析`() {
        val detail = """
            <html><body>
            <a href="https://other-host.example.org/live/program/b.mp3?t=2">收听</a>
            </body></html>
        """.trimIndent()

        assertEquals(
            "https://other-host.example.org/live/program/b.mp3?t=2",
            LoveqParser.parseAudio(detail, base).url
        )
    }

    @Test
    fun `只有版头音频时判定为解析失败而不是错播别的内容`() {
        val detail = """
            <html><body>
            <audio src="https://dl.loveq.cn/live/audio/jingle.mp3"></audio>
            </body></html>
        """.trimIndent()

        assertThrows(java.io.IOException::class.java) {
            LoveqParser.parseAudio(detail, base)
        }
    }
}
