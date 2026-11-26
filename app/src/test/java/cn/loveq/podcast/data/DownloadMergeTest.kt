package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动读盘与入队的竞态回归测试。
 *
 * 车机上 load() 读 manifest + 逐条 localFile() 可能慢于启动自动下载的 2 秒延时：
 * 自动下载先把多集入队，load() 随后才完成。旧实现用 restored 整片覆盖，
 * 刚入队的条目被抹掉，表现就是「批量下载只下到一期」。
 */
class DownloadMergeTest {

    private fun entry(id: Long, state: DownloadManager.State) = DownloadManager.Entry(
        episode = Episode(id, "2026.01.01", "t$id"),
        state = state,
        bytes = 0,
        totalBytes = 0
    )

    @Test
    fun `读盘完成时内存里新入队的条目全部保留`() {
        // 自动下载在 load 读盘期间入队了 5 集
        val live = listOf(
            entry(10, DownloadManager.State.QUEUED),
            entry(11, DownloadManager.State.QUEUED),
            entry(12, DownloadManager.State.QUEUED),
            entry(13, DownloadManager.State.QUEUED),
            entry(14, DownloadManager.State.QUEUED)
        )
        // manifest 里是上次的旧记录
        val restored = listOf(entry(3, DownloadManager.State.DONE))

        val merged = mergeRestoredEntries(live, restored)

        assertEquals(6, merged.size)
        assertEquals(live + restored, merged)
    }

    @Test
    fun `同一期内存状态比磁盘新——以内存为准不被覆盖`() {
        // 内存里第 10 集已开下（DOWNLOADING），磁盘清单还记着上次 DONE
        val live = listOf(entry(10, DownloadManager.State.DOWNLOADING))
        val restored = listOf(entry(10, DownloadManager.State.DONE))

        val merged = mergeRestoredEntries(live, restored)

        assertEquals(1, merged.size)
        assertEquals(DownloadManager.State.DOWNLOADING, merged[0].state)
    }

    @Test
    fun `磁盘独有的条目补进队列，顺序在内存条目之后`() {
        val live = listOf(entry(20, DownloadManager.State.DOWNLOADING))
        val restored = listOf(
            entry(1, DownloadManager.State.DONE),
            entry(2, DownloadManager.State.QUEUED)
        )

        assertEquals(
            listOf(entry(20, DownloadManager.State.DOWNLOADING), entry(1, DownloadManager.State.DONE), entry(2, DownloadManager.State.QUEUED)),
            mergeRestoredEntries(live, restored)
        )
    }

    @Test
    fun `内存为空时等价于直接用磁盘恢复结果`() {
        val restored = listOf(
            entry(1, DownloadManager.State.DONE),
            entry(2, DownloadManager.State.QUEUED)
        )
        assertEquals(restored, mergeRestoredEntries(emptyList(), restored))
    }

    @Test
    fun `两侧都为空——结果为空`() {
        assertEquals(emptyList<DownloadManager.Entry>(), mergeRestoredEntries(emptyList(), emptyList()))
    }

    @Test
    fun `限流重试等待取剩余冷却——不超过上限`() {
        val max = 30L * 60 * 1000
        assertEquals(8_000L, retryDelayMs(8_000L, max))
        assertEquals(max, retryDelayMs(6L * 60 * 60 * 1000, max))
    }

    @Test
    fun `冷却已过——不等，也不出现负等待`() {
        val max = 30L * 60 * 1000
        assertEquals(0L, retryDelayMs(0L, max))
        assertEquals(0L, retryDelayMs(-5_000L, max))
    }

    @Test
    fun `读盘恢复只清磁盘独有条目的part——内存已入队的同id不删`() {
        // 内存里第 10 集已开下，worker 正在写它的 .part；磁盘清单也记着这一集。
        // 若把它的 .part 删掉，收尾 renameTo 失败，刚保住的并发入队条目被误标 FAILED。
        val live = listOf(entry(10, DownloadManager.State.DOWNLOADING))
        val restored = listOf(entry(10, DownloadManager.State.QUEUED), entry(11, DownloadManager.State.QUEUED))

        assertEquals(listOf(11L), partIdsToCleanOnRestore(live, restored))
    }

    @Test
    fun `part清理集合与合并结果一致——被合并保住的id绝不出现在清理集合里`() {
        val live = listOf(entry(20, DownloadManager.State.DOWNLOADING), entry(21, DownloadManager.State.QUEUED))
        val restored = listOf(
            entry(20, DownloadManager.State.DONE),
            entry(21, DownloadManager.State.DONE),
            entry(1, DownloadManager.State.DONE)
        )

        val toClean = partIdsToCleanOnRestore(live, restored)
        val mergedIds = mergeRestoredEntries(live, restored).map { it.episode.id }
        // 清理集合与合并新增项完全对应，且与内存条目无交集
        assertEquals(listOf(1L), toClean)
        assertTrue(toClean.all { it in mergedIds })
        assertTrue(toClean.none { it in live.map { e -> e.episode.id } })
    }
}
