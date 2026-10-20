package cn.loveq.podcast.data

import android.content.Context
import android.content.SharedPreferences

/**
 * GitHub 的镜像源与轮换。
 *
 * **元数据（api.github.com）和资产（github.com）的可达性完全不同** ——
 * 2026-10-16 实测：ghfast.top 能下资产但对 api.github.com 返回 403，
 * 所以两条列表必须各排各的，不能共用。
 *
 * 源用「前缀」表示：空前缀就是直连，镜像前缀直接拼在原始 GitHub URL 前面。
 * 这样两类源共用同一套轮换逻辑，不必为「直连」写特例。
 *
 * 名单一定会腐坏：同日实测 agent.md 原先记录的 11 个全部失效，
 * 连上游项目文档里写的官方演示站 gh.api.99988866.xyz 都不通了。
 * 所以全部源都失败时必须能降级到「打开浏览器自己下」（见 UpdateCenter.openReleasePage）。
 */
internal object UpdateSources {

    /** 取 releases/latest。拼接后形如 https://api.github.com/repos/.../releases/latest */
    val API = listOf(
        // 直连排第一：实测最快最稳（0.65s），镜像反而慢一倍
        "",
        "https://gh-proxy.com/",
        "https://cdn.gh-proxy.com/"
    )

    /** 取 APK 资产。拼接后形如 https://github.com/.../releases/download/<tag>/<asset> */
    val ASSET = listOf(
        // 2026-10-20 实测：v1.7 全量 4.59MB / 5.76s，sha256 与 Release 一致。
        // 它不代理 api.github.com，所以只放资产源
        "https://gh.monlor.com/",
        // 镜像优先：实测直连 github.com 只有约 1/3 成功率，且失败要等约 21 秒超时
        "https://gh-proxy.com/",
        "https://cdn.gh-proxy.com/",
        "https://ghfast.top/",
        // 直连垫底：能直连的网络（海外、带代理）下它是最优的一跳。
        // 代价只是所有镜像都失败后多等一次超时，换来一条真实的兜底路径。
        // 不列 ghproxy.net：实测 120 秒只下到 4.04MB 就截断，是已知劣质源
        ""
    )

    /**
     * 把上次成功的源提到最前，其余保持原顺序。
     *
     * 车机在热点/4G 之间切换时，这一步能省掉一次必败的尝试。
     * 记下的源已经不在名单里（名单被更新过）就原样返回，不把它塞回来。
     */
    fun promoted(sources: List<String>, lastOk: String?): List<String> {
        if (lastOk == null || lastOk !in sources) return sources
        return listOf(lastOk) + sources.filter { it != lastOk }
    }
}

/**
 * 源轮换。仿 [RequestPacer]：纯逻辑、单独放一个文件、单独测。
 *
 * 只负责「按顺序给出下一个源」。**记住成功源不在这里**：那是 [SourceMemory] 的事
 * （只认 prefs），两套机制并存只会让「为什么下次还是先试坏源」多一种解释。
 *
 * 这里写错是安静的：试过的源又试一遍（每次白等一个超时），
 * 或者最后一个源之后不返回 null（调用方以为还有机会，实际卡在一个空循环里）。
 */
internal class SourceRotation(private val sources: List<String>) {

    private var index = 0

    /** 下一个待试的源；全部试完返回 null */
    fun next(): String? = if (index < sources.size) sources[index++] else null
}

/**
 * 记住「上次哪个源成功了」。
 *
 * 抽成接口是为了让 GithubReleases / UpdateDownloader 不必各自拿着 Context 去碰 prefs，
 * 记住什么、存在哪里只有一处。
 */
internal interface SourceMemory {
    fun lastOk(key: String): String?
    fun remember(key: String, source: String)
}

/**
 * 用单独的 prefs 文件，不混进 loveq_progress。
 *
 * 那个文件里是收听进度，车机断电时靠 commit() 保命；更新的镜像偏好丢了无所谓，
 * 混在一起只会让「进度为什么没存上」多一种可能。
 */
internal class PrefsSourceMemory(context: Context) : SourceMemory {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("loveq_update", Context.MODE_PRIVATE)

    override fun lastOk(key: String): String? = prefs.getString(key, null)

    override fun remember(key: String, source: String) {
        // 这里用 apply()：丢一次偏好只是下次多试一个源，不值得为它阻塞下载线程
        prefs.edit().putString(key, source).apply()
    }
}
