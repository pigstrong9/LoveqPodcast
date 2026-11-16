package cn.loveq.podcast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 官网登录态。
 *
 * 官网从 2026-11-16 起给 `program_download-*.html` 加了登录墙：未登录请求会
 * 302 到 `https://loveq.cn/user-login.html`。这里只保存官网下发的 Cookie 和
 * 登录用户名，**不保存密码** —— 密码只在登录请求里用一次。
 *
 * Cookie 用一颗 `Cookie:` 头保存，不按域名/路径分桶：本应用只访问
 * `www.loveq.cn`，等真的需要访问多个官网子域时再换成 OkHttp CookieJar。
 */
class LoveqSession(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, null))
    private val _lastUsername = MutableStateFlow(prefs.getString(KEY_LAST_USERNAME, null))

    /** 登录成功的用户名；null 表示还没登录，或官网要求重新登录 */
    val username: StateFlow<String?> = _username

    /**
     * 上次登录过的用户名，只用于登录框回填。
     *
     * 和 [username] 分开：登录过期时 [onAuthRequired] 会清掉登录态，
     * 但账号栏不该跟着变空，否则用户每次过期都要重新敲一遍账号。
     */
    val lastUsername: StateFlow<String?> = _lastUsername

    @Synchronized
    fun cookieHeader(): String? = prefs.getString(KEY_COOKIE, null)

    /** 从任意响应的 Set-Cookie 里更新会话 Cookie（匿名 LQ_ID 也要收） */
    @Synchronized
    fun saveCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val merged = CookieHeader.merge(prefs.getString(KEY_COOKIE, null), setCookies)
        prefs.edit().putString(KEY_COOKIE, merged).apply()
    }

    @Synchronized
    fun markLoggedIn(name: String) {
        _username.value = name
        _lastUsername.value = name
        prefs.edit()
            .putString(KEY_USERNAME, name)
            .putString(KEY_LAST_USERNAME, name)
            .apply()
    }

    /**
     * 官网要求重新登录。
     *
     * 不清 Cookie：有时只是这一页要求登录，清掉反而让刚拿到的匿名会话作废。
     * 状态回到「未登录」，下次请求被拒时继续弹登录框。
     */
    @Synchronized
    fun onAuthRequired() {
        if (_username.value == null) return
        _username.value = null
        prefs.edit().remove(KEY_USERNAME).apply()
    }

    @Synchronized
    fun logout() {
        _username.value = null
        // 只清会话，保留上次用户名供下次登录回填
        prefs.edit()
            .remove(KEY_COOKIE)
            .remove(KEY_USERNAME)
            .apply()
    }

    private companion object {
        const val PREFS = "loveq_session"
        const val KEY_COOKIE = "cookie"
        const val KEY_USERNAME = "username"
        const val KEY_LAST_USERNAME = "last_username"
    }
}

/**
 * Cookie 头合并规则抽成纯函数，JVM 单测不用 Android。
 *
 * 只保留 `name=value`，丢掉 Path/Domain/Expires 等属性：请求只发往
 * `www.loveq.cn`，一颗头够用。服务端用空值或 `deleted` 表示删除，
 * 带 `Max-Age=0` 的也要按删除处理。
 */
internal object CookieHeader {

    fun merge(existing: String?, setCookies: List<String>): String {
        val map = linkedMapOf<String, String>()
        parseHeader(existing).forEach { (name, value) -> map[name] = value }

        setCookies.forEach { raw ->
            val lower = raw.lowercase()
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) return@forEach
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty()) return@forEach
            if (lower.contains("max-age=0") ||
                value.isEmpty() ||
                value.equals("deleted", ignoreCase = true)
            ) {
                map.remove(name)
            } else {
                map[name] = value
            }
        }

        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parseHeader(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(';').mapNotNull { part ->
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) {
                null
            } else {
                pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
            }
        }.toMap()
    }
}
