package cn.loveq.podcast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 播放进度与设置，全部存本地 SharedPreferences，无任何账号体系。
 * 进度按节目 id 记录：positionMs / durationMs / done / 标题 / 日期。
 */
class ProgressStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("loveq_progress", Context.MODE_PRIVATE)

    private val _progressMap = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    val progressMap: StateFlow<Map<Long, Progress>> = _progressMap

    private val _lastId = MutableStateFlow<Long?>(null)
    val lastId: StateFlow<Long?> = _lastId

    init {
        reload()
    }

    @Synchronized
    private fun reload() {
        val map = mutableMapOf<Long, Progress>()
        var lastId: Long? = null
        for ((key, value) in prefs.all) {
            when {
                key.startsWith(KEY_POS) -> {
                    val id = key.removePrefix(KEY_POS).toLongOrNull() ?: continue
                    val pos = (value as? Long) ?: 0L
                    val dur = prefs.getLong(KEY_DUR + id, 0L)
                    val done = prefs.getBoolean(KEY_DONE + id, false)
                    val meta = prefs.getString(KEY_META + id, null)
                    val metaJson = meta?.let { runCatching { JSONObject(it) }.getOrNull() }
                    map[id] = Progress(
                        id = id,
                        positionMs = pos,
                        durationMs = dur,
                        done = done,
                        title = metaJson?.optString("title") ?: "",
                        date = metaJson?.optString("date") ?: "",
                        updatedAt = prefs.getLong(KEY_TIME + id, 0L)
                    )
                }
                key == KEY_LAST -> lastId = (value as? Long)
            }
        }
        _progressMap.value = map
        _lastId.value = lastId
    }

    @Synchronized
    fun save(id: Long, positionMs: Long, durationMs: Long, title: String, date: String) {
        val pos = positionMs.coerceAtLeast(0)
        val dur = if (durationMs > 0) durationMs else prefs.getLong(KEY_DUR + id, 0L)
        val done = dur > 0 && pos >= dur - DONE_EPSILON
        prefs.edit()
            .putLong(KEY_POS + id, pos)
            .putLong(KEY_DUR + id, dur)
            .putBoolean(KEY_DONE + id, done)
            .putLong(KEY_TIME + id, System.currentTimeMillis())
            .putString(KEY_META + id, JSONObject().put("title", title).put("date", date).toString())
            .putLong(KEY_LAST, id)
            .apply()
        reload()
    }

    @Synchronized
    fun markDone(id: Long, title: String, date: String) {
        val dur = prefs.getLong(KEY_DUR + id, 0L)
        prefs.edit()
            .putLong(KEY_POS + id, dur)
            .putLong(KEY_DUR + id, dur)
            .putBoolean(KEY_DONE + id, true)
            .putLong(KEY_TIME + id, System.currentTimeMillis())
            .putString(KEY_META + id, JSONObject().put("title", title).put("date", date).toString())
            .putLong(KEY_LAST, id)
            .apply()
        reload()
    }

    fun get(id: Long): Progress? = _progressMap.value[id]

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    private companion object {
        const val KEY_POS = "pos_"
        const val KEY_DUR = "dur_"
        const val KEY_DONE = "done_"
        const val KEY_META = "meta_"
        const val KEY_TIME = "time_"
        const val KEY_LAST = "last_id"
        const val KEY_SPEED = "speed"
        const val DONE_EPSILON = 30_000L
    }
}
