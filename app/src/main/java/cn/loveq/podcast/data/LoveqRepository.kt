package cn.loveq.podcast.data

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class LoveqRepository(private val guard: RateLimitGuard) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 整体超时：车机 4G 上单个请求卡死会一直占着线程
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 全局闸门的锁只保护「预约时间槽」那一瞬间，不覆盖网络请求本身 */
    private val rateLock = Mutex()

    /** 下一个可发请求的时间点（nanoTime 毫秒） */
    private var nextSlotAtMs = 0L

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
    private suspend fun fetch(url: String): String {
        // 冷却期内一次都不发。这里先查一次是快路径：能直接失败，不必去占闸门的时间槽
        // —— 凭空占槽会把冷却结束后的第一个请求往后推好几十秒
        throwIfCooling(url)

        awaitTurn()

        // 等槽位这几百毫秒里可能刚落冷却（别的请求刚被拒），再查一次才算把窗口关上。
        // 漏掉这一个，就正好是「被拒之后还在敲门」的那一下，而且它被拒还会把冷却续长
        throwIfCooling(url)

        return suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
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
                            if (!r.isSuccessful) {
                                // 405/403/429 是官网对访问频率的拒绝，不是网络故障：
                                // 先落冷却再抛，绝不能混进 IOException 被上层当成抖动重试
                                if (r.code == 405 || r.code == 403 || r.code == HTTP_TOO_MANY_REQUESTS) {
                                    guard.onRateLimited()
                                    throw RateLimitedException(r.code, url)
                                }
                                throw IOException("HTTP ${r.code} - $url")
                            }
                            r.body?.string() ?: throw IOException("空响应 - $url")
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    // 通了就把退避档位清零：下次偶发被拒仍从最短冷却起步
                    guard.onSuccess()
                    if (cont.isActive) cont.resume(body)
                }
            })
        }
    }

    /** 冷却期内不发请求：反正会被拒，发了只会把封禁一次次续上 */
    private fun throwIfCooling(url: String) {
        if (guard.isBlocked()) throw RateLimitedException(CODE_LOCAL_COOLDOWN, url)
    }

    /**
     * 全局限速闸门：所有对官网的请求（建索引、翻页、取直链）都必须从这里过。
     *
     * 各加载循环原本各自 delay，但它们会互相叠加 —— 建索引的同时翻页、取直链，
     * 实际瞬时频率是失控的。这里用「预约时间槽」统一排开：每个调用者领一个不早于
     * [MIN_REQUEST_INTERVAL_MS] 的槽位，先到先得。
     *
     * 只锁计算槽位的那一瞬间，不占着锁做网络请求 —— 否则一次超时会把后续所有
     * 请求（包括用户在等的翻页）全卡在锁上。
     */
    private suspend fun awaitTurn() {
        val waitMs = rateLock.withLock {
            val now = System.nanoTime() / 1_000_000L
            val slot = maxOf(now, nextSlotAtMs)
            nextSlotAtMs = slot + MIN_REQUEST_INTERVAL_MS
            slot - now
        }
        if (waitMs > 0L) delay(waitMs)
    }

    companion object {
        const val SITE = "https://www.loveq.cn"

        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * 本地冷却期内的占位状态码。
         *
         * 这种情况其实没有真的发出请求、也没收到任何响应 —— 用一个非标准值标出来，
         * 免得日志里把「我们自己决定不发」误读成「官网返回了 429」。
         */
        private const val CODE_LOCAL_COOLDOWN = 0

        /**
         * 批量抓取时相邻两页之间的间隔（建索引、增量刷新）。
         *
         * 原来是 200ms（5 请求/秒）：全量 90 多页 18 秒打完，这正是把官网 WAF
         * 招来的原因。1500ms 下一次全量约 2 分半，一次性、可断点续抓。
         *
         * 只给「一抓就是几十页」的批量路径用。用户正等着结果的交互请求不套这个值，
         * 由下面的全局闸门兜底就够了。
         */
        const val CRAWL_DELAY_MS = 1_500L

        /**
         * 全局闸门的最小间隔，兜底用：任何调用路径叠加起来也不会超过 2 请求/秒。
         * 比 [CRAWL_DELAY_MS] 松，是为了不把「用户点播放」「跳到某一年」也拖慢 ——
         * 那些请求后面有人在等。
         */
        private const val MIN_REQUEST_INTERVAL_MS = 500L

        /**
         * UA 保留浏览器主体（免得被 WAF 一眼挡掉），后面追加自己的身份。
         *
         * 不伪装成官方客户端，也不轮换 UA：被认出来是好事 —— 官网要拦、要联系，
         * 都找得到人。轮换 UA 去规避拦截是另一码事，不在这里做。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0 Mobile Safari/537.36 " +
                "LoveqPodcast/1.5 (+https://github.com/pigstrong9/LoveqPodcast)"
    }
}
