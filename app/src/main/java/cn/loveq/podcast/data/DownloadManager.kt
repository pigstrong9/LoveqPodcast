package cn.loveq.podcast.data

import android.content.Context
import android.os.StatFs
import cn.loveq.podcast.playback.AuthEvents
import cn.loveq.podcast.playback.PlaybackEvents
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.IOException

/**
 * 批量离线缓存。
 *
 * 和 MediaCache 的流式缓存是两回事：流式缓存是「顺手存下来的分片」，放在 cacheDir、
 * 受 2GB LRU 淘汰、系统也可能回收；这里是用户明确点名要留的整集文件，
 * 放 filesDir、不参与淘汰，只有听完自动删或用户手动删才消失。
 *
 * 一次只跑一个下载：车机是 4G/热点，并发抢带宽只会让每一期都下得更慢。
 */
class DownloadManager(
    context: Context,
    private val repository: LoveqRepository,
    private val guard: RateLimitGuard
) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "offline").apply { mkdirs() }
    private val manifest = File(dir, "index.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    private val _downloads = MutableStateFlow<List<Entry>>(emptyList())
    val downloads: StateFlow<List<Entry>> = _downloads

    /**
     * 清单是否已从磁盘读完。启动自动下载必须等这个为 true 再算「已缓存」——
     * 否则读到的是空表，会把已下的期当成未缓存重新排队，还会被随后的 [load] 覆盖。
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    /** 供编排方等待清单就绪，超时返回 false（调用方自行决定是否照常继续） */
    suspend fun awaitLoaded(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        _loaded.first { it }
        true
    } ?: false

    /**
     * 队列状态（[_downloads]）会被两边同时改：UI 线程排队/删除，下载线程回写进度。
     * 所有「读出来算一遍再写回」的地方都必须在这把锁里，否则后写的会整片覆盖前者的结果
     * ——表现为选了三期只排进去两期。纯替换用的 [updateEntry] 走 StateFlow 的 CAS，无需加锁。
     */
    private val stateLock = Any()

    private var worker: Job? = null

    /** 限流后的定时重试任务：到点重新 pump；删除/清理/新任务入队时取消重排 */
    private var retryJob: Job? = null

    /**
     * 流水线代号：取消/删除会掐掉当前流水线并重启一条新的。
     * 旧协程收尾时靠它认出自己已经不是当前流水线，就不会去清掉新流水线的状态。
     */
    private var pipelineId = 0L

    enum class State { QUEUED, DOWNLOADING, DONE, FAILED }

    data class Entry(
        val episode: Episode,
        val state: State,
        val bytes: Long,
        val totalBytes: Long
    ) {
        val progress: Float get() = if (totalBytes > 0) bytes.toFloat() / totalBytes else 0f
    }

    init {
        scope.launch { load() }
    }

    /** 已下完的本地文件；没有则返回 null，播放时据此决定走本地还是走网络直链 */
    fun localFile(episodeId: Long): File? {
        val f = File(dir, "$episodeId.mp3")
        return if (f.length() > 0) f else null
    }

    /** @return 实际入队数（调用方据此播报，避免把已缓存的期也算进去报多） */
    fun enqueue(episodes: List<Episode>): Int {
        val pending = episodes.filter { e ->
            _downloads.value.none { it.episode.id == e.id } && localFile(e.id) == null
        }
        if (pending.isEmpty()) return 0
        // 一期约 126MB（128kbps 两小时），车机存储不大；空间不够就明确拒绝，
        // 不能下到一半把设备塞满
        val need = pending.size * ESTIMATED_BYTES
        val free = StatFs(dir.absolutePath).availableBytes
        if (free < need) {
            PlaybackEvents.report(
                "空间不足：缓存 ${pending.size} 期约需 ${need / MB} MB，" +
                    "当前仅剩 ${free / MB} MB"
            )
            return 0
        }
        // CAS 追加：下载线程正在回写进度时也不能把刚排进来的期覆盖掉
        _downloads.update { list -> list + pending.map { Entry(it, State.QUEUED, 0, 0) } }
        persist()
        pump()
        return pending.size
    }

    /** 失败重试：FAILED 条目还留在列表里，不重置状态的话重新「缓存」会被当成已存在而跳过 */
    fun retry(episodeId: Long) {
        updateEntry(episodeId) {
            if (it.state == State.FAILED) it.copy(state = State.QUEUED, bytes = 0, totalBytes = 0) else it
        }
        persist()
        pump()
    }

    /** 手动删除，或听完由服务自动调用 */
    fun remove(episodeId: Long) {
        synchronized(stateLock) {
            // 正在下的这一期被删掉，必须掐掉流水线：否则它会继续写 .part，
            // 结束时改名成正式文件，刚删的节目又自己回来了
            if (currentEpisodeId == episodeId) stopPipelineLocked()
            _downloads.value = _downloads.value.filterNot { it.episode.id == episodeId }
        }
        File(dir, "$episodeId.mp3").delete()
        File(dir, "$episodeId.mp3.part").delete()
        persist()
        pump()
    }

    /**
     * 一键清掉所有未完成的下载任务（排队中 / 下载中 / 失败），保留已下完的。
     *
     * 要在锁里先掐掉流水线再改列表：否则被取消的协程收尾时会把「下载中」又写回 FAILED，
     * 清完又冒出来。文件按 id 一起删（含 .part），免得取消瞬间的半成品永远占着存储。
     *
     * @return 清掉的任务数，给界面回显用
     */
    fun clearUnfinished(): Int {
        val removed = synchronized(stateLock) {
            val pending = _downloads.value.filter { it.state != State.DONE }
            if (pending.isEmpty()) {
                emptyList<Long>()
            } else {
                val ids = pending.map { it.episode.id }
                if (currentEpisodeId in ids) stopPipelineLocked()
                _downloads.value = _downloads.value.filter { it.state == State.DONE }
                ids
            }
        }
        if (removed.isEmpty()) return 0
        removed.forEach { id ->
            File(dir, "$id.mp3").delete()
            File(dir, "$id.mp3.part").delete()
        }
        persist()
        pump()
        return removed.size
    }

    @Volatile
    private var currentEpisodeId: Long = -1L

    /** 启动（或重启）一条串行流水线。必须在 [stateLock] 内调用 */
    private fun pump() {
        synchronized(stateLock) {
            if (worker?.isActive == true) return
            // 有活要干了就撤掉旧的定时重试：用户新加了任务，或冷却已到点自行 pump
            retryJob?.cancel()
            retryJob = null
            val pipeline = ++pipelineId
            worker = scope.launch { drain(pipeline) }
        }
    }

    /** 掐掉当前流水线。必须在 [stateLock] 内调用 */
    private fun stopPipelineLocked() {
        pipelineId++
        worker?.cancel()
        worker = null
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * 限流后安排自动重试：等官网冷却结束再 pump，任务全程留在 QUEUED 里。
     *
     * 不在这条流水线里空等——一是 30 分钟起步的 delay 会让队列看着卡死，二是进程随时可能
     * 被系统杀掉；留在队列 + 持久化 manifest 保证下次启动也能接着下。单段等待上限
     * [MAX_RETRY_WAIT_MS]：官网 6 小时冷却裁成 30 分钟一段，到点 pump 一次、被 guard
     * 拦下就再等一段，不挂 6 小时协程（冷却期不发请求，不会升档）。
     * 冷却已过（wait<=0）时由 drain 交还推进权后立刻重排，避免队列卡死。
     * 新任务入队/删除/清理都会取消旧的重排，避免重复排队。
     */
    private fun scheduleRateRetry() {
        synchronized(stateLock) {
            retryJob?.cancel()
            if (!_downloads.value.any { it.state == State.QUEUED }) return
            val wait = retryDelayMs(guard.blockedForMs(), MAX_RETRY_WAIT_MS)
            if (wait <= 0L) {
                // 冷却已过（或等待被夹到 0）：条目已回 QUEUED，需要立刻重排。
                // 注意本方法由 download() 在 worker 协程内调用，此刻 worker 仍 isActive，
                // 直接 pump() 会被守卫挡掉。真正的重排交给即将退出的 drain()：它在锁内
                // 先把 worker 置空，再调本方法，pump 才能起新流水线。
                retryJob = scope.launch {
                    currentCoroutineContext().ensureActive()
                    pump()
                }
                return
            }
            retryJob = scope.launch {
                delay(wait)
                currentCoroutineContext().ensureActive()
                pump()
            }
        }
    }

    /**
     * 串行推进整条队列：一期下完、隔一会儿再接下一期，直到没有排队项。
     *
     * 写成循环而不是「下完一期后再 pump() 一次」——download() 就跑在 worker 自己的协程里，
     * 从它内部调 pump() 时 worker 仍然 isActive，pump 的守卫会直接返回，
     * 于是批量下载只下第一期，后面排队的永远不动（要等下次删除/重启才偶然接上下一期）。
     *
     * 相邻两期之间留 [EPISODE_GAP_MS]：官网对 program_download 的取链有频率限制，
     * 连着取几期会触发限流，后面几期全失败；单期间隔重试则能下。首期不等（用户点了就立刻动），
     * 从第二期起才等。delay 在流水线被取消时会抛 CancellationException，正好随删除/清理一起停下。
     */
    private suspend fun drain(pipeline: Long) {
        var downloadedThisPipeline = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = claimNext(pipeline)
            if (next == null) {
                // 队列看着空了，但「交还推进权」和「确认没活了」必须在同一把锁里做完：
                // 否则用户刚好在这一瞬排进来几期，pump() 会因「worker 还活着」直接返回，
                // 这几期就没人接手了
                if (retireIfIdle(pipeline)) return
                continue
            }
            if (downloadedThisPipeline) delay(EPISODE_GAP_MS)
            val canContinue = download(next, pipeline)
            if (!canContinue) {
                // 限流收手：download 已把条目重置回 QUEUED。必须在交还推进权之后
                // 再安排重试——否则 worker 仍 isActive，pump 会被守卫挡掉，队列卡死。
                val stillCurrent = synchronized(stateLock) {
                    if (pipeline == pipelineId) {
                        worker = null
                        true
                    } else false
                }
                // 已被删除/清理/新入队抢走推进权时，新流水线会自己 pump，不要重复重排
                if (stillCurrent) scheduleRateRetry()
                return
            }
            downloadedThisPipeline = true
        }
    }

    /** 队列确实没有排队项就交还推进权并退出；期间排进来了就继续跑，返回 false */
    private fun retireIfIdle(pipeline: Long): Boolean = synchronized(stateLock) {
        if (pipeline != pipelineId) return true
        if (_downloads.value.none { it.state == State.QUEUED }) {
            worker = null
            true
        } else {
            false
        }
    }

    /** 取队首的排队项并原子标记为下载中；返回 null 表示队列已空或本流水线已作废 */
    private fun claimNext(pipeline: Long): Episode? = synchronized(stateLock) {
        if (pipeline != pipelineId) return null
        val next = _downloads.value.firstOrNull { it.state == State.QUEUED } ?: return null
        _downloads.value = _downloads.value.map {
            if (it.episode.id == next.episode.id) it.copy(state = State.DOWNLOADING) else it
        }
        currentEpisodeId = next.episode.id
        next.episode
    }

    /** @return false 表示撞上官网限流，流水线应立刻收手（余数交给定时重试） */
    private suspend fun download(episode: Episode, pipeline: Long): Boolean {
        val target = File(dir, "${episode.id}.mp3")
        val part = File(dir, "${episode.id}.mp3.part")
        try {
            val audio = repository.fetchAudio(episode.id)
            // CDN 直链同样要带 UA：裸 OkHttp UA 在带防盗链的 CDN 上会被当成异常客户端
            val request = Request.Builder()
                .url(audio.url)
                .header("User-Agent", LoveqRepository.USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("空响应")
                val total = body.contentLength()
                updateEntry(episode.id) { it.copy(totalBytes = total) }
                FileOutputStream(part).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            // 阻塞式读流不会自己响应取消，每块主动检查一次
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            // 每 ~256KB 报一次进度，避免高频重组
                            if (written / PROGRESS_STEP != (written - n) / PROGRESS_STEP) {
                                updateEntry(episode.id) { it.copy(bytes = written) }
                            }
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
            }
            // 取消发生在收尾时也要停下，别让已取消的一期又落出正式文件
            currentCoroutineContext().ensureActive()
            if (!part.renameTo(target)) throw IOException("重命名失败")
            // 下完不从列表里剔除：列表同时是「哪几期离线可听」的唯一数据源，
            // 列表徽标和缓存页都靠它
            updateEntry(episode.id) { it.copy(state = State.DONE, bytes = target.length()) }
        } catch (e: Exception) {
            part.delete()
            if (e is CancellationException) {
                _downloads.update { list -> list.filterNot { it.episode.id == episode.id } }
                throw e
            }
            if (e is RateLimitedException) {
                // 限流不是永久失败：留在队列里，等官网冷却结束自动重试（或下次启动续下）。
                // 绝不能标 FAILED 后还让流水线接着下——那是对着已经拒绝我们的官网继续敲门，
                // 只会把冷却档位越升越高（30min → 6h）。
                updateEntry(episode.id) { it.copy(state = State.QUEUED, bytes = 0, totalBytes = 0) }
                val wait = guard.describeRemaining()
                PlaybackEvents.report(
                    if (wait.isEmpty()) "《${episode.date}》离线缓存稍后自动重试"
                    else "官网暂时限制了访问，《${episode.date}》约 ${wait}后自动重试"
                )
                // 重排由 drain 在交还推进权（worker 置空）后统一调用 scheduleRateRetry，
                // 这里若直接调会被「worker 仍 isActive」的守卫挡掉，队列从此没人接手。
                return false
            }
            updateEntry(episode.id) { it.copy(state = State.FAILED) }
            // 登录墙和普通失败要分开说：前者得先登录，后者可点重试
            PlaybackEvents.report(
                when (e) {
                    is LoginRequiredException -> {
                        AuthEvents.requestLogin(
                            AuthEvents.Retry.DOWNLOAD,
                            episode.id
                        )
                        "《${episode.date}》离线缓存失败：官网要求登录后才能下载"
                    }
                    else -> "《${episode.date}》离线缓存失败，可点重试"
                }
            )
        } finally {
            persist()
            synchronized(stateLock) {
                // 只有仍归本流水线时才清，否则会把刚重启的流水线「当前期」误清空
                if (pipeline == pipelineId) currentEpisodeId = -1L
            }
        }
        return true
    }

    private fun updateEntry(id: Long, transform: (Entry) -> Entry) {
        _downloads.update { list -> list.map { if (it.episode.id == id) transform(it) else it } }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            _downloads.value.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.episode.id)
                        .put("date", e.episode.date)
                        .put("title", e.episode.title)
                        .put("state", e.state.name)
                        .put("bytes", e.bytes)
                        .put("totalBytes", e.totalBytes)
                )
            }
            val tmp = File(dir, "index.json.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(arr.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(manifest)) {
                manifest.delete()
                tmp.renameTo(manifest)
            }
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            val list = try {
                if (!manifest.exists()) emptyList() else {
                    val arr = JSONArray(manifest.readText())
                    buildList {
                        for (i in 0 until arr.length()) {
                            // 单条坏数据只跳过，别让整张下载表作废
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optLong("id")
                            if (id <= 0) continue
                            val state = runCatching { State.valueOf(o.optString("state")) }
                                .getOrDefault(State.QUEUED)
                            add(
                                Entry(
                                    episode = Episode(id, o.optString("date"), o.optString("title")),
                                    // 上次进程被杀时正在下的，重启后重新排队
                                    state = if (state == State.DOWNLOADING) State.QUEUED else state,
                                    bytes = 0,
                                    totalBytes = o.optLong("totalBytes")
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
            // 磁盘上有完整文件的直接标 DONE（不重下）；清单说下完了但文件已不在的，重新排队。
            // 注意这里不删 .part：本方法读盘可能慢于启动自动下载的 2 秒延时，同一期可能
            // 已经被内存入队甚至开下，删它的 .part 会砍掉 worker 正在写的文件，收尾
            // renameTo 失败→该期误标 FAILED。清理推迟到下面的锁内，只针对磁盘独有的条目。
            val restored = list.map { entry ->
                val f = localFile(entry.episode.id)
                when {
                    f != null -> entry.copy(state = State.DONE, bytes = f.length(), totalBytes = f.length())
                    entry.state == State.DONE -> entry.copy(state = State.QUEUED, bytes = 0)
                    else -> entry
                }
            }
            synchronized(stateLock) {
                val live = _downloads.value
                // 只补磁盘里有、内存里还没有的条目。绝不能整片覆盖：
                // 启动早期（本方法还在读盘时）自动下载/手动缓存可能已经入队甚至开下，
                // 整片覆盖会把这些新条目全部抹掉，表现就是「批量下载只下到一期」。
                // 内存里的状态永远比磁盘新，以它为准。
                // .part 清理与合并必须同一把锁：只对磁盘独有的条目动手，内存里已有同 id 的
                // 说明 worker 可能正写着它的 .part，绝不能删。
                partIdsToCleanOnRestore(live, restored).forEach { id ->
                    File(dir, "$id.mp3.part").delete()
                }
                _downloads.value = mergeRestoredEntries(live, restored)
            }
            persist()
            pump()
        } finally {
            // 无论读盘成功与否都要通知等待方，否则 awaitLoaded 会白等到超时
            _loaded.value = true
        }
    }

    companion object {
        private const val PROGRESS_STEP = 256L * 1024
        private const val MB = 1024L * 1024

        /** 相邻两期之间的下载间隔：避开官网对 program_download 取链的频率限制 */
        private const val EPISODE_GAP_MS = 8_000L

        /**
         * 限流后每段自动重试的最长等待：官网冷却可能长达 6 小时，直接挂一个 6 小时协程
         * 既易被系统杀掉也不必。裁成 30 分钟一段，到点 pump 一次、被 guard 拦下就再等一段
         * （冷却期不发请求，不会升档）；删任务/清队列/新入队都会取消这段等待。
         */
        private const val MAX_RETRY_WAIT_MS = 30L * 60 * 1000

        /** 一期约两小时、128kbps，估算 126MB，用于下载前的空间预检 */
        private const val ESTIMATED_BYTES = 130L * MB
    }
}

/**
 * 磁盘恢复的条目并入内存现有队列，按 episode id 去重、内存优先。
 *
 * [live] 是启动早期内存里已有的条目（自动下载/手动缓存刚入队甚至开下的），状态最新；
 * [restored] 是从 manifest 读回的。id 冲突时保留 [live]，只用 [restored] 补内存里没有的。
 * 纯函数，便于 JVM 单测钉死「读盘不能冲掉并发入队的条目」这条竞态。
 */
internal fun mergeRestoredEntries(
    live: List<DownloadManager.Entry>,
    restored: List<DownloadManager.Entry>
): List<DownloadManager.Entry> {
    val liveIds = live.mapTo(HashSet()) { it.episode.id }
    return live + restored.filterNot { it.episode.id in liveIds }
}

/**
 * 读盘恢复时允许清理 .part 残留的 id：仅磁盘独有、内存里没有的条目。
 *
 * 内存里已有同 id 的，说明启动早期已入队甚至已开下，worker 可能正在写它的 .part；
 * 这时删文件会让收尾 renameTo 失败，把刚保住的并发入队条目误标成 FAILED。
 * 与 [mergeRestoredEntries] 共用同一套 id 判定，避免「合并保住了、part 却被删了」的错位。
 * 纯函数，便于 JVM 单测钉死这条竞态。
 */
internal fun partIdsToCleanOnRestore(
    live: List<DownloadManager.Entry>,
    restored: List<DownloadManager.Entry>
): List<Long> {
    val liveIds = live.mapTo(HashSet()) { it.episode.id }
    return restored.map { it.episode.id }.distinct().filterNot { it in liveIds }
}

/**
 * 限流自动重试的单段等待时长：取官网剩余冷却，但夹在 [0, maxWait] 内。
 *
 * 纯函数便于 JVM 单测钉死两件事：冷却已过时不白等（返回 0，调用方立刻重排）；
 * 官网冷却 6 小时也只等一段 maxWait（到点再检查，不挂 6 小时协程）。
 */
internal fun retryDelayMs(remainingCooldownMs: Long, maxWaitMs: Long): Long =
    remainingCooldownMs.coerceIn(0L, maxWaitMs)
