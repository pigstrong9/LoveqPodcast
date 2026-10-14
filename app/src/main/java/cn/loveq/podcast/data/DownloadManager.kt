package cn.loveq.podcast.data

import android.content.Context
import android.os.StatFs
import cn.loveq.podcast.playback.PlaybackEvents
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

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

    private var worker: Job? = null

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
            PlaybackEvents.tryEmit(
                "空间不足：缓存 ${pending.size} 期约需 ${need / MB} MB，" +
                    "当前仅剩 ${free / MB} MB"
            )
            return
        }
        _downloads.value = _downloads.value + pending.map { Entry(it, State.QUEUED, 0, 0) }
        persist()
        pump()
    }

    fun cancel(episodeId: Long) {
        if (currentEpisodeId == episodeId) worker?.cancel()
        _downloads.value = _downloads.value.filterNot { it.episode.id == episodeId }
        File(dir, "$episodeId.mp3.part").delete()
        persist()
        pump()
    }

    /** 听完自动删除，或用户在缓存页手动删除 */
    fun remove(episodeId: Long) {
        _downloads.value = _downloads.value.filterNot { it.episode.id == episodeId }
        File(dir, "$episodeId.mp3").delete()
        File(dir, "$episodeId.mp3.part").delete()
        persist()
        pump()
    }

    fun has(episodeId: Long): Boolean =
        localFile(episodeId) != null || _downloads.value.any { it.episode.id == episodeId }

    /** 已下载整集占用的空间（不含流式缓存） */
    fun usedBytes(): Long = dir.listFiles()?.sumOf { if (it.name.endsWith(".mp3")) it.length() else 0L } ?: 0L

    @Volatile
    private var currentEpisodeId: Long = -1L

    private fun pump() {
        if (worker?.isActive == true) return
        val next = _downloads.value.firstOrNull { it.state == State.QUEUED } ?: return
        _downloads.value = _downloads.value.map {
            if (it.episode.id == next.episode.id) it.copy(state = State.DOWNLOADING) else it
        }
        currentEpisodeId = next.episode.id
        worker = scope.launch { download(next.episode) }
    }

    private suspend fun download(episode: Episode) {
        val target = File(dir, "${episode.id}.mp3")
        val part = File(dir, "${episode.id}.mp3.part")
        try {
            val audio = repository.fetchAudio(episode.id)
            val request = Request.Builder().url(audio.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("空响应")
                val total = body.contentLength()
                update(episode.id) { it.copy(totalBytes = total) }
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
                                update(episode.id) { it.copy(bytes = written) }
                            }
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("重命名失败")
            // 下完不从列表里剔除：列表同时是「哪几期离线可听」的唯一数据源，
            // 列表徽标和缓存页都靠它
            update(episode.id) { it.copy(state = State.DONE, bytes = target.length()) }
        } catch (e: Exception) {
            part.delete()
            if (e is kotlinx.coroutines.CancellationException) {
                _downloads.value = _downloads.value.filterNot { it.episode.id == episode.id }
                throw e
            }
            update(episode.id) { it.copy(state = State.FAILED) }
            PlaybackEvents.tryEmit("《${episode.date}》离线缓存失败，可重试")
        } finally {
            persist()
            currentEpisodeId = -1L
            pump()
        }
    }

    private fun update(id: Long, transform: (Entry) -> Entry) {
        _downloads.value = _downloads.value.map {
            if (it.episode.id == id) transform(it) else it
        }
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
        _downloads.value = list.map { entry ->
            File(dir, "${entry.episode.id}.mp3.part").delete()
            val f = localFile(entry.episode.id)
            when {
                f != null -> entry.copy(state = State.DONE, bytes = f.length(), totalBytes = f.length())
                entry.state == State.DONE -> entry.copy(state = State.QUEUED, bytes = 0)
                else -> entry
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
