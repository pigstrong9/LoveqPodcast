package cn.loveq.podcast.data

import android.content.Context
import cn.loveq.podcast.playback.PlaybackEvents
import kotlinx.coroutines.CancellationException
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
import java.io.FileOutputStream

/**
 * 全部节目索引（id/日期/标题），供全库搜索使用。
 * 首次启动后台抓全部页面并缓存到磁盘；之后增量刷新——新节目只出现在前几页，
 * 遇到整页已知即停，通常只需 1 次请求。
 */
class EpisodeIndexStore(context: Context, private val guard: RateLimitGuard) {

    private val file = File(context.filesDir, "episode_index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var buildJob: Job? = null
    private var pagesDone = 0
    private var totalPagesKnown = 1

    /** 缓存最后落盘时间，用来判断增量刷新还有没有必要 */
    private var cachedAt = 0L

    fun ensureIndex(repository: LoveqRepository) {
        if (buildJob?.isActive == true || _episodes.value.isNotEmpty()) return
        startIndexing(repository, respectTtl = true)
    }

    fun refresh(repository: LoveqRepository) {
        if (buildJob?.isActive == true) {
            // 推进循环已经在跑（可能正卡在冷却等待里）：给一次反馈，
            // 但绝不另起一个任务去撞已经被拒的接口
            if (guard.isBlocked()) announceCooldownOnce()
            return
        }
        startIndexing(repository, respectTtl = false)
    }

    /**
     * 索引推进循环。
     *
     * 冷却时不是「另排一个新任务」，而是在**同一个 job 里等** —— 这样 buildJob 只有一处
     * 赋值，不会出现「协程体里改了 buildJob、外面 `buildJob = launch{}` 又覆盖回去」的竞态
     * （那样会把续抓任务的引用丢掉）。
     *
     * 反复被拒也没关系：冷却阶梯 6 小时封顶，一天最多几次请求，是礼貌重试，不是敲门。
     */
    private fun startIndexing(repository: LoveqRepository, respectTtl: Boolean) {
        buildJob = scope.launch {
            var mode = respectTtl
            while (true) {
                // null = 这次没事可做了；否则是「等这么久再来」
                val waitMs = advanceIndex(repository, mode) ?: return@launch
                delay(waitMs)
                // 续抓一律按启动语义：这期间索引可能已经完整，不该无条件再刷一次
                mode = true
            }
        }
    }

    /**
     * 推进一轮：先把磁盘缓存读进来，再决定补齐断点 / 增量刷新 / 什么都不做。
     *
     * @param respectTtl 启动路径为 true（缓存还新鲜就不刷，这就是「平时零请求」的来源）；
     *                   下拉刷新为 false（强制走一次增量）
     * @return 需要等多久再续（官网冷却中）；null 表示这一轮已经没事可做
     */
    private suspend fun advanceIndex(repository: LoveqRepository, respectTtl: Boolean): Long? {
        // 缓存无论如何都要读进来：冷却意味着「不发请求」，不等于「让搜索空着」
        if (_episodes.value.isEmpty()) {
            readCache()?.let { cache ->
                pagesDone = cache.pagesDone
                totalPagesKnown = cache.totalPages
                _episodes.value = cache.episodes
                cachedAt = cache.savedAt
            }
        }

        // 索引完整又还新鲜：启动时直接收工，一次请求都不发。
        // 放在冷却检查之前 —— 这种情况本来就无事可做，不该报「索引暂停更新」
        val incomplete = _episodes.value.isEmpty() || pagesDone < totalPagesKnown
        if (!incomplete && respectTtl && cachedAt >= System.currentTimeMillis() - DAY_MS) return null

        if (guard.isBlocked()) {
            announceCooldownOnce()
            return guard.blockedForMs() + RESUME_MARGIN_MS
        }

        return try {
            when {
                _episodes.value.isEmpty() -> buildAll(repository, 1)
                pagesDone < totalPagesKnown -> buildAll(repository, pagesDone + 1)
                else -> refreshIncremental(repository)
            }
            null
        } catch (_: RateLimitedException) {
            // 被官网拒了：已抓页面都已落盘，剩下的等冷却过了再续。
            // 必须报出来 —— 静默吞掉会让列表和搜索莫名少一截，用户完全无从判断
            announceCooldownOnce()
            guard.blockedForMs() + RESUME_MARGIN_MS
        }
    }

    /**
     * 冷却期内说一声。
     *
     * 索引不全时分类和搜索都会缺节目；原来的静默跳过会让用户只看到
     * 「列表莫名其妙少了一段」，既不知道该等，也不知道不是应用坏了。
     *
     * 同一段冷却只说一次：自动续抓会反复撞它，用户也可能连点几下下拉刷新。
     * 这里必须真的报出「等多久」——而且外层循环真的会到点续抓，不是空头承诺。
     */
    private fun announceCooldownOnce() {
        if (!guard.shouldAnnounceCooldown()) return
        PlaybackEvents.report(
            "官网暂时限制了访问，节目索引暂停更新，约 ${guard.describeRemaining()}后自动恢复"
        )
    }

    private suspend fun buildAll(repository: LoveqRepository, startPage: Int) {
        _loading.value = true
        try {
            val all = _episodes.value.toMutableList()
            // 断点续抓时官网可能已插入新节目、整表整体后移，续抓页与缓存页会重叠，
            // 按 id 去重避免索引里出现重复项（重复 id 会让搜索列表的 LazyColumn key 冲突崩溃）
            val seen = all.mapTo(HashSet<Long>(all.size * 2)) { it.id }
            var page = startPage
            var sinceFlush = 0
            // 一次会话最多抓这么多页：全量 90 多页一口气打完，本身就是一次明显突发。
            // 拆成两次（索引先能用上，下次启动或下拉刷新再补齐）对官网温和，对用户也没损失
            var fetchedThisRun = 0
            while (true) {
                val result = repository.loadPage(page)
                totalPagesKnown = result.totalPages
                for (episode in result.episodes) {
                    if (seen.add(episode.id)) all += episode
                }
                pagesDone = page
                _episodes.value = all.toList()
                // 每页都全量落盘 + fsync 太伤闪存（全量 90 多页就是 90 多次），
                // 攒几页写一次、末页必写。断电最多丢 CACHE_FLUSH_EVERY 页，
                // 下次启动从文件里的 pagesDone 续抓，不会重来
                if (++sinceFlush >= CACHE_FLUSH_EVERY || page >= result.totalPages) {
                    writeCache(all, page, result.totalPages)
                    sinceFlush = 0
                }
                if (page >= result.totalPages) break
                if (++fetchedThisRun >= MAX_PAGES_PER_RUN) {
                    // 本轮到此为止，进度已落盘，下次启动/下拉刷新续抓
                    writeCache(all, page, result.totalPages)
                    break
                }
                page++
                delay(LoveqRepository.CRAWL_DELAY_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            // 交给 advanceIndex：由它统一提示 + 排下一次续抓，别在这里各写一份
            throw e
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
                delay(LoveqRepository.CRAWL_DELAY_MS)
            }
            if (fetched.isNotEmpty()) {
                val fetchedIds = fetched.mapTo(HashSet()) { it.id }
                val merged = fetched + old.filterNot { it.id in fetchedIds }
                _episodes.value = merged
                pagesDone = totalPagesKnown
                writeCache(merged, pagesDone, totalPagesKnown)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            throw e
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
            // 车机是直接断电的：先写临时文件并 fsync，再原子 rename 覆盖。
            // 否则断电瞬间可能留下半截 JSON，下次启动解析失败 → 缓存作废、全量重抓。
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(obj.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            cachedAt = System.currentTimeMillis()
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

        /** 攒够这么多页才落一次盘（末页必写）；断电容忍度见 buildAll 里的注释 */
        const val CACHE_FLUSH_EVERY = 5

        /**
         * 单次会话的最大抓取页数。全量约 90 多页，45 页意味着一个全新安装
         * 分两次把索引建完（中间那次启动就能搜到一半的节目），
         * 而不是开机头两分钟连打 90 个请求。
         */
        const val MAX_PAGES_PER_RUN = 45

        /**
         * 到点续抓时多等一会儿再发。
         *
         * 贴着冷却结束的瞬间打过去，很容易正好撞在官网那侧的计时器边界上
         * （我们和它的时钟不可能一致），白吃一次 405 还把冷却升一档。
         */
        const val RESUME_MARGIN_MS = 60_000L
    }
}
