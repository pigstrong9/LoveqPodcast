package cn.loveq.podcast.data

import cn.loveq.podcast.playback.PlaybackEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 启动时自动缓存要下哪几期。
 *
 * [episodes] 是所选分类的完整节目表，**新→旧**排布（官网列表就是这个顺序）。
 * 从当前那期（含）往更新的方向收集还没缓存的，最多 [limit] 期：这就是「从当前播放节目
 * 到最新、补全未缓存」。
 *
 * 当前那期不在表里 → 空列表。这条同时挡住两种不该下的情况：用户在别的分类听节目
 * （分类不匹配），以及当前期老到超出了我们拉取的范围。交给调用方「跳过本次」。
 *
 * 纯函数，便于 JVM 单测把边界（上限、已缓存、找不到当前期）钉死。
 */
internal fun autoDownloadTargets(
    episodes: List<Episode>,
    currentId: Long,
    alreadyHandled: Set<Long>,
    limit: Int
): List<Episode> {
    val start = episodes.indexOfFirst { it.id == currentId }
    if (start < 0 || limit <= 0) return emptyList()
    val picked = ArrayList<Episode>(limit.coerceAtMost(start + 1))
    // start 是当前期，往 0（最新）走就是「更新的节目」
    for (i in start downTo 0) {
        val e = episodes[i]
        if (e.id in alreadyHandled) continue
        picked += e
        if (picked.size >= limit) break
    }
    return picked
}

/**
 * 启动自动下载的编排：读开关与所选分类 → 找到当前那期 → 取该分类节目表 → 算目标 → 排队。
 *
 * 只在 ViewModel 构造时跑一次（转屏、切后台回来不重放），和启动自动续播同一条生命周期。
 * 失败一律静默收手：这是后台便利功能，不该在启动时用错误弹窗打断用户；
 * 下载本身的成功/失败由 DownloadManager 走既有的通知与设置页。
 */
class AutoDownloadCoordinator(
    private val store: ProgressStore,
    private val indexStore: EpisodeIndexStore,
    private val repository: LoveqRepository,
    private val downloads: DownloadManager
) {

    suspend fun run() {
        if (!store.autoDownload.value) return
        // 让首屏先画出来，别和索引增量刷新、首屏翻页挤在同一瞬间抢闸门
        delay(START_DELAY_MS)
        withContext(Dispatchers.IO) { runOnIo() }
    }

    private suspend fun runOnIo() {
        val category = Category.ALL.firstOrNull { it.id == store.autoDownloadCategory.value }
            ?: return
        val currentId = store.lastId.value ?: return
        val current = store.get(currentId) ?: return

        val episodes = when (category.id) {
            // 粤语有全库本地索引：一次请求都不用发就能算出整段区间
            Category.CANTONESE_ID -> {
                indexStore.episodes.value.ifEmpty {
                    // 冷启动时索引还在从磁盘读（ensureIndex 是异步的），稍等它一下；
                    // 全新安装还没建索引就等不到，本次跳过，下次启动再补
                    withTimeoutOrNull(INDEX_WAIT_MS) {
                        indexStore.episodes.first { it.isNotEmpty() }
                    }.orEmpty()
                }
            }
            // 其余分类没有全库索引：从最新一页往前翻到当前那期为止
            else -> loadRecentPages(category.id, current)
        }
        if (episodes.isEmpty()) return

        // 排队里的（含 FAILED）也算「已处理」：DownloadManager.enqueue 会跳过列表里已有的，
        // 重试留给设置页的手动按钮，不在启动时自作主张重下失败的
        val already = downloads.downloads.value.mapTo(HashSet()) { it.episode.id }
        val targets = autoDownloadTargets(episodes, currentId, already, MAX_EPISODES)
        if (targets.isEmpty()) return
        downloads.enqueue(targets)
        PlaybackEvents.report("已开始自动缓存 ${targets.size} 期《${category.name}》")
    }

    /**
     * 非粤语分类：从第 1 页往前翻，直到翻到当前那一期。
     *
     * 列表新→旧、日期形如 2026.01.01（字典序即时间序），所以一旦某期日期已经早于当前期
     * 还没找到当前 id，就说明当前听的这期不在这个分类里 → 返回空，由上层跳过本次。
     * 最多翻 [MAX_PAGES] 页：再老的「当前期」要翻太多页，不值得为后台功能去压官网。
     */
    private suspend fun loadRecentPages(catId: Int, current: Progress): List<Episode> {
        val acc = ArrayList<Episode>()
        try {
            for (page in 1..MAX_PAGES) {
                val result = repository.loadPage(page, catId)
                var passedCurrent = false
                for (e in result.episodes) {
                    if (e.id == current.id) passedCurrent = true
                    if (!passedCurrent && current.date.isNotEmpty() && e.date.isNotEmpty() &&
                        e.date < current.date
                    ) {
                        // 已经比当前期还旧却没遇到它 → 不属于这个分类
                        return emptyList()
                    }
                }
                acc += result.episodes
                if (passedCurrent || page >= result.totalPages) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 限流 / 登录墙 / 网络异常：本次不下，静默收手
            return emptyList()
        }
        return acc
    }

    companion object {
        /** 每次启动最多排进去的期数（一期约 126MB） */
        const val MAX_EPISODES = 5

        private const val START_DELAY_MS = 2_000L
        private const val INDEX_WAIT_MS = 5_000L
        private const val MAX_PAGES = 5
    }
}
