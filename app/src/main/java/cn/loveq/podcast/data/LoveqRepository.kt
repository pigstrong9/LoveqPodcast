package cn.loveq.podcast.data

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

class LoveqRepository {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 整体超时：车机 4G 上单个请求卡死会一直占着线程
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    suspend fun loadPage(page: Int, catId: Int = Category.CANTONESE_ID): PageResult = withContext(Dispatchers.IO) {
        val html = fetch("$SITE/program.html?cat_id=$catId&page=$page")
        val doc = Jsoup.parse(html, SITE)

        val blocks = doc.select("dl[id^=program]")
        // 页面里有节目详情链接、却一个列表块都没选中：这是改版而不是「这页恰好没内容」，
        // 必须抛错。返回空列表会被上层当成正常页写进缓存，之后一直显示「已加载 0 期」
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
            .mapNotNull { it.attr("href") }
            .mapNotNull { href -> pageParamOf(catId).find(href)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1

        PageResult(episodes, maxPage)
    }

    suspend fun fetchAudio(episodeId: Long): EpisodeAudio = withContext(Dispatchers.IO) {
        val html = fetch("$SITE/program_download-$episodeId.html")
        val doc = Jsoup.parse(html, SITE)

        // 多级 fallback：<audio src> → <audio><source src> → 正文里的 .mp3 链接。
        // 只按「是不是 mp3」筛，不再硬编码 dl2.loveq.cn，官网换 CDN 也不用改代码
        val audioUrls = (
            doc.select("audio[src]").map { it.attr("abs:src") } +
                doc.select("audio source[src]").map { it.attr("abs:src") } +
                doc.select("a[href]").map { it.attr("abs:href") }
            )
            .filter { it.startsWith("http") && it.substringBefore('?').endsWith(".mp3") }
            .distinct()
        val url = audioUrls.firstOrNull { it.contains("/live/program/") }
            ?: audioUrls.firstOrNull { !it.contains("/live/audio/") }
            ?: audioUrls.firstOrNull()
            ?: throw IOException("音频地址解析失败")

        val title = doc.select("li")
            .map { it.ownText().trim() }
            .firstOrNull { it.startsWith("节目内容") }
            ?.removePrefix("节目内容：")?.removePrefix("节目内容:")?.trim()

        EpisodeAudio(url, title)
    }

    /**
     * 挂起式请求：协程取消时必须真的掐断在途 HTTP。
     * 原来用阻塞 execute()，ViewModel 的 loadJob.cancel() 断不掉它，
     * 线程会被占满一个完整超时周期，快速切分类/翻页时请求会堆积。
     */
    private suspend fun fetch(url: String): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code} - $url")
                        r.body?.string() ?: throw IOException("空响应 - $url")
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(body)
            }
        })
    }

    companion object {
        private const val SITE = "https://www.loveq.cn"

        private fun pageParamOf(catId: Int): Regex = Regex("""cat_id=$catId&page=(\d+)""")
    }
}
