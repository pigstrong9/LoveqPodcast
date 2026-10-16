package cn.loveq.podcast.data

/**
 * 请求节奏控制：给每个调用者预约一个时间槽，相邻两槽至少隔 [MIN_REQUEST_INTERVAL_MS]。
 *
 * 这是「全局不超过 2 请求/秒」的全部实现，所以单独放一个文件、单独测：
 * 这类代码写错的方式都很安静 —— 忘了 `maxOf` 会给出负等待（调用方白睡，
 * `delay` 直接抛异常），忘了在旧槽上累加会让两个调用者拿到同一个槽（速率上限直接作废）。
 *
 * 线程安全由调用方负责（[LoveqRepository] 用一把 Mutex 把它包起来，只保护
 * 「算槽位」那一瞬间）：网络请求绝不能在这把锁里做，否则一次超时会把后面所有
 * 请求（包括用户正在等的翻页）全卡在锁上。
 */
internal class RequestPacer {

    /** 下一个可发请求的时间点 */
    private var nextSlotAtMs = 0L

    /**
     * 领一个槽位。
     *
     * @param now 当前时间，必须是**单调时钟**（`nanoTime` 换算的毫秒），不能用墙钟 ——
     *            墙钟会被 NTP 校正或用户改时间跳变，往回跳一下就是全局限速失效
     * @return 本次需要等待的毫秒数，0 表示立刻可发（空闲时不会白等）
     */
    fun reserve(now: Long): Long {
        val slot = maxOf(now, nextSlotAtMs)
        nextSlotAtMs = slot + MIN_REQUEST_INTERVAL_MS
        return slot - now
    }

    companion object {
        /**
         * 全局限速的最小间隔，兜底用：任何调用路径叠加起来也不会超过 2 请求/秒。
         *
         * 比批量抓取的 1500ms 松，是为了不把「用户点了播放」「跳到某一年」也拖慢 ——
         * 那些请求后面有人在等。
         */
        internal const val MIN_REQUEST_INTERVAL_MS = 500L
    }
}
