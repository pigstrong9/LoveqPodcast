package cn.loveq.podcast.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * 官网明确拒绝（405 / 403 / 429）。
 *
 * 单独一个类型，是为了和「网络抖动」彻底分开：前者重试多少次都没用、越试越糟，
 * 后者才值得退避重连。混在同一个 IOException 里，调用方只能一律当成网络问题处理。
 *
 * [code] 只在日志/诊断里有意义：本地冷却时不会真的发出请求，那时填 0。
 */
class RateLimitedException(val code: Int, url: String) : IOException("HTTP $code - $url")

/**
 * 只存两个数字。
 *
 * 不用 SharedPreferences 本身，是因为要测「冷却能不能跨重启」就得把它整份实现一遍，
 * 而这里要的只是两个 long。
 */
interface RateLimitStore {
    fun readLong(key: String, def: Long): Long
    fun writeLong(key: String, value: Long)
}

/**
 * 官网限流状态与冷却。
 *
 * 存在的理由：这个状态必须**跨进程重启**。原来的行为是「被 405 → 静默中断 →
 * 下次开 App 立刻从断点再抓一次」，用户重启几次就把自己反复钉在封禁里 ——
 * 那已经不是「频繁访问」，而是被拒之后还在继续敲门。
 *
 * 冷却时长按连续被拒次数递增（30min → 6h），任何一次成功立刻清零。
 * 刻意不做随机抖动：稳定的慢节奏是「守规矩」的信号；抖动更像在躲检测，
 * 那是另一码事，不该出现在这里。
 */
class RateLimitGuard(
    private val store: RateLimitStore?,
    private val clock: () -> Long = System::currentTimeMillis
) {

    constructor(context: Context) : this(
        PrefsStore(context.getSharedPreferences("loveq_rate_limit", Context.MODE_PRIVATE))
    )

    /** 冷却到什么时候（epoch ms）。0 表示正常 */
    @Volatile
    private var untilMs: Long = store?.readLong(KEY_UNTIL, 0L) ?: 0L

    /** 连续被拒次数，决定退避档位 */
    @Volatile
    private var strikes: Long = store?.readLong(KEY_STRIKES, 0L) ?: 0L

    fun isBlocked(): Boolean = blockedForMs() > 0L

    /** 冷却剩余毫秒数，未冷却时为 0 */
    fun blockedForMs(): Long = (untilMs - clock()).coerceAtLeast(0L)

    /**
     * 被官网拒了一次：进入或延长冷却，并升一档退避。
     * 返回本次冷却时长，调用方可以直接拿去提示用户。
     */
    fun onRateLimited(): Long {
        val n = strikes + 1
        val cooldown = cooldownFor(n)
        strikes = n
        untilMs = clock() + cooldown
        store?.let {
            it.writeLong(KEY_STRIKES, n)
            it.writeLong(KEY_UNTIL, untilMs)
        }
        return cooldown
    }

    /** 请求成功：冷却立即解除，退避档位归零 */
    fun onSuccess() {
        if (untilMs == 0L && strikes == 0L) return
        strikes = 0L
        untilMs = 0L
        store?.let {
            it.writeLong(KEY_STRIKES, 0L)
            it.writeLong(KEY_UNTIL, 0L)
        }
    }

    /** 剩余冷却时间的中文描述，空串表示没在冷却 */
    fun describeRemaining(): String {
        val ms = blockedForMs()
        if (ms <= 0L) return ""
        val minutes = (ms + 59_999L) / 60_000L
        return when {
            minutes < 60L -> "$minutes 分钟"
            minutes % 60L == 0L -> "${minutes / 60L} 小时"
            else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
        }
    }

    private fun cooldownFor(strike: Long): Long = when {
        strike <= 1L -> 30 * MINUTE
        strike == 2L -> 60 * MINUTE
        strike == 3L -> 2 * HOUR
        strike == 4L -> 4 * HOUR
        else -> 6 * HOUR
    }

    private class PrefsStore(private val prefs: SharedPreferences) : RateLimitStore {
        override fun readLong(key: String, def: Long): Long = prefs.getLong(key, def)
        override fun writeLong(key: String, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }
    }

    private companion object {
        const val KEY_UNTIL = "blocked_until"
        const val KEY_STRIKES = "strikes"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
