package cn.loveq.podcast.data

import org.jsoup.Jsoup
import java.io.IOException

/**
 * 官网 HTML 解析。纯函数、不碰网络也不碰 Android 框架，
 * 因此可以用 fixture 在 JVM 上跑单测 —— 官网改版时这里会最先报警。
 */
object LoveqParser {

    data class ListPage(val episodes: List<Episode>, val totalPages: Int)

    /**
     * 解析列表页。
     *
     * 页面里明明有节目详情链接、却一个列表块都选不中时判定为「改版」并抛错，
     * 而不是返回空列表：空结果会被上层当成正常页写进缓存，
     * 之后一直显示「已加载 0 期 · 已经到底啦」，用户完全无从察觉。
     */
    fun parseList(html: String, baseUri: String, catId: Int): ListPage {
        val doc = Jsoup.parse(html, baseUri)
        val blocks = doc.select("dl[id^=program]")
        if (blocks.isEmpty() && html.contains("program_download-")) {
            throw IOException("节目列表结构已变化，解析规则需要更新")
        }
        val episodes = blocks.mapNotNull { dl ->
            val id = dl.id().removePrefix("program").toLongOrNull() ?: return@mapNotNull null
            val date = dl.selectFirst("dt a")?.text()?.trim().orEmpty()
            if (date.isEmpty()) return@mapNotNull null
            val title = dl.selectFirst("dd.ct span")?.text()?.trim().orEmpty()
            Episode(id, date, title.ifEmpty { date })
        }
        val maxPage = doc.select("a[href]")
            .map { it.attr("href") }
            .mapNotNull { href -> pageParamOf(catId).find(href)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        return ListPage(episodes, maxPage)
    }

    /**
     * 解析详情页，取音频直链与节目标题。
     *
     * 多级 fallback：`<audio src>` → `<audio><source src>` → 正文里的 .mp3 链接，
     * 只按 mp3 后缀筛（不分大小写，见 [isAudioUrl]）、不硬编码下载服务器域名，
     * 官网换 CDN 无需改代码。
     */
    fun parseAudio(html: String, baseUri: String): EpisodeAudio {
        val doc = Jsoup.parse(html, baseUri)
        val urls = (
            doc.select("audio[src]").map { it.attr("abs:src") } +
                doc.select("audio source[src]").map { it.attr("abs:src") } +
                doc.select("a[href]").map { it.attr("abs:href") }
            )
            .filter { it.startsWith("http") && isAudioUrl(it) }
            .distinct()
        val url = urls.firstOrNull { it.contains(PROGRAM_PATH) }
            ?: urls.firstOrNull { !isSiteJingle(it) }
            ?: throw AudioParseException("音频地址解析失败")

        val title = doc.select("li")
            .map { it.ownText().trim() }
            .firstOrNull { it.startsWith("节目内容") }
            ?.removePrefix("节目内容：")?.removePrefix("节目内容:")?.trim()

        return EpisodeAudio(url, title)
    }

    /**
     * 从分页链接里取页码。
     *
     * 「全部节目」（cat_id=0）的分页链接**不带** cat_id，形如 `program.html?&page=2`；
     * 其余分类才是 `program.html?cat_id=N&page=2`。早先只认带 cat_id 的那种，
     * 结果「全部节目」这一档的 totalPages 永远算成 1：只能停在第一页，跳年份也失效。
     * 所以 cat_id 前缀做成可选的，两种形式都要认。
     */
    private fun pageParamOf(catId: Int): Regex = Regex("""(?:cat_id=$catId&)?page=(\d+)""")

    /**
     * 音频后缀判定不分大小写。
     *
     * 官网存在 `2026.09.22.MP3` 这种全大写后缀的期（如 id 22905、22603）。
     * 早先按小写 `.mp3` 精确匹配时，这类期的直链会被整条筛掉，页面上只剩站头片段，
     * 于是 App 报「音频地址解析失败 / 加载节目失败」，而官网网页照常能听 —— 看着像断网，
     * 其实是后缀大小写把真链接筛没了。
     */
    private fun isAudioUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".mp3", ignoreCase = true)

    /**
     * 站头/年份试听片段（`http://dl.loveq.cn/live/audio/2003.mp3` 这一组）。
     *
     * 只按「这个站头主机的试听目录」排除，不要顺手把整条 `/live/audio/` 路径都当噪音：
     * 节目正片一旦被挪到别的主机但该目录下，全局排除会让它再次解析失败。
     */
    private fun isSiteJingle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(JINGLE_HOST) && lower.contains(JINGLE_PATH)
    }

    private const val PROGRAM_PATH = "/live/program/"
    private const val JINGLE_HOST = "dl.loveq.cn"
    private const val JINGLE_PATH = "/live/audio/"

    /**
     * 官网列表每页条数。
     *
     * 索引里「第 pos 条」换算成页码（跳年份的加速猜测）、离线兜底切一片列表，
     * 都要用它。放在解析器里是因为它本质上是对官网分页规则的假设，
     * 改版时应该和选择器一起改；散落在各处的 26 迟早会漏改一个。
     */
    const val PAGE_SIZE = 26
}

/**
 * 页面里找不到本期音频直链：结构对不上、或后缀不认识。
 *
 * 和断网严格区分开：这类失败重试网络、去官网检查都白费（官网网页本来就能听），
 * 真正需要的是更新解析规则。上层据此给不同的提示。
 */
class AudioParseException(message: String) : IOException(message)
