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

    suspend fun loadPage(page: Int, catId: Int = Category.CANTONESE_ID): PageResult {
        val html = fetch("$SITE/program.html?cat_id=$catId&page=$page")
        return withContext(Dispatchers.Default) {
            val parsed = LoveqParser.parseList(html, SITE, catId)
            PageResult(parsed.episodes, parsed.totalPages)
        }
    }

    suspend fun fetchAudio(episodeId: Long): EpisodeAudio {
        val html = fetch("$SITE/program_download-$episodeId.html")
        return withContext(Dispatchers.Default) { LoveqParser.parseAudio(html, SITE) }
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
        const val SITE = "https://www.loveq.cn"

        /** 连续翻页之间的最小间隔：对官网客气一点，也是各加载循环共用的节流值 */
        const val REQUEST_DELAY_MS = 200L
    }
}
