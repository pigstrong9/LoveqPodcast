package cn.loveq.podcast.playback

import android.content.Context
import cn.loveq.podcast.data.Episode
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连播队列落盘。
 *
 * 队列原本只在内存里：进程被回收后，从搜索/历史/跳年份进来的那一集就没了原来的
 * 连播上下文，退化成单集队列；进度保存也依赖队列能对得上 mediaId。
 * 车机是直接断电的，所以写盘沿用临时文件 + fsync + rename 的原子覆盖。
 */
class QueueStore(context: Context) {

    private val file = File(context.filesDir, "playback_queue.json")

    data class Snapshot(val episodes: List<Episode>, val startIndex: Int)

    fun save(episodes: List<Episode>, startIndex: Int) {
        if (episodes.isEmpty()) return
        try {
            val arr = JSONArray()
            episodes.forEach { e ->
                arr.put(JSONObject().put("id", e.id).put("date", e.date).put("title", e.title))
            }
            val obj = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("startIndex", startIndex.coerceIn(0, episodes.lastIndex))
                .put("episodes", arr)
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
        } catch (_: Exception) {
        }
    }

    fun load(): Snapshot? = try {
        if (!file.exists()) null else {
            val obj = JSONObject(file.readText())
            val arr = obj.optJSONArray("episodes")
            if (arr == null || arr.length() == 0) null else {
                val list = ArrayList<Episode>(arr.length())
                for (i in 0 until arr.length()) {
                    // 单条坏数据只跳过，不能像整份作废那样把队列丢掉
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optLong("id")
                    if (id > 0) list += Episode(id, e.optString("date"), e.optString("title"))
                }
                if (list.isEmpty()) null
                else Snapshot(list, obj.optInt("startIndex", 0).coerceIn(0, list.lastIndex))
            }
        }
    } catch (_: Exception) {
        null
    }
}
