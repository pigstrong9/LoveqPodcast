package cn.loveq.podcast.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已加载过的节目列表落盘，按「分类 + 年 + 月」一格一个文件（month = 0 表示整年）。
 *
 * 节目列表只会往后新增，历史月份的标题、日期不会变：加载过一次之后再选到同一个
 * 月份就没必要再打官网。当前月/当前年还在增长，由 [isStable] 排除在缓存之外。
 *
 * 一格一个文件而不是塞进一个大 JSON：某个文件坏了只丢那一段；写入也沿用
 * 临时文件 + fsync + rename，车机直接断电也不会留下半截数据。
 */
class EpisodeCacheStore(context: Context) {

    private val dir = File(context.filesDir, "episode_cache").apply { mkdirs() }

    data class Entry(
        val episodes: List<Episode>,
        val totalPages: Int,
        val savedAt: Long
    )

    /** 只有已经过去的月份/年份才信任缓存；当前月（年）还在更新，必须重新取 */
    fun isStable(year: Int, month: Int, now: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val curYear = cal.get(Calendar.YEAR)
        val curMonth = cal.get(Calendar.MONTH) + 1
        return if (month == 0) year < curYear else year * 100 + month < curYear * 100 + curMonth
    }

    @Synchronized
    fun load(catId: Int, year: Int, month: Int): Entry? = try {
        val f = file(catId, year, month)
        if (!f.exists()) null else {
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("episodes") ?: return null
            val seen = HashSet<Long>(arr.length() * 2)
            val list = ArrayList<Episode>(arr.length())
            for (i in 0 until arr.length()) {
                // 单条坏数据只跳过，不能让整格缓存作废
                val e = arr.optJSONObject(i) ?: continue
                val id = e.optLong("id")
                val date = e.optString("date")
                if (id <= 0 || date.isEmpty() || !seen.add(id)) continue
                val episode = Episode(id, date, e.optString("title").ifEmpty { date })
                // 防串格：文件内容必须真的属于这个时段
                val matches = if (month == 0) episode.year == year
                else episode.yearMonth == year * 100 + month
                if (matches) list += episode
            }
            if (list.isEmpty()) null
            else Entry(list, obj.optInt("totalPages", 1), obj.optLong("savedAt", 0L))
        }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun save(catId: Int, year: Int, month: Int, episodes: List<Episode>, totalPages: Int) {
        // 当前月/年还在更新，写进去以后会被当成「完整的一段」读到，
        // 结果就是月中缓存下前几期、月底之后再也看不到后半段
        if (episodes.isEmpty() || !isStable(year, month)) return
        try {
            dir.mkdirs()
            val arr = JSONArray()
            episodes.forEach { e ->
                arr.put(JSONObject().put("id", e.id).put("date", e.date).put("title", e.title))
            }
            val obj = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("totalPages", totalPages)
                .put("episodes", arr)
            val target = file(catId, year, month)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(obj.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun file(catId: Int, year: Int, month: Int) = File(dir, "$catId-$year-$month.json")
}
