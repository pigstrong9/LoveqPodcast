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
     * 只按 mp3 后缀筛、不硬编码下载服务器域名，官网换 CDN 无需改代码。
     */
    fun parseAudio(html: String, baseUri: String): EpisodeAudio {
        val doc = Jsoup.parse(html, baseUri)
        val urls = (
            doc.select("audio[src]").map { it.attr("abs:src") } +
                doc.select("audio source[src]").map { it.attr("abs:src") } +
                doc.select("a[href]").map { it.attr("abs:href") }
            )
            .filter { it.startsWith("http") && it.substringBefore('?').endsWith(".mp3") }
            .distinct()
        val url = urls.firstOrNull { it.contains("/live/program/") }
            ?: urls.firstOrNull { !it.contains("/live/audio/") }
            ?: throw IOException("音频地址解析失败")

        val title = doc.select("li")
            .map { it.ownText().trim() }
            .firstOrNull { it.startsWith("节目内容") }
            ?.removePrefix("节目内容：")?.removePrefix("节目内容:")?.trim()

        return EpisodeAudio(url, title)
    }

    private fun pageParamOf(catId: Int): Regex = Regex("""cat_id=$catId&page=(\d+)""")
}
