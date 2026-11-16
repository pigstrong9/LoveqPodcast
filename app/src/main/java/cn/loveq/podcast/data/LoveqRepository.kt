package cn.loveq.podcast.data

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

class LoveqRepository(
    private val guard: RateLimitGuard,
    private val session: LoveqSession
) {

    /**
     * 官网登录态。界面只读这个 StateFlow 判断是否显示「已登录」；
     * 真正的失效以请求被 302 到登录页为准（见 [LoginRequiredException]）。
     */
    val account: StateFlow<String?> = session.username

    /** 上次登录过的账号，仅用于登录框回填 */
    val lastUsername: StateFlow<String?> = session.lastUsername

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 整体超时：车机 4G 上单个请求卡死会一直占着线程
        .callTimeout(60, TimeUnit.SECONDS)
        // 只对 loveq.cn 发送 Cookie，避免把登录态带给别的域名；且列表页是公开的，
        // 不带登录态，减少把浏览行为绑到账号上的暴露面。
        .addInterceptor { chain ->
            val request = chain.request()
            // 发和收共用同一个判断，避免条件漂移。
            val sessionRequest = isSessionRequest(request.url.host, request.url.encodedPath)
            val outgoing = if (sessionRequest) {
                session.cookieHeader()?.let {
                    request.newBuilder().header("Cookie", it).build()
                } ?: request
            } else {
                request
            }
            val response = chain.proceed(outgoing)
            // 列表页不收 Set-Cookie：没带 Cookie 时官网会下发匿名 LQ_ID，
            // 它和登录态 LQ_ID 同名，merge 会覆盖掉登录态，导致下次取播放链接时
            // 带的是匿名 Cookie、登录失效。匿名播种由 login() 抓 user-login 页完成，
            // 不依赖列表页。其余官网响应照收：登录态下发、过期时间刷新都靠它。
            if (sessionRequest) {
                session.saveCookies(response.headers.values("Set-Cookie"))
            }
            response
        }
        .build()

    /** 全局闸门的锁只保护「预约时间槽」那一瞬间，不覆盖网络请求本身 */
    private val rateLock = Mutex()

    private val pacer = RequestPacer()

    suspend fun loadPage(page: Int, catId: Int = Category.CANTONESE_ID): PageResult {
        val html = fetch("$SITE/program.html?cat_id=$catId&page=$page")
        return withContext(Dispatchers.Default) {
            val parsed = LoveqParser.parseList(html, SITE, catId)
            PageResult(parsed.episodes, parsed.totalPages)
        }
    }

    suspend fun fetchAudio(episodeId: Long): EpisodeAudio {
        val html = fetch("$SITE/program_download-$episodeId.html")
        return withContext(Dispatchers.Default) {
            try {
                LoveqParser.parseAudio(html, SITE)
            } catch (e: LoginRequiredException) {
                // 登录墙也可能以 200 + 登录页返回：fetch 只认 302 重定向，
                // 这里补上登录态失效，免得设置页仍显示「已登录」
                session.onAuthRequired()
                throw e
            }
        }
    }

    /**
     * 用用户自己的 LoveQ 账号登录。
     *
     * 官网登录页的 `signIn()` 就是 POST `user.php?act=signin`，只带
     * username / password / captcha；成功后官网通过 Set-Cookie 把会话写进
     * `LQ_ID`（由上面的拦截器统一收下）。这里**不保存密码**。
     *
     * 第一次登录通常不需要验证码；密码错几次后官网会返回 `captcha=1`，
     * 此时去取 `get_img.php?is_login=1` 的图片，让用户补输。
     */
    suspend fun login(username: String, password: String, captcha: String): LoginOutcome {
        // 从设置主动登录时，本机可能还没访问过官网（没有匿名 LQ_ID）。
        // 先取一次登录页播种会话，否则官网的验证码/登录态对不上。
        if (session.cookieHeader() == null) {
            fetch("$SITE/user-login.html")
        }

        throwIfCooling("$SITE/user.php?act=signin")
        awaitTurn()

        val body = withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("captcha", captcha)
                .add("cookietime", "2592000")
                .add("back_act", "$SITE/")
                .build()
            val request = Request.Builder()
                .url("$SITE/user.php?act=signin")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$SITE/user-login.html")
                .header("Origin", SITE)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .post(form)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 405 || response.code == 403 ||
                    response.code == HTTP_TOO_MANY_REQUESTS
                ) {
                    guard.onRateLimited()
                    throw RateLimitedException(response.code, request.url.toString())
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                guard.onSuccess()
                response.body?.string().orEmpty()
            }
        }

        return withContext(Dispatchers.Default) {
            val reply = try {
                LoginReply.parse(body)
            } catch (_: JSONException) {
                return@withContext LoginOutcome.Failure("官网登录接口返回异常，请稍后重试")
            }
            when {
                reply.error == 0 -> {
                    session.markLoggedIn(username)
                    LoginOutcome.Success(username)
                }
                reply.captcha -> LoginOutcome.CaptchaRequired(
                    image = loadCaptchaImage(),
                    message = reply.message.ifBlank { "请输入验证码" }
                )
                else -> LoginOutcome.Failure(reply.message.ifBlank { "登录失败" })
            }
        }
    }

    /** 验证码图片。拿不到就返回 null，界面仍保留手输入口。 */
    suspend fun loadCaptchaImage(): ByteArray? {
        throwIfCooling("$SITE/get_img.php")
        awaitTurn()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$SITE/get_img.php?is_login=1&t=${System.currentTimeMillis()}")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$SITE/user-login.html")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.bytes()
            }
        }
    }

    /** 退出登录：清掉本机保存的 Cookie 和用户名，下次播放会重新弹登录框 */
    fun logout() {
        session.logout()
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
                            // 官网从 2026-11-16 起对 program_download 加登录墙：
                            // 未登录会被 302 到 loveq.cn/user-login.html。
                            // 必须在解析 HTML 之前认出来，否则只会得到一个含糊的
                            // 「音频地址解析失败」，让人误以为是解析规则过期。
                            // 主动抓登录页播种 Cookie 时不算撞墙。
                            if (!url.contains("user-login") &&
                                r.request.url.toString().contains("user-login")
                            ) {
                                session.onAuthRequired()
                                throw LoginRequiredException()
                            }
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
     * [RequestPacer.MIN_REQUEST_INTERVAL_MS] 的槽位，先到先得。
     *
     * 只锁计算槽位的那一瞬间，不占着锁做网络请求 —— 否则一次超时会把后续所有
     * 请求（包括用户在等的翻页）全卡在锁上。
     */
    private suspend fun awaitTurn() {
        val waitMs = rateLock.withLock {
            pacer.reserve(System.nanoTime() / 1_000_000L)
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
         * 通用浏览器 UA，只保留主体，不追加客户端身份。
         *
         * 保留浏览器主体是因为 CDN 防盗链会拒掉裸 OkHttp/ExoPlayer 的 UA；
         * 不追加 LoveqPodcast 字样，是不让官网按 UA 直接标记出第三方客户端。
         * 这是「不主动自报」，不是「伪装」：不冒充 loveq 官方客户端，不轮换 UA，
         * 也不做指纹或行为层面的对抗式规避 —— 固定一颗常见浏览器 UA 比轮换更自然。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0 Mobile Safari/537.36"
    }
}

/**
 * 该请求是否参与会话 Cookie 的收发（带上 Cookie、并收下 Set-Cookie）。
 *
 * 两条规则：
 * - 只认 loveq.cn，别把登录态带给别的域名（如 GitHub 更新源）；
 * - 列表页 `/program.html` 是公开的，不带登录态，减少浏览行为与账号的关联。
 *
 * 精确匹配 path —— 不能用 contains("program")，`program_download` 也含这个词。
 * 抽成纯函数是为了能在 JVM 单测里覆盖，避免"发/收条件不一致"这种隐蔽回归。
 */
internal fun isSessionRequest(host: String, encodedPath: String): Boolean =
    host.endsWith("loveq.cn") && encodedPath != "/program.html"

/**
 * 官网要求登录后才能继续访问。
 *
 * 和「解析规则过期」「网络失败」都不同：它有一个明确的用户动作 ——
 * 在应用内用自己的 LoveQ 账号登录。不要把登录墙降级成普通 IOException，
 * 否则错误提示只会让人去检查网络或等更新。
 */
class LoginRequiredException : IOException("官网要求登录后才能获取音频")
