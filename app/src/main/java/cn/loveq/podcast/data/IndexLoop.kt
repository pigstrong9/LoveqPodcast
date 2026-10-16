package cn.loveq.podcast.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 索引推进循环：跑一轮 [advance]；若它说「官网在冷却，等 X 毫秒再试」，等完再跑一轮。
 *
 * 单独放一个文件、单独测，是因为它承载了一条不显眼的语义：**续抓必须保留发起者的原意**。
 * 上一版这里有个 `mode = true`（本意是「续抓时别白刷一次」），实际效果是把用户的下拉刷新
 * 降级成了启动语义 —— 在索引完整且新鲜时直接判定「无事可做」。用户被告知
 * 「约 30 分钟后自动恢复」，30 分钟后什么都没发生，而且日志里看不出任何异常。
 *
 * 现在 [respectTtl] 是循环自己持有的参数，每一轮原样交给 [advance]，
 * 没有可以中途改写的变量，那种 bug 写不出来了。
 *
 * @param respectTtl 语义标记，原样传给 [advance]：true = 缓存还新鲜就不刷（启动路径）；
 *                   false = 强制刷（下拉刷新）
 * @param advance 推进一轮。返回 null = 这一轮已经没事可做，循环结束；
 *                返回毫秒数 = 官网在冷却，等这么久再试一轮
 * @param sleep 等待实现。测试里换成记账的函数就能脱离真实时间
 */
internal suspend fun runIndexLoop(
    respectTtl: Boolean,
    advance: suspend (respectTtl: Boolean) -> Long?,
    sleep: suspend (Long) -> Unit = { delay(it) }
) {
    while (true) {
        val waitMs = try {
            advance(respectTtl) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // advance 内部各自兜底（解析失败、落盘失败都不会抛到这里），
            // 还能漏过来说明出了预期外的问题。既不能重试 —— 那可能是死循环，
            // 也不能让它冒泡 —— 从 launch 里冒出去的异常会交给 Android 的默认未捕获
            // 处理器（本项目的 CrashLog 会记上一笔，然后进程照样崩）。收手，
            // 下次启动 / 下拉刷新再续。
            return
        }
        sleep(waitMs)
    }
}
