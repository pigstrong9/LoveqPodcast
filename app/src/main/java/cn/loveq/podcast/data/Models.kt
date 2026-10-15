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

        fun byId(id: Int): Category = ALL.first { it.id == id }
    }
}

data class Episode(
    val id: Long,
    val date: String,
    val title: String
) {
    val year: Int get() = date.substringBefore('.').toIntOrNull() ?: 0

    /** 日期形如 2026.10.12；缺月份时给 0，比较时自然排到最旧一侧 */
    val month: Int get() = date.split('.').getOrNull(1)?.toIntOrNull() ?: 0

    /** 年*100+月：同月内的排序与筛选键（列表整体是新→旧排布） */
    val yearMonth: Int get() = year * 100 + month
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
