package cn.loveq.podcast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

class LoveqRepository {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    suspend fun loadPage(page: Int, catId: Int = Category.CANTONESE_ID): PageResult = withContext(Dispatchers.IO) {
        val html = fetch("$SITE/program.html?cat_id=$catId&page=$page")
        val doc = Jsoup.parse(html, SITE)

        val episodes = doc.select("dl[id^=program]").mapNotNull { dl ->
            val id = dl.id().removePrefix("program").toLongOrNull() ?: return@mapNotNull null
            val date = dl.selectFirst("dt a")?.text()?.trim().orEmpty()
            if (date.isEmpty()) return@mapNotNull null
            val title = dl.selectFirst("dd.ct span")?.text()?.trim().orEmpty()
            Episode(id, date, title.ifEmpty { date })
        }

        val maxPage = doc.select("a[href]")
            .mapNotNull { it.attr("href") }
            .mapNotNull { href -> pageParamOf(catId).find(href)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1

        PageResult(episodes, maxPage)
    }

    suspend fun fetchAudio(episodeId: Long): EpisodeAudio = withContext(Dispatchers.IO) {
        val html = fetch("$SITE/program_download-$episodeId.html")
        val doc = Jsoup.parse(html, SITE)

        val audioUrls = doc.select("audio[src]").map { it.attr("abs:src") }
        val url = audioUrls.firstOrNull { it.contains("dl2.loveq.cn") }
            ?: audioUrls.firstOrNull { it.startsWith("http") && !it.contains("dl.loveq.cn/live/audio/") }
            ?: throw IOException("音频地址解析失败")

        val title = doc.select("li")
            .map { it.ownText().trim() }
            .firstOrNull { it.startsWith("节目内容") }
            ?.removePrefix("节目内容：")?.removePrefix("节目内容:")?.trim()

        EpisodeAudio(url, title)
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} - $url")
            return response.body?.string() ?: throw IOException("空响应 - $url")
        }
    }

    companion object {
        private const val SITE = "https://www.loveq.cn"

        private fun pageParamOf(catId: Int): Regex = Regex("""cat_id=$catId&page=(\d+)""")
    }
}
