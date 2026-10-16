package cn.loveq.podcast.data

/**
 * 版本名的解析与比较 —— 「这个 Release 比我现在装的新吗」的唯一定义。
 *
 * 单独放一个文件、单独测：这里写错的方式都是安静的。
 * 用字符串比较会让 1.10 被当成比 1.9 旧（字典序），发了新版用户却收不到提示；
 * 把不可比的 tag（"1.5-beta"、"latest"）当成可解析，则可能把人引到一个
 * 不该升的版本上。
 *
 * 所以策略是单向的：**宁可漏一次提示，也不按猜测去升。**
 */
internal object VersionTags {

    /**
     * 解析 "v1.5" / "1.5" / "1.5.0" / " V1.5 " → [1,5] / [1,5] / [1,5,0]。
     *
     * 任一段不是纯数字就整体返回 null（"1.5-beta"、"1..5"、"v"、"latest"）。
     * 不做「把 beta 当 0」这类猜测：猜错的方向是让人装上一个更旧或不存在的版本。
     */
    fun parse(tag: String): List<Int>? {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return null
        val body = if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed.substring(1) else trimmed
        if (body.isEmpty()) return null

        val parts = body.split('.')
        val nums = ArrayList<Int>(parts.size)
        for (part in parts) {
            // toIntOrNull 也挡住空段、"1..5" 里的空串，以及 "5-beta" 这种带尾巴的
            val n = part.toIntOrNull() ?: return null
            if (n < 0) return null
            nums.add(n)
        }
        return nums
    }

    /**
     * 远程是否比本地新。
     *
     * 逐段比数字，短的一侧补 0 —— 所以 1.5 与 1.5.0 视为相同（1.5.0 不算更新）。
     * 任一侧解析不出都返回 false：本地版本名来自我们自己的构建，正常不会解析不出；
     * 真出现了说明有问题，这时候不提示比提示错更安全。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parse(remote) ?: return false
        val l = parse(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
