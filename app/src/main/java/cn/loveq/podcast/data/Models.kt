package cn.loveq.podcast.data

/** 官网节目分类，顺序即 Tab 展示顺序 */
data class Category(
    val id: Int,
    val name: String
) {
    companion object {
        const val CANTONESE_ID = 1

        val ALL = listOf(
            Category(1, "粤语节目"),
            Category(5, "每周一车"),
            Category(4, "得闲小叙"),
            Category(35, "Hugo's Story Time"),
            Category(3, "节目精华"),
            Category(38, "节目版头"),
            Category(2, "国语节目"),
            Category(0, "全部节目")
        )
    }
}

data class Episode(
    val id: Long,
    val date: String,
    val title: String
) {
    /**
     * 日期形如 2026.10.12。
     *
     * year / month / yearMonth 在列表年份分组、日期筛选、跳年份二分里被高频读取，
     * 构造时一次算好，避免每次访问都去切字符串（尤其 month 早先用 split('.')，
     * 每次都会新建一个 List）。解析不出来的字段给 0，比较时自然排到最旧一侧。
     */
    val year: Int
    val month: Int

    /** 年*100+月：同月内的排序与筛选键（列表整体是新→旧排布） */
    val yearMonth: Int

    init {
        val firstDot = date.indexOf('.')
        year = (if (firstDot >= 0) date.substring(0, firstDot) else date).toIntOrNull() ?: 0
        month = if (firstDot < 0) {
            0
        } else {
            val rest = date.substring(firstDot + 1)
            val secondDot = rest.indexOf('.')
            (if (secondDot >= 0) rest.substring(0, secondDot) else rest).toIntOrNull() ?: 0
        }
        yearMonth = year * 100 + month
    }
}

data class PageResult(
    val episodes: List<Episode>,
    val totalPages: Int
)

data class EpisodeAudio(
    val url: String,
    val title: String?
)

data class Progress(
    val id: Long,
    val positionMs: Long,
    val durationMs: Long,
    val done: Boolean,
    val title: String,
    val date: String,
    val updatedAt: Long
)

/**
 * 这一期还能接着听吗 —— 「听完」这条规则的唯一出处。
 *
 * 听完的一期有两层影响，必须统一：服务对听完的一期是从 **0** 重新播
 * （见 PlaybackService.startAtSavedPosition），所以听完的既不该在列表顶部给
 * 「继续播放」，也不该被启动自动续播接上。界面不提供的事，自动也不做。
 *
 * 因此列表（EpisodeList）和闸门（[shouldAutoResume]）都引这里，别各写各的 `!done`。
 */
internal val Progress.canResume: Boolean get() = !done
