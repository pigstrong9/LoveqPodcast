package cn.loveq.podcast.data

import android.content.Context
import android.os.StatFs
import cn.loveq.podcast.playback.PlaybackEvents
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class DownloadManager(context: Context, private val repository: LoveqRepository) {

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
     * 队列状态（[_downloads]）会被两边同时改：UI 线程排队/删除，下载线程回写进度。
     * 所有「读出来算一遍再写回」的地方都必须在这把锁里，否则后写的会整片覆盖前者的结果
     * ——表现为选了三期只排进去两期。纯替换用的 [updateEntry] 走 StateFlow 的 CAS，无需加锁。
     */
    private val stateLock = Any()

    private var worker: Job? = null

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

    fun enqueue(episodes: List<Episode>) {
        val pending = episodes.filter { e ->
            _downloads.value.none { it.episode.id == e.id } && localFile(e.id) == null
        }
        if (pending.isEmpty()) return
        // 一期约 126MB（128kbps 两小时），车机存储不大；空间不够就明确拒绝，
        // 不能下到一半把设备塞满
        val need = pending.size * ESTIMATED_BYTES
        val free = StatFs(dir.absolutePath).availableBytes
        if (free < need) {
            PlaybackEvents.report(
                "空间不足：缓存 ${pending.size} 期约需 ${need / MB} MB，" +
                    "当前仅剩 ${free / MB} MB"
            )
            return
        }
        // CAS 追加：下载线程正在回写进度时也不能把刚排进来的期覆盖掉
        _downloads.update { list -> list + pending.map { Entry(it, State.QUEUED, 0, 0) } }
        persist()
        pump()
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

    @Volatile
    private var currentEpisodeId: Long = -1L

    /** 启动（或重启）一条串行流水线。必须在 [stateLock] 内调用 */
    private fun pump() {
        synchronized(stateLock) {
            if (worker?.isActive == true) return
            val pipeline = ++pipelineId
            worker = scope.launch { drain(pipeline) }
        }
    }

    /** 掐掉当前流水线。必须在 [stateLock] 内调用 */
    private fun stopPipelineLocked() {
        pipelineId++
        worker?.cancel()
        worker = null
    }

    /**
     * 串行推进整条队列：一期下完立刻接下一期，直到没有排队项。
     *
     * 写成循环而不是「下完一期后再 pump() 一次」——download() 就跑在 worker 自己的协程里，
     * 从它内部调 pump() 时 worker 仍然 isActive，pump 的守卫会直接返回，
     * 于是批量下载只下第一期，后面排队的永远不动（要等下次删除/重启才偶然接上下一期）。
     */
    private suspend fun drain(pipeline: Long) {
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
            download(next, pipeline)
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

    private suspend fun download(episode: Episode, pipeline: Long) {
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
            updateEntry(episode.id) { it.copy(state = State.FAILED) }
            // 被限流和普通失败要分开说：前者点重试没用，得等冷却过去
            PlaybackEvents.report(
                if (e is RateLimitedException) {
                    "《${episode.date}》离线缓存失败：官网暂时限制了访问，请稍后再试"
                } else {
                    "《${episode.date}》离线缓存失败，可点重试"
                }
            )
        } finally {
            persist()
            synchronized(stateLock) {
                // 只有仍归本流水线时才清，否则会把刚重启的流水线「当前期」误清空
                if (pipeline == pipelineId) currentEpisodeId = -1L
            }
        }
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
        // 磁盘上有完整文件的直接标 DONE（不重下）；清单说下完了但文件已不在的，重新排队
        synchronized(stateLock) {
            _downloads.value = list.map { entry ->
                File(dir, "${entry.episode.id}.mp3.part").delete()
                val f = localFile(entry.episode.id)
                when {
                    f != null -> entry.copy(state = State.DONE, bytes = f.length(), totalBytes = f.length())
                    entry.state == State.DONE -> entry.copy(state = State.QUEUED, bytes = 0)
                    else -> entry
                }
            }
        }
        persist()
        pump()
    }

    companion object {
        private const val PROGRESS_STEP = 256L * 1024
        private const val MB = 1024L * 1024

        /** 一期约两小时、128kbps，估算 126MB，用于下载前的空间预检 */
        private const val ESTIMATED_BYTES = 130L * MB
    }
}
