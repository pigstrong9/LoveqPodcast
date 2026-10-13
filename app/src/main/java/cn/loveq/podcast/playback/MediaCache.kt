package cn.loveq.podcast.playback

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 收听缓存：SimpleCache 单例，LRU 淘汰，上限 2GB。
 * 缓存键去掉签名参数（sign/timestamp），签名刷新后同一期节目仍命中缓存。
 */
object MediaCache {

    private var cache: SimpleCache? = null

    @Volatile
    private var activeKey: String? = null

    @Synchronized
    fun get(context: Context): SimpleCache {
        return cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
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

    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
}
