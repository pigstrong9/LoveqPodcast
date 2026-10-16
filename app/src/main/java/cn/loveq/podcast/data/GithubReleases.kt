package cn.loveq.podcast.data

import java.io.IOException
import org.json.JSONObject

/** 一次可以安装的发布 */
data class ReleaseInfo(
    /** tag 去掉可选 v 前缀后的版本名，例如 "1.5" */
    val versionName: String,
    /** 选中资产的原始 GitHub 下载地址；镜像前缀由 UpdateDownloader 拼 */
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** 裸小写 hex；老 Release 没有 digest 时为 null */
    val sha256: String?,
    /** 更新说明原文，按纯文本展示 */
    val notes: String
)

/** 取不到可用的发布：网络全失败、响应不是 JSON、没有 apk 资产 */
internal class UpdateCheckException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * 从 releases/latest 的 JSON 里取出我们需要的字段。
 *
 * 抽成纯函数是为了能拿「真实的响应快照」当测试资源：解析是这里最容易出错的部分
 * （字段名、digest 前缀、资产挑选），但一旦要联网就测不动了。
 *
 * 返回 null 表示「这份响应里没有可安装的发布」，不是「网络出错」——
 * 后者由调用方按异常处理。两种情况在界面上的说法完全不同（「已是最新」vs「检查失败」）。
 */
internal fun parseLatestRelease(json: String): ReleaseInfo? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

    val tag = root.optString("tag_name").trim()
    val versionName = tag.removePrefix("v").removePrefix("V").trim()
    if (versionName.isEmpty()) return null
    // 注意：这里**不**校验 versionName 是不是合法版本号。
    // tag 写成 "latest" 这种时，ReleaseInfo 照样解析出来，由 VersionTags.isNewer
    // 判为「不可比 → 不提示升级」。这是「没有新版」，不是「检查失败」——
    // 在这里返回 null 会被上层当成网络错误报给用户，说法就错了。

    val assets = root.optJSONArray("assets") ?: return null
    var chosen: JSONObject? = null
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val name = asset.optString("name")
        if (!name.endsWith(".apk", ignoreCase = true)) continue
        // 历史 Release 有三种资产名（LoveqPodcast-v1.5.apk / app-release.apk /
        // LoveqPodcast-v1.0-20261014-release.apk）：优先挑名字里带 tag 的那个，
        // 都没有就退回第一个 apk
        if (name.contains(versionName)) {
            chosen = asset
            break
        }
        if (chosen == null) chosen = asset
    }
    val asset = chosen ?: return null

    val url = asset.optString("browser_download_url").trim()
    if (url.isEmpty()) return null

    // digest 形如 "sha256:<hex>"；没有这个字段时 optString 给出 ""，
    // 字段存在但值为 null 时给出字符串 "null" —— 两种都不是合法前缀
    val digest = asset.optString("digest").trim()
    val sha256 = if (digest.startsWith("sha256:", ignoreCase = true)) {
        digest.substring("sha256:".length).trim().lowercase().ifEmpty { null }
    } else {
        null
    }

    return ReleaseInfo(
        versionName = versionName,
        apkUrl = url,
        apkName = asset.optString("name"),
        apkSize = asset.optLong("size", 0L),
        sha256 = sha256,
        notes = root.optString("body").trim()
    )
}
