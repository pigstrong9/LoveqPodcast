package cn.loveq.podcast.playback

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 收听缓存：SimpleCache 单例，LRU 淘汰。
 *
 * 上限不再是写死的 2GB，而是取可用空间的 1/4、最多 [HARD_CAP]（见 [cacheCapBytes]）：
 * 车机/平板/手机的存储差别很大，小存储设备上固定 2GB 会跟其他应用抢空间。
 *
 * 缓存键去掉签名参数（sign/timestamp），签名刷新后同一期节目仍命中缓存。
 */
object MediaCache {

    private var cache: SimpleCache? = null

    @Volatile
    private var activeKey: String? = null

    /**
     * 实际生效的缓存上限。首次 [get] 时按当时的可用空间算定，之后不再变
     * （SimpleCache 的淘汰器上限在构造时就固定了）。界面据此显示「上限 x GB」。
     */
    @Volatile
    var capBytes: Long = HARD_CAP
        private set

    @Synchronized
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        val cap = cacheCapBytes(freeSpaceBytes(context))
        capBytes = cap
        return SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(cap),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { cache = it }
    }

    fun keyOf(uri: Uri): String = uri.buildUpon().clearQuery().build().toString()

    fun setActiveKey(uri: Uri?) {
        activeKey = uri?.let { keyOf(it) }
    }

    @Synchronized
    fun sizeBytes(context: Context): Long = get(context).cacheSpace

    /** 清除全部缓存；正在收听的一期保留，避免打断播放 */
    @Synchronized
    fun clear(context: Context) {
        val c = get(context)
        val keep = activeKey
        c.keys.toList().forEach { key ->
            if (key != keep) c.removeResource(key)
        }
    }

    /** 硬上限：可用空间再多也不会超过这个值 */
    internal const val HARD_CAP = 2L * 1024 * 1024 * 1024
}

/** cacheDir 所在卷的可用空间；取不到时按 0 处理（[cacheCapBytes] 会退回 [MediaCache.HARD_CAP]） */
internal fun freeSpaceBytes(context: Context): Long =
    runCatching { context.applicationContext.cacheDir.usableSpace }.getOrDefault(0L)

/**
 * 缓存上限 = 可用空间的 1/4，且不超过 [MediaCache.HARD_CAP]。
 * 纯函数，便于 JVM 单测（不碰文件系统）。
 */
internal fun cacheCapBytes(freeBytes: Long): Long {
    if (freeBytes <= 0L) return MediaCache.HARD_CAP
    return minOf(MediaCache.HARD_CAP, freeBytes / 4)
}
