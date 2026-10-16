package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * releases/latest 的解析。
 *
 * 用真实的响应快照当输入：字段名写错、digest 的 "sha256:" 前缀没去掉、
 * 资产挑错了，这些都不会抛异常，只会安静地给出一个错的值。
 */
class ReleaseParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "测试资源缺失：$name"
        }.use { it.readBytes().decodeToString() }

    @Test
    fun `从真实响应里取出 tag 资产与哈希`() {
        val info = checkNotNull(parseLatestRelease(fixture("latest_v1_5.json")))

        assertEquals("1.5", info.versionName)          // v 前缀要去掉
        assertEquals("LoveqPodcast-v1.5.apk", info.apkName)
        assertEquals(4_536_121L, info.apkSize)
        assertEquals(
            "6b9ba13f3e743ed8e0bf9630019e5315e138a1efc454d3f56a88558b00695962",
            info.sha256
        )
        assertTrue(info.apkUrl.startsWith("https://github.com/"))
        assertTrue(info.apkUrl.endsWith("LoveqPodcast-v1.5.apk"))
        assertTrue(info.notes.isNotEmpty())
    }

    @Test
    fun `哈希是小写裸 hex，不带 sha256 前缀`() {
        // 忘了去前缀的话，后面跟「边下边算出来的哈希」比对永远不等，
        // 表现为「每个源都报哈希不符」，非常难查
        val info = checkNotNull(parseLatestRelease(fixture("latest_v1_5.json")))
        assertTrue(!info.sha256!!.contains(":"))
        assertEquals(info.sha256, info.sha256!!.lowercase())
    }

    @Test
    fun `digest 前缀与 hex 都大写时也能取出小写哈希`() {
        // 上面那条其实测不到 .lowercase()：真实快照里的 digest 本来就是小写，
        // 归一化是个空操作。这条把人造的「大写 digest」喂进去，才真正钉住这一步 ——
        // 否则哪天上游回一个大写 digest，哈希比对会全线不通过，而测试还是绿的。
        val original = fixture("latest_v1_5.json")
        val json = original.replace(
            "\"digest\":\"sha256:6b9ba13f3e743ed8e0bf9630019e5315e138a1efc454d3f56a88558b00695962\"",
            "\"digest\":\"SHA256:6B9BA13F3E743ED8E0BF9630019E5315E138A1EFC454D3F56A88558B00695962\""
        )
        assertTrue("替换没生效（快照格式变了？检查 digest 的精确原文）", json != original)

        val info = checkNotNull(parseLatestRelease(json))
        assertEquals(
            "6b9ba13f3e743ed8e0bf9630019e5315e138a1efc454d3f56a88558b00695962",
            info.sha256
        )
    }

    @Test
    fun `没有 digest 字段时哈希为 null 而不是整条失败`() {
        // 2026 之前的 Release 没有 digest；这时靠签名闸兜底，不是解析失败
        val json = fixture("latest_v1_5.json").replace(Regex("\"digest\"\\s*:\\s*\"[^\"]*\""), "\"digest\": null")
        val info = checkNotNull(parseLatestRelease(json))
        assertNull(info.sha256)
    }

    @Test
    fun `tag 里没有 v 前缀也能解析`() {
        val json = fixture("latest_v1_5.json").replace("\"v1.5\"", "\"1.5\"")
        val info = checkNotNull(parseLatestRelease(json))
        assertEquals("1.5", info.versionName)
    }

    @Test
    fun `tag 不可解析时照样解析出来，交给 isNewer 决定不提示`() {
        // 规格 §3：任一段不是数字就按「不可比 → 已是最新」处理。
        // 这属于「没有新版」，不是「检查失败」—— 在解析层返回 null 会被上层
        // 当成网络错误报给用户，说法就错了
        val json = fixture("latest_v1_5.json").replace("\"v1.5\"", "\"latest\"")
        val info = checkNotNull(parseLatestRelease(json))
        assertEquals("latest", info.versionName)
        assertFalse(VersionTags.isNewer(info.versionName, "1.5"))
    }

    @Test
    fun `没有 apk 资产时返回 null`() {
        // 资产名以 .apk 结尾才算可安装的
        val json = """
            {"tag_name":"v1.5","body":"","assets":[
              {"name":"source.zip","size":1,"browser_download_url":"https://github.com/x/y/releases/download/v1.5/source.zip"}
            ]}
        """.trimIndent()
        assertNull(parseLatestRelease(json))
    }

    @Test
    fun `不是 JSON 时返回 null 而不是抛异常`() {
        assertNull(parseLatestRelease("<html>502 Bad Gateway</html>"))
        assertNull(parseLatestRelease(""))
    }

    @Test
    fun `名字里带 tag 的资产优先于泛化名字`() {
        val json = """
            {"tag_name":"v1.5","body":"","assets":[
              {"name":"app-release.apk","size":1,"browser_download_url":"https://github.com/x/y/releases/download/v1.5/app-release.apk"},
              {"name":"LoveqPodcast-v1.5.apk","size":2,"browser_download_url":"https://github.com/x/y/releases/download/v1.5/LoveqPodcast-v1.5.apk"}
            ]}
        """.trimIndent()
        val info = checkNotNull(parseLatestRelease(json))
        assertEquals("LoveqPodcast-v1.5.apk", info.apkName)
    }

    @Test
    fun `都不带 tag 时退回第一个 apk`() {
        val json = """
            {"tag_name":"v1.4","body":"","assets":[
              {"name":"app-release.apk","size":1,"browser_download_url":"https://github.com/x/y/releases/download/v1.4/app-release.apk"}
            ]}
        """.trimIndent()
        val info = checkNotNull(parseLatestRelease(json))
        assertEquals("app-release.apk", info.apkName)
    }
}
