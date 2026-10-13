package cn.loveq.podcast.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 全部节目索引（id/日期/标题），供全库搜索使用。
 * 首次启动后台抓全部页面并缓存到磁盘；之后增量刷新——新节目只出现在前几页，
 * 遇到整页已知即停，通常只需 1 次请求。
 */
class EpisodeIndexStore(context: Context) {

    private val file = File(context.filesDir, "episode_index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var buildJob: Job? = null
    private var pagesDone = 0
    private var totalPagesKnown = 1

    fun ensureIndex(repository: LoveqRepository) {
        if (buildJob?.isActive == true || _episodes.value.isNotEmpty()) return
        buildJob = scope.launch {
            val cache = readCache()
            if (cache == null) {
                buildAll(repository, 1)
                return@launch
            }
            pagesDone = cache.pagesDone
            totalPagesKnown = cache.totalPages
            _episodes.value = cache.episodes
            if (pagesDone < totalPagesKnown) {
                buildAll(repository, pagesDone + 1)
            } else if (cache.savedAt < System.currentTimeMillis() - DAY_MS) {
                refreshIncremental(repository)
            }
        }
    }

    fun refresh(repository: LoveqRepository) {
        if (buildJob?.isActive == true) return
        buildJob = scope.launch {
            if (_episodes.value.isEmpty()) {
                val cache = readCache()
                if (cache != null) {
                    pagesDone = cache.pagesDone
                    totalPagesKnown = cache.totalPages
                    _episodes.value = cache.episodes
                }
            }
            when {
                _episodes.value.isEmpty() -> buildAll(repository, 1)
                pagesDone < totalPagesKnown -> buildAll(repository, pagesDone + 1)
                else -> refreshIncremental(repository)
            }
        }
    }

    private suspend fun buildAll(repository: LoveqRepository, startPage: Int) {
        _loading.value = true
        try {
            val all = _episodes.value.toMutableList()
            var page = startPage
            while (true) {
                val result = repository.loadPage(page)
                totalPagesKnown = result.totalPages
                all += result.episodes
                pagesDone = page
                _episodes.value = all.toList()
                writeCache(all, page, result.totalPages)
                if (page >= result.totalPages) break
                page++
                delay(REQUEST_DELAY_MS)
            }
        } catch (_: Exception) {
            // 已抓页面均已落盘，下次启动自动续抓
        } finally {
            _loading.value = false
        }
    }

    private suspend fun refreshIncremental(repository: LoveqRepository) {
        _loading.value = true
        try {
            val old = _episodes.value
            val oldIds = HashSet<Long>(old.size * 2).apply { old.forEach { add(it.id) } }
            val fetched = mutableListOf<Episode>()
            var page = 1
            while (page <= totalPagesKnown) {
                val result = repository.loadPage(page)
                totalPagesKnown = result.totalPages
                if (result.episodes.all { it.id in oldIds }) break
                fetched += result.episodes.filter { it.id !in oldIds }
                page++
                delay(REQUEST_DELAY_MS)
            }
            if (fetched.isNotEmpty()) {
                val fetchedIds = fetched.mapTo(HashSet()) { it.id }
                val merged = fetched + old.filterNot { it.id in fetchedIds }
                _episodes.value = merged
                pagesDone = totalPagesKnown
                writeCache(merged, pagesDone, totalPagesKnown)
            }
        } catch (_: Exception) {
        } finally {
            _loading.value = false
        }
    }

    private fun readCache(): Cache? = try {
        if (!file.exists()) null else {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("episodes") ?: return null
            val list = ArrayList<Episode>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.getLong("id")
                val date = e.optString("date")
                if (id > 0 && date.isNotEmpty()) {
                    list += Episode(id, date, e.optString("title").ifEmpty { date })
                }
            }
            if (list.isEmpty()) null
            else Cache(list, obj.optInt("pagesDone", 0), obj.optInt("totalPages", 1), obj.optLong("savedAt", 0L))
        }
    } catch (_: Exception) {
        null
    }

    private fun writeCache(episodes: List<Episode>, pagesDone: Int, totalPages: Int) {
        try {
            val arr = JSONArray()
            for (e in episodes) {
                arr.put(JSONObject().put("id", e.id).put("date", e.date).put("title", e.title))
            }
            val obj = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("pagesDone", pagesDone)
                .put("totalPages", totalPages)
                .put("episodes", arr)
            file.writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    private data class Cache(
        val episodes: List<Episode>,
        val pagesDone: Int,
        val totalPages: Int,
        val savedAt: Long
    )

    private companion object {
        const val DAY_MS = 24L * 3600 * 1000
        const val REQUEST_DELAY_MS = 200L
    }
}
