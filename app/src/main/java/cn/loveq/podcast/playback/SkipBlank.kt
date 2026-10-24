package cn.loveq.podcast.playback

/** 跳过空白的默认与可调范围（对应用户确认的「片头片尾各一首歌，约 3 分钟」） */
object SkipBlankDefaults {
    const val MIN_MINUTES = 2
    const val MAX_MINUTES = 5
    const val MINUTES = 3
}

/**
 * 跳过空白：片头/片尾各跳 N 分钟。
 *
 * 这里只有纯计算，方便 JVM 单测；真正的起播/收尾在 [PlaybackService] 里做。
 *
 * 两条边界：
 * · 时长未知（duration <= 0）时不跳片尾，避免把「还没加载出时长」当成「已经放完」。
 * · 一期节目短于 2N 分钟时整期都不跳——首尾各砍 N 分钟会把内容切没，
 *   比如 5 分钟的短花絮在 3 分钟档位下只剩负数。
 */
object SkipBlank {

    /** 起播位置：保留已听进度，但若落在片头歌里就跳到 [minutes] 处 */
    fun startPositionMs(
        savedPositionMs: Long,
        enabled: Boolean,
        minutes: Int
    ): Long {
        if (!enabled || savedPositionMs < 0) return savedPositionMs.coerceAtLeast(0)
        val skip = minutesToMs(minutes)
        return savedPositionMs.coerceAtLeast(skip)
    }

    /**
     * 本集的可听终点；返回 0 表示这一期不启用片尾跳过
     * （时长未知，或短于首尾各跳 N 分钟）。
     */
    fun endPositionMs(
        durationMs: Long,
        enabled: Boolean,
        minutes: Int
    ): Long {
        if (!enabled || durationMs <= 0) return 0
        val skip = minutesToMs(minutes)
        if (durationMs <= skip * 2) return 0
        return durationMs - skip
    }

    /** 是否已经到达「可听终点」（片尾歌开始处） */
    fun reachedEnd(positionMs: Long, endPositionMs: Long): Boolean =
        endPositionMs > 0 && positionMs >= endPositionMs

    private fun minutesToMs(minutes: Int): Long =
        minutes.coerceIn(SkipBlankDefaults.MIN_MINUTES, SkipBlankDefaults.MAX_MINUTES) * 60_000L
}
