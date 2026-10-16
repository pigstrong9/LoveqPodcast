package cn.loveq.podcast.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors
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

    /**
     * 车机是直接断电的，apply() 只保证内存立即可见、落盘交给系统后台线程，
     * 熄火时最近几次进度可能压根没写到闪存上。
     * 所以所有写都走这个单线程队列 + commit()：串行保证后写的不会先落盘，
     * commit() 保证函数返回时数据已经真正写下去。
     */
    private val writes = Executors.newSingleThreadExecutor()

    /**
     * 时长单独在内存里留一份：落盘改成异步后，prefs 的内存表要等队列任务跑完才更新，
     * 直接从 prefs 读会读到滞后值（「听完标记」紧跟在一次 save 之后，最容易踩到）。
     */
    private val durations = HashMap<Long, Long>()

    private val _progressMap = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    val progressMap: StateFlow<Map<Long, Progress>> = _progressMap

    private val _lastId = MutableStateFlow<Long?>(null)
    val lastId: StateFlow<Long?> = _lastId

    /**
     * 是否隐藏顶部状态栏（沉浸式）。
     *
     * 车机顶栏占着一条、又要全屏观感，所以默认隐藏（与历史行为一致）；
     * 手机/平板上要看到时间和通知图标，可以在设置里关掉。
     * 用 StateFlow 暴露：Activity 订阅它即时切换，不必重启界面。
     */
    private val _immersive = MutableStateFlow(prefs.getBoolean(KEY_IMMERSIVE, true))
    val immersive: StateFlow<Boolean> = _immersive

    fun setImmersive(hidden: Boolean) {
        _immersive.value = hidden
        persist(prefs.edit().putBoolean(KEY_IMMERSIVE, hidden))
    }

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
                key.startsWith(KEY_DUR) -> {
                    val id = key.removePrefix(KEY_DUR).toLongOrNull() ?: continue
                    val dur = (value as? Long) ?: 0L
                    if (dur > 0) durations[id] = dur
                }
            }
        }
        _progressMap.value = map
        _lastId.value = lastId
    }

    @Synchronized
    fun save(id: Long, positionMs: Long, durationMs: Long, title: String, date: String) {
        val pos = positionMs.coerceAtLeast(0)
        val dur = if (durationMs > 0) durationMs
        else durations[id] ?: prefs.getLong(KEY_DUR + id, 0L)
        if (dur > 0) durations[id] = dur
        val done = dur > 0 && pos >= dur - DONE_EPSILON
        val now = System.currentTimeMillis()
        persist(
            prefs.edit()
                .putLong(KEY_POS + id, pos)
                .putLong(KEY_DUR + id, dur)
                .putBoolean(KEY_DONE + id, done)
                .putLong(KEY_TIME + id, now)
                .putString(KEY_META + id, JSONObject().put("title", title).put("date", date).toString())
                .putLong(KEY_LAST, id)
        )
        _progressMap.value = _progressMap.value + (id to Progress(id, pos, dur, done, title, date, now))
        _lastId.value = id
        pruneIfNeeded()
    }

    @Synchronized
    fun markDone(id: Long, title: String, date: String) {
        val dur = durations[id] ?: prefs.getLong(KEY_DUR + id, 0L)
        if (dur > 0) durations[id] = dur
        val now = System.currentTimeMillis()
        persist(
            prefs.edit()
                .putLong(KEY_POS + id, dur)
                .putLong(KEY_DUR + id, dur)
                .putBoolean(KEY_DONE + id, true)
                .putLong(KEY_TIME + id, now)
                .putString(KEY_META + id, JSONObject().put("title", title).put("date", date).toString())
                .putLong(KEY_LAST, id)
        )
        _progressMap.value = _progressMap.value + (id to Progress(id, dur, dur, true, title, date, now))
        _lastId.value = id
        pruneIfNeeded()
    }

    /**
     * 历史裁剪：只留「在听」+ 最近 [MAX_DONE_ENTRIES] 条「听完」。
     *
     * 车机连开几年，每年一百多期，done_/time_/meta_ 这些 key 会越攒越多，
     * 每次启动 reload 和排序都越来越慢。听完的记录留最近几百条足够翻回看，
     * 「在听」（未听完）的一律保留，[ProgressStore.lastId] 指向的那期也绝不删。
     */
    @Synchronized
    private fun pruneIfNeeded() {
        val map = _progressMap.value
        // 快路径：总量还没到上限就不用扫
        if (map.size <= MAX_DONE_ENTRIES) return
        val victims = progressIdsToPrune(map.values, MAX_DONE_ENTRIES, _lastId.value)
        if (victims.isEmpty()) return
        val editor = prefs.edit()
        victims.forEach { id ->
            editor.remove(KEY_POS + id)
                .remove(KEY_DUR + id)
                .remove(KEY_DONE + id)
                .remove(KEY_META + id)
                .remove(KEY_TIME + id)
            durations.remove(id)
        }
        persist(editor)
        _progressMap.value = map.filterKeys { it !in victims }
    }

    /** 交给单线程队列落盘：既保证顺序，也保证真正写进闪存 */
    private fun persist(editor: SharedPreferences.Editor) {
        writes.execute { editor.commit() }
    }

    fun get(id: Long): Progress? = _progressMap.value[id]

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = persist(prefs.edit().putFloat(KEY_SPEED, value))

    /** 软件音量增强档位，100 表示不增强 */
    var boostPercent: Int
        get() = prefs.getInt(KEY_BOOST, 100)
        set(value) = persist(prefs.edit().putInt(KEY_BOOST, value))

    private companion object {
        const val KEY_POS = "pos_"
        const val KEY_DUR = "dur_"
        const val KEY_DONE = "done_"
        const val KEY_META = "meta_"
        const val KEY_TIME = "time_"
        const val KEY_LAST = "last_id"
        const val KEY_SPEED = "speed"
        const val KEY_BOOST = "boost"
        const val KEY_IMMERSIVE = "immersive"
        const val DONE_EPSILON = 30_000L

        /**
         * 历史里最多保留多少条「听完」的记录。超出的按 updatedAt 从旧到新裁掉。
         * 「在听」不受此上限约束。
         */
        const val MAX_DONE_ENTRIES = 300
    }
}

/**
 * 计算需要从进度表里裁掉的 id。
 *
 * 规则：保留全部「在听」、最近 [maxDone] 条「听完」，以及 [keepId]（当前正在收听的那一期，
 * 绝不裁掉，否则「继续收听」会指向一个不存在的进度）。纯函数，便于 JVM 单测。
 */
internal fun progressIdsToPrune(
    progresses: Collection<Progress>,
    maxDone: Int,
    keepId: Long?
): Set<Long> {
    val done = progresses.filter { it.done }
    if (done.size <= maxDone) return emptySet()
    val keep = done.sortedByDescending { it.updatedAt }.take(maxDone).mapTo(HashSet()) { it.id }
    if (keepId != null) keep.add(keepId)
    return done.filter { it.id !in keep }.mapTo(HashSet()) { it.id }
}
