package cn.loveq.podcast.ui

/**
 * 在分页归档里找「页内最新一期已经不晚于 target」的第一页。
 *
 * 网站列表按日期新→旧排布，所以「第 p 页最新一期的排序键」沿页码单调不增：
 * headKey 对 target 的判定沿页码呈「前 false 后 true」的阶梯，因此可以二分，
 * 不用像旧实现那样从第 1 页逐页往后翻（跳 2006 年要串 40 多页）。
 *
 * headKey 返回 null 表示该页没有内容（超出最后一页），按「太旧」处理往左收。
 *
 * guess 是全库索引换算出的候选页（第 pos 条约在第 pos/26+1 页）。它只负责加速：
 * 猜中就省掉二分，猜歪也只允许把搜索范围收到**已经用真实页首判定过**的一侧。
 * 早先的版本不做判定就写死 `hi = guess + 1`，索引少收几条时估算偏小，真边界
 * 直接被排除在范围外 —— 症状是 2023 年能跳、2010 年「没有找到」。
 */
internal suspend fun findTargetPage(
    target: Int,
    firstPage: Int,
    lastPage: Int,
    guess: Int?,
    headKey: suspend (Int) -> Int?
): Int {
    var lo = firstPage
    var hi = lastPage
    if (guess != null && firstPage <= lastPage) {
        val g = guess.coerceIn(firstPage, lastPage)
        val head = headKey(g)
        if (head != null) {
            if (head > target) {
                // 这页还比目标新 => 边界一定在右边，左半边可以整段丢掉
                lo = g + 1
            } else {
                val prev = if (g == firstPage) null else headKey(g - 1)
                // 上一页比目标新、这页已经够旧 => 这页就是边界页
                if (prev == null || prev > target) return g
                hi = g - 1
            }
        }
    }
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val head = headKey(mid)
        // 空页 = 已经翻过归档末尾，比任何目标都旧，算「已满足」继续往左找；
        // 当成不满足会把 lo 推过真正的边界页
        if (head == null || head <= target) hi = mid - 1 else lo = mid + 1
    }
    // lo 即第一个满足条件的页；越过 lastPage 表示归档里没有这一段
    return if (lo <= lastPage) lo else 0
}
