# 在线升级（应用内检查 → 下载 → 安装）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让应用自己检查 GitHub 上有没有新版本，下载 APK，校验后唤出系统安装界面。

**架构：** 方案 A —— 约定 Release tag 为 `v<versionName>`，用版本名比对判断有没有新版；下载后用包内真实的 `versionCode` 复核兜底。元数据与资产各自一轮多镜像轮换（两者可达性完全不同），下载边下边算 sha256。安装前三道闸：sha256 / 签名证书 == 已装应用证书 / versionCode 递增。

**技术栈：** Kotlin + Jetpack Compose（Material3）、OkHttp 4.12（自带 client，不复用 `LoveqRepository`）、org.json、kotlinx-coroutines、JUnit 4。

**规格：** `docs/superpowers/specs/2026-10-16-online-update-design.md`（本计划的唯一依据，实现中如有分歧以规格为准，但需回头改规格）

---

## 文件结构

**创建**

| 文件 | 职责 |
|---|---|
| `app/src/main/java/cn/loveq/podcast/data/VersionTags.kt` | 纯函数：解析 tag、分段比版本 |
| `app/src/main/java/cn/loveq/podcast/data/UpdateSources.kt` | 两个源列表、`SourceRotation`、`SourceMemory` + prefs 实现 |
| `app/src/main/java/cn/loveq/podcast/data/GithubReleases.kt` | `ReleaseInfo`、`ReleaseSource`、`parseLatestRelease`（纯解析）、`GithubReleases`（网络）、`FixtureReleaseSource`（夹具） |
| `app/src/main/java/cn/loveq/podcast/data/UpdateDownloader.kt` | 下载 + 边下边算 sha256 + 换源；`sha256Of` |
| `app/src/main/java/cn/loveq/podcast/data/ApkInstaller.kt` | 读包内 versionCode/签名、比对证书、唤安装器、未知来源判定 |
| `app/src/main/java/cn/loveq/podcast/data/UpdateCenter.kt` | `UpdateStatus`/`FailureKind` + 编排 + 状态持有 |
| `app/src/main/java/cn/loveq/podcast/ui/UpdateSection.kt` | 设置页「更新」节 + `UpdateDot` 红点 |
| `app/src/main/res/xml/file_paths.xml` | FileProvider 路径白名单 |
| `app/src/test/java/cn/loveq/podcast/data/VersionTagsTest.kt` | 版本比较单测 |
| `app/src/test/java/cn/loveq/podcast/data/UpdateSourcesTest.kt` | 源轮换单测 |
| `app/src/test/java/cn/loveq/podcast/data/ReleaseParserTest.kt` | 响应解析单测 |
| `app/src/test/java/cn/loveq/podcast/data/Sha256Test.kt` | 哈希单测 |
| `app/src/test/resources/latest_v1_5.json` | 真实 `releases/latest` 响应快照（测试资源） |
| `app/src/fixture/assets/update_fixture.json` | 夹具响应（只进 fixture 构建） |

**修改**

| 文件 | 改什么 |
|---|---|
| `app/build.gradle.kts` | `buildConfig = true`；新增 `fixture` buildType（`initWith(release)` 拿正式签名） |
| `app/src/main/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES` 权限 + FileProvider 声明 |
| `app/src/main/java/cn/loveq/podcast/LoveqApp.kt` | 建 `UpdateCenter` 单例 |
| `app/src/main/java/cn/loveq/podcast/ui/EpisodesViewModel.kt` | 暴露 `updateStatus` + 两个动作 |
| `app/src/main/java/cn/loveq/podcast/ui/AppDialogs.kt` | 设置页插「更新」节；「关于」行挂红点 |
| `app/src/main/java/cn/loveq/podcast/ui/AppScreen.kt` | 订阅状态、传参、给两个入口传 `showUpdateDot` |
| `app/src/main/java/cn/loveq/podcast/ui/CategoryNav.kt` | `SideTool` / `IconTool` 支持红点 |
| `app/src/main/java/cn/loveq/podcast/ui/MainActivity.kt` | 启动静默检查一次 |
| `agent.md` | 更新镜像清单、加在线升级与 tag 约定 |
| `README.md` | 功能条目 |

---

## 任务 1：构建与清单前置

这个任务必须先做：后面所有代码都引用 `BuildConfig.UPDATE_USE_FIXTURE`。

**文件：**
- 修改：`app/build.gradle.kts`
- 修改：`gradle/libs.versions.toml`
- 修改：`app/src/main/AndroidManifest.xml`
- 创建：`app/src/main/res/xml/file_paths.xml`

> **执行中补充（2026-10-16，TDD 阶段发现，原计划遗漏）**：任务 4 是第一个在 JVM 单测里用
> `org.json` 的测试，而 Android 单测跑在 mockable `android.jar` 上 —— `org.json.*` 全是**桩**，
> 一调用就抛 `RuntimeException: Method optString in org.json.JSONObject not mocked`。
> 所以本任务要**额外**把真实 `org.json` 挂进测试类路径（只影响单测；设备上仍用系统实现）：
>
> - `gradle/libs.versions.toml`：`[versions]` 加 `json = "20240303"`；`[libraries]` 加
>   `json = { group = "org.json", name = "json", version.ref = "json" }`
> - `app/build.gradle.kts`：dependencies 里 `testImplementation(libs.junit)` 之后加
>   `testImplementation(libs.json)`
>
> **`testOptions.unitTests.isReturnDefaultValues = true` 不是替代方案**：那会让桩返回 `null`/`0`，
> `JSONObject(json)` 造出来是空对象，解析会静默失败（不是抛错，更难看懂）。

- [ ] **步骤 1：给 `buildFeatures` 打开 buildConfig**

`app/build.gradle.kts` 里把：

```kotlin
    buildFeatures {
        compose = true
    }
```

改成：

```kotlin
    buildFeatures {
        compose = true
        // 需要它来区分「真打 API」和「读夹具」：ReleaseSource 的实现在编译期定死，
        // R8 才能把夹具那条分支整个删掉
        buildConfig = true
    }
```

- [ ] **步骤 2：新增 `fixture` buildType**

在 `app/build.gradle.kts` 的 `buildTypes { ... }` 里，`release { ... }` 之后加：

```kotlin
        // 测试用构建：跑在线升级的端到端验证。
        // 必须 initWith(release) —— 校验签名时比的是「已装应用的证书」，
        // 用 debug 签名的话闸② 必然拦下，根本走不到安装器。
        create("fixture") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "UPDATE_USE_FIXTURE", "true")
        }
```

同时在 `debug` 与 `release` 里各加一行 `buildConfigField`。`buildTypes` 块改成：

```kotlin
    buildTypes {
        debug {
            buildConfigField("boolean", "UPDATE_USE_FIXTURE", "false")
        }
        release {
            // 正式包开 R8：压缩 + 资源裁剪。Jsoup 没有随包带 consumer 规则，
            // 保留规则见 proguard-rules.pro；其余依赖（compose/media3/okhttp/coroutines）
            // 都自带 consumer 规则，交给 R8 处理即可。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有签名配置就用正式签名，否则退回 debug 签名（保证照样能装）
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "UPDATE_USE_FIXTURE", "false")
        }
        // 测试用构建：跑在线升级的端到端验证。
        // 必须 initWith(release) —— 校验签名时比的是「已装应用的证书」，
        // 用 debug 签名的话闸② 必然拦下，根本走不到安装器。
        create("fixture") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "UPDATE_USE_FIXTURE", "true")
        }
    }
```

- [ ] **步骤 3：加 FileProvider 路径白名单**

创建 `app/src/main/res/xml/file_paths.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 只开放 cacheDir/update 这一个子目录给安装器读。
     不要用 <cache-path path="." />：那等于把整个缓存目录都开出去，
     而缓存里还有 2GB 的音频分片，没理由让外部应用能读到。 -->
<paths>
    <cache-path name="update" path="update/" />
</paths>
```

- [ ] **步骤 4：清单加权限与 provider**

`app/src/main/AndroidManifest.xml` 里，`POST_NOTIFICATIONS` 那行之后加：

```xml
    <!-- 只用于把下载好的 APK 交给系统安装器（minSdk 24 起必须走 FileProvider + content URI）。
         本应用侧载自用、不上架，不涉及 Play 商店对该权限的审查。 -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

在 `<application>` 内、`</application>` 之前（`<service>` 之后）加：

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **步骤 5：确认四个变体都能配置成功**

运行：

```bash
./gradlew :app:tasks --offline --console=plain -q > /dev/null && echo "配置通过"
```

预期：输出 `配置通过`（若报 `buildConfigField` 相关错误，说明某个 buildType 漏了字段）。

- [ ] **步骤 6：Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "build(update): 打开 buildConfig、新增 fixture 构建、声明安装权限与 FileProvider"
```

---

## 任务 2：VersionTags（版本解析与比较）

**文件：**
- 创建：`app/src/main/java/cn/loveq/podcast/data/VersionTags.kt`
- 测试：`app/src/test/java/cn/loveq/podcast/data/VersionTagsTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `app/src/test/java/cn/loveq/podcast/data/VersionTagsTest.kt`：

```kotlin
package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本名的解析与比较。
 *
 * 这里写错的方式都是安静的：用字符串比较会让 1.10 被当成比 1.9 旧（字典序），
 * 结果是发新版用户却收不到提示；把不可比的 tag 当成可解析，则可能把人引到一个
 * 来路不明的版本上。所以宁可判「不可比、不提示」，也不猜。
 */
class VersionTagsTest {

    @Test
    fun `v 前缀和各种写法都能解析`() {
        assertEquals(listOf(1, 5), VersionTags.parse("v1.5"))
        assertEquals(listOf(1, 5), VersionTags.parse("V1.5"))
        assertEquals(listOf(1, 5), VersionTags.parse("1.5"))
        assertEquals(listOf(1, 5), VersionTags.parse("  v1.5  "))
        assertEquals(listOf(1, 0, 0), VersionTags.parse("v1.0.0"))
    }

    @Test
    fun `10 大于 9 而不是按字典序`() {
        // 字典序下 "1.10" < "1.9"，这条就是为了钉死这个坑
        assertTrue(VersionTags.isNewer("v1.10", "1.9"))
        assertFalse(VersionTags.isNewer("v1.9", "1.10"))
    }

    @Test
    fun `1_5_0 与 1_5 视为相同`() {
        assertFalse(VersionTags.isNewer("v1.5.0", "1.5"))
        assertFalse(VersionTags.isNewer("v1.5", "1.5.0"))
    }

    @Test
    fun `更高版本判定为更新`() {
        assertTrue(VersionTags.isNewer("v1.6", "1.5"))
        assertTrue(VersionTags.isNewer("v2.0", "1.9.9"))
    }

    @Test
    fun `更低版本不判定为更新`() {
        assertFalse(VersionTags.isNewer("v1.4", "1.5"))
        assertFalse(VersionTags.isNewer("v1.5", "1.5"))
    }

    @Test
    fun `长的纯数字版本号照样能解析`() {
        // 段数多不是错误，不能因为「看着不像版本号」就判为不可比
        assertEquals(listOf(1, 5, 1, 2, 3), VersionTags.parse("1.5.1.2.3"))
    }

    @Test
    fun `不可比的 tag 一律解析不出`() {
        assertNull(VersionTags.parse("v1.5-beta"))
        assertNull(VersionTags.parse(""))
        assertNull(VersionTags.parse("v"))
        assertNull(VersionTags.parse("vv1.5"))
        assertNull(VersionTags.parse("v1..5"))
        assertNull(VersionTags.parse("latest"))
    }

    @Test
    fun `任一侧不可比都不提示升级`() {
        // 宁可漏一次提示，也不能按猜测去升
        assertFalse(VersionTags.isNewer("v1.5-beta", "1.5"))
        assertFalse(VersionTags.isNewer("v1.6", "whatever"))
    }
}
```

注意「首尾空白」（`"v1.5 "`）**不算**不可比：`parse` 会先 `trim()`，所以它归上面「各种写法都能解析」那条。别把它挪进「不可比」列表 —— 那样测试会红，而错的是测试。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.VersionTagsTest"
```

预期：**编译失败**，`Unresolved reference: VersionTags`。

- [ ] **步骤 3：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/data/VersionTags.kt`：

```kotlin
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
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.VersionTagsTest"
```

预期：`BUILD SUCCESSFUL`，8 个测试全过。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/VersionTags.kt app/src/test/java/cn/loveq/podcast/data/VersionTagsTest.kt
git commit -m "feat(update): 版本名解析与分段比较（方案 A 的比对基础）"
```

---

## 任务 3：UpdateSources（源列表、轮换、记住成功源）

**文件：**
- 创建：`app/src/main/java/cn/loveq/podcast/data/UpdateSources.kt`
- 测试：`app/src/test/java/cn/loveq/podcast/data/UpdateSourcesTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `app/src/test/java/cn/loveq/podcast/data/UpdateSourcesTest.kt`：

```kotlin
package cn.loveq.podcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像源的轮换。
 *
 * 这里写错的两种方式都不会报错，只会让人白等：试过的源又试一遍（多等一次超时），
 * 或者试完之后不返回 null（调用方以为还有机会）。所以逐个钉死。
 */
class UpdateSourcesTest {

    @Test
    fun `依次给出每个源，不重复`() {
        val rotation = SourceRotation(listOf("a", "b", "c"))
        assertEquals("a", rotation.next())
        assertEquals("b", rotation.next())
        assertEquals("c", rotation.next())
    }

    @Test
    fun `试完之后返回 null 而不是从头再来`() {
        val rotation = SourceRotation(listOf("a"))
        assertEquals("a", rotation.next())
        assertNull(rotation.next())
        assertNull(rotation.next())
    }

    @Test
    fun `空列表立刻返回 null`() {
        assertNull(SourceRotation(emptyList()).next())
    }

    @Test
    fun `promoted 把上次成功的源提到最前`() {
        val sources = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), UpdateSources.promoted(sources, "c"))
        assertEquals(listOf("b", "a", "c"), UpdateSources.promoted(sources, "b"))
    }

    @Test
    fun `从来没有成功过就保持原顺序`() {
        val sources = listOf("a", "b")
        assertEquals(sources, UpdateSources.promoted(sources, null))
    }

    @Test
    fun `记下的源已经不在名单里时保持原顺序`() {
        // 名单会腐坏，promoted 不能因为记住了一个被删掉的源就把它塞回来
        val sources = listOf("a", "b")
        assertEquals(sources, UpdateSources.promoted(sources, "已失效的源"))
    }

    @Test
    fun `直连用空前缀表示，也能被 promote`() {
        val sources = listOf("https://gh-proxy.com/", "")
        assertEquals(listOf("", "https://gh-proxy.com/"), UpdateSources.promoted(sources, ""))
    }

    @Test
    fun `两条名单都以直连收尾或开头，且不含已知劣质源`() {
        // 资产：镜像优先、直连垫底（直连在国内只有约 1/3 成功率，放前面就是白等）
        assertEquals("", UpdateSources.ASSET.last())
        // ghproxy.net 实测 120 秒只下到 4.04MB 就截断，明确不用
        assertTrue(UpdateSources.ASSET.none { it.contains("ghproxy.net") })
        assertTrue(UpdateSources.API.none { it.contains("ghproxy.net") })
        // 元的直连排第一：实测最快最稳
        assertEquals("", UpdateSources.API.first())
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.UpdateSourcesTest"
```

预期：编译失败，`Unresolved reference: SourceRotation` 与 `Unresolved reference: UpdateSources`。

- [ ] **步骤 3：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/data/UpdateSources.kt`：

```kotlin
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
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.UpdateSourcesTest"
```

预期：`BUILD SUCCESSFUL`，8 个测试全过。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/UpdateSources.kt app/src/test/java/cn/loveq/podcast/data/UpdateSourcesTest.kt
git commit -m "feat(update): 镜像源列表、轮换状态机与「记住上次成功的源」"
```

---

## 任务 4：ReleaseInfo 与响应解析

解析抽成纯函数，是为了能拿**真实的响应快照**当测试资源 —— 一旦要联网就测不动了，而字段名、digest 前缀、资产挑选正是最容易错的地方。

**文件：**
- 创建：`app/src/test/resources/latest_v1_5.json`
- 创建：`app/src/test/java/cn/loveq/podcast/data/ReleaseParserTest.kt`
- 创建：`app/src/main/java/cn/loveq/podcast/data/GithubReleases.kt`（本任务只写 `ReleaseInfo` / `UpdateCheckException` / `parseLatestRelease`）

- [ ] **步骤 1：抓一份真实的响应当测试资源**

运行：

```bash
mkdir -p app/src/test/resources
curl -sS --max-time 20 \
  -H "User-Agent: LoveqPodcast/1.5" \
  https://api.github.com/repos/pigstrong9/LoveqPodcast/releases/latest \
  -o app/src/test/resources/latest_v1_5.json
python3 -c "
import json
d = json.load(open('app/src/test/resources/latest_v1_5.json'))
print('tag      =', d['tag_name'])
print('assets   =', [(a['name'], a['size'], a.get('digest')) for a in d['assets']])
print('body 长度 =', len(d.get('body') or ''))
"
```

预期输出（2026-10-16 实测）：

```
tag      = v1.5
assets   = [('LoveqPodcast-v1.5.apk', 4536121, 'sha256:6b9ba13f3e743ed8e0bf9630019e5315e138a1efc454d3f56a88558b00695962')]
body 长度 = 723
```

若 `v1.5` 已被删掉换成了别的版本，**以实际输出为准修改下面测试里的期望值**，并在提交信息里说明。

- [ ] **步骤 2：编写失败的测试**

创建 `app/src/test/java/cn/loveq/podcast/data/ReleaseParserTest.kt`：

```kotlin
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
```

- [ ] **步骤 3：运行测试验证失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.ReleaseParserTest"
```

预期：编译失败，`Unresolved reference: parseLatestRelease`。

- [ ] **步骤 4：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/data/GithubReleases.kt`（本任务先写前半部分，任务 5 再补网络类与夹具类）：

```kotlin
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
```

- [ ] **步骤 5：运行测试验证通过**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.ReleaseParserTest"
```

预期：`BUILD SUCCESSFUL`，10 个测试全过。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/GithubReleases.kt app/src/test/java/cn/loveq/podcast/data/ReleaseParserTest.kt app/src/test/resources/latest_v1_5.json
git commit -m "feat(update): 解析 releases/latest，附真实响应快照测试资源"
```

---

## 任务 5：GithubReleases 网络层与夹具源

**文件：**
- 修改：`app/src/main/java/cn/loveq/podcast/data/GithubReleases.kt`

- [ ] **步骤 1：补上接口、网络实现与夹具实现**

在 `GithubReleases.kt` 末尾追加：

```kotlin
/**
 * 取「最新发布」的来源。
 *
 * 抽成接口是为了让夹具构建能整个换掉它 —— 不给 GithubReleases 塞一个假基址，
 * 那样真实现里就永远留着一行测试代码。
 */
internal interface ReleaseSource {
    suspend fun latest(): ReleaseInfo
}

/**
 * 唯一碰 GitHub 的地方。
 *
 * 自带 OkHttpClient，刻意不复用 [LoveqRepository]：后者那套全局限速闸门与冷却阶梯
 * 是为 loveq.cn 的 405 定制的，GitHub 请求既不该被官网的冷却拦住，
 * 也不该去挤官网的请求槽（[DownloadManager] 也是这么分开的）。
 */
internal class GithubReleases(private val memory: SourceMemory) : ReleaseSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val rotation = SourceRotation(
            UpdateSources.promoted(UpdateSources.API, memory.lastOk(KEY_API))
        )
        var lastError: Exception? = null
        while (true) {
            val prefix = rotation.next() ?: break
            val url = prefix + LATEST_URL
            try {
                val info = parseLatestRelease(get(url))
                    ?: throw UpdateCheckException("响应里没有可安装的发布 - $url")
                // 成功才记：失败也记的话，下次会把一个坏源排在第一个
                memory.remember(KEY_API, prefix)
                return@withContext info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw UpdateCheckException("所有源都取不到版本信息", lastError)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            // GitHub API 强制要求带 UA；写明身份，不伪装也不轮换
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateCheckException("HTTP ${response.code} - $url")
            }
            return response.body?.string() ?: throw UpdateCheckException("空响应 - $url")
        }
    }

    companion object {
        const val LATEST_URL =
            "https://api.github.com/repos/pigstrong9/LoveqPodcast/releases/latest"

        /** 「上次成功的源」在 prefs 里的键 */
        const val KEY_API = "api"
        const val KEY_ASSET = "asset"

        const val USER_AGENT =
            "LoveqPodcast/1.5 (+https://github.com/pigstrong9/LoveqPodcast)"
    }
}

/**
 * 夹具源：读打包进来的真实响应快照。
 *
 * 只有 fixture 构建里有这个 asset（`app/src/fixture/assets/`），
 * 而 BuildConfig.UPDATE_USE_FIXTURE 是编译期常量，release 里 R8 会把
 * 整个分支连同这个类一起删掉。
 */
internal class FixtureReleaseSource(private val context: Context) : ReleaseSource {

    override suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val json = context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() }
        parseLatestRelease(json) ?: throw UpdateCheckException("夹具解析失败：$ASSET_NAME")
    }

    companion object {
        const val ASSET_NAME = "update_fixture.json"
    }
}
```

同时把文件顶部的 import 补齐成：

```kotlin
import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
```

- [ ] **步骤 2：确认编译通过**

运行：

```bash
./gradlew :app:compileDebugKotlin --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

（`FixtureReleaseSource` 引用的 asset 到任务 13 才有，这里只要求编得过 —— `assets.open` 是运行时才会失败。）

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/GithubReleases.kt
git commit -m "feat(update): GitHub 网络层（多镜像轮换）与夹具源"
```

---

## 任务 6：UpdateDownloader（下载、边下边算哈希、换源）

**文件：**
- 创建：`app/src/main/java/cn/loveq/podcast/data/UpdateDownloader.kt`
- 测试：`app/src/test/java/cn/loveq/podcast/data/Sha256Test.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `app/src/test/java/cn/loveq/podcast/data/Sha256Test.kt`：

```kotlin
package cn.loveq.podcast.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件哈希。
 *
 * 这里写错的方式很安静：忘了补零（0x0a 打成 "a"）会让哈希长度不对，
 * 于是「每个源都报哈希不符」，看着像所有镜像都坏了。
 */
class Sha256Test {

    private fun tempFile(content: ByteArray): File =
        File.createTempFile("sha256", ".bin").apply {
            deleteOnExit()
            writeBytes(content)
        }

    @Test
    fun `abc 的 sha256 是已知值`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Of(tempFile("abc".toByteArray()))
        )
    }

    @Test
    fun `每个字节都固定两位十六进制`() {
        // 0x00 与 0x0f 这类值时最容易漏掉补零
        val expected = "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"
        assertEquals(expected, sha256Of(tempFile(byteArrayOf(0x00))))
        assertEquals(64, sha256Of(tempFile(byteArrayOf(0x0f))).length)
    }

    @Test
    fun `空文件的哈希也是 64 位`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Of(tempFile(ByteArray(0)))
        )
    }

    @Test
    fun `内容不同则哈希不同`() {
        val a = sha256Of(tempFile("a".toByteArray()))
        val b = sha256Of(tempFile("b".toByteArray()))
        assertTrue(a != b)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.Sha256Test"
```

预期：编译失败，`Unresolved reference: sha256Of`。

- [ ] **步骤 3：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/data/UpdateDownloader.kt`：

```kotlin
package cn.loveq.podcast.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 所有源都下载失败，或某一个源给的东西不完整 / 哈希不符 */
internal class UpdateDownloadException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * 下载 APK：多镜像轮换 + 边下边算 sha256。
 *
 * 每个源都先写到 `.part`，**全部下完才 rename 成正式名**。否则半截文件会被当成
 * 下载成功送进校验，然后在签名那一步失败 —— 报出来的错指向签名，
 * 真正的原因（下载被截断）就被藏掉了。
 *
 * 哈希在这里就比对，不留到调用方：源已经换掉、文件已经删掉之后再报「哈希不符」，
 * 就没法告诉人「是这个源给的东西不对」。
 */
internal class UpdateDownloader(
    private val dir: File,
    private val memory: SourceMemory
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // APK 4.3MB，国内镜像实测 90~165 KB/s；给足 10 分钟，
        // 免得网慢一点就被整体超时掐掉、白白换源重来
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * 成功返回落盘的 APK。全部源都失败则抛 [UpdateDownloadException]。
     *
     * 进度回调带上「当前在试哪个源」：换源那一刻进度会跳回 0，不说明的话
     * 看着像从头重来，而实际上只是换了一条路。
     */
    suspend fun fetch(
        info: ReleaseInfo,
        onProgress: (progress: Float, source: String) -> Unit
    ): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, info.apkName)
            val rotation = SourceRotation(
                UpdateSources.promoted(UpdateSources.ASSET, memory.lastOk(GithubReleases.KEY_ASSET))
            )
            var lastError: Exception? = null
            while (true) {
                val prefix = rotation.next() ?: break
                val part = File(dir, "${info.apkName}.part")
                try {
                    onProgress(0f, prefix)
                    downloadOne(prefix + info.apkUrl, part, info, prefix, onProgress)
                    if (target.exists() && !target.delete()) {
                        throw UpdateDownloadException("旧的安装包删不掉：${target.absolutePath}")
                    }
                    if (!part.renameTo(target)) {
                        throw UpdateDownloadException("无法写入 ${target.absolutePath}")
                    }
                    // 成功才记：失败也记的话，下次会把一个坏源排在第一个
                    memory.remember(GithubReleases.KEY_ASSET, prefix)
                    return@withContext target
                } catch (e: CancellationException) {
                    part.delete()
                    throw e
                } catch (e: Exception) {
                    part.delete()
                    lastError = e
                }
            }
            throw UpdateDownloadException("所有源都下载失败", lastError)
        }

    private suspend fun downloadOne(
        url: String,
        dest: File,
        info: ReleaseInfo,
        prefix: String,
        onProgress: (progress: Float, source: String) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", GithubReleases.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateDownloadException("HTTP ${response.code} - $url")
            }
            val body = response.body ?: throw UpdateDownloadException("空响应 - $url")
            // 优先用 API 报的大小做进度分母：镜像即使不给 content-length 也能显示进度
            val total = if (info.apkSize > 0) info.apkSize else body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L

            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        // 边下边喂给 digest，不为此再读一遍文件
                        digest.update(buffer, 0, n)
                        copied += n
                        if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f), prefix)
                        // 用户退出时协程会被取消，这里要真的停下来，
                        // 否则 4MB 会一直下完才响应取消
                        currentCoroutineContext().ensureActive()
                    }
                    output.flush()
                }
            }

            if (total > 0 && copied != total) {
                throw UpdateDownloadException("大小不符：期望 $total 字节，实收 $copied 字节")
            }

            val expected = info.sha256
            if (expected != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw UpdateDownloadException("哈希不符：期望 $expected，实际 $actual")
                }
            }
        }
    }
}

/** 字节数组转小写 hex。补零不能漏：0x0a 打成 "a" 会让哈希长度不对 */
private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** 计算文件的 sha256（小写 hex）。单独抽出来是为了能单测 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain --tests "cn.loveq.podcast.data.Sha256Test"
```

预期：`BUILD SUCCESSFUL`，4 个测试全过。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/UpdateDownloader.kt app/src/test/java/cn/loveq/podcast/data/Sha256Test.kt
git commit -m "feat(update): APK 下载器（多镜像轮换 + 边下边算 sha256）"
```

---

## 任务 7：ApkInstaller（校验与唤安装器）

这个类依赖 `PackageManager`，无法单测，靠任务 14 的端到端验证。**签名比对是唯一真正拦得住伪造包的东西，所以任何一步读不到证书都按「不可信」处理，不按「先放行」处理。**

**文件：**
- 创建：`app/src/main/java/cn/loveq/podcast/data/ApkInstaller.kt`

- [ ] **步骤 1：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/data/ApkInstaller.kt`：

```kotlin
package cn.loveq.podcast.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * 校验下载好的 APK，并把它交给系统安装器。
 *
 * 签名比对（[isTrusted]）是这里唯一真正拦得住伪造包的东西：只有持有本项目
 * keystore 的人能签出可安装的包，镜像再怎么投毒也过不了这一关。
 *
 * 所以一切**失败关闭**：任何一步读不到证书，都按「不可信」处理。
 * 反过来「读不到就先放行」等于把这道闸关掉，而且关得毫无声音。
 */
internal class ApkInstaller(private val context: Context) {

    /** 读包内真实的 versionCode；读不出来返回 null */
    fun versionCodeOf(file: File): Long? {
        val info = archiveInfo(file, flags = 0) ?: return null
        return PackageInfoCompat.getLongVersionCode(info)
    }

    /**
     * 下载包的签名证书是否与**已装应用**完全一致。
     *
     * 比的是证书摘要集合，不是「能装上就行」：版本号相同、包名相同但签名不同的包，
     * 系统也会拒绝安装，但那已经是装到一半才失败，而且报的错和「包坏了」混在一起。
     */
    fun isTrusted(file: File): Boolean {
        val archive = archiveCertDigests(file) ?: return false
        val installed = installedCertDigests() ?: return false
        return archive == installed
    }

    /** Android 8 起才有「按应用授予未知来源」这回事；更低版本由系统总开关决定 */
    fun needsUnknownSources(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    /**
     * 唤出系统安装界面。
     *
     * minSdk 24 / targetSdk 35 必须走 FileProvider + content URI：
     * 直接把 file:// 递出去会抛 FileUriExposedException。
     */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun archiveInfo(file: File, flags: Int): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }.getOrNull()

    /** 下载包的证书摘要集合；任一环节不满足都返回 null（调用方按不可信处理） */
    private fun archiveCertDigests(file: File): Set<String>? {
        val info = archiveInfo(file, archiveFlags()) ?: return null
        return signaturesToDigests(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        )
    }

    /** 已装应用自己的证书摘要集合；用 PackageInfoCompat 以避开 API 28 前后的差异 */
    private fun installedCertDigests(): Set<String>? = runCatching {
        signaturesToDigests(
            PackageInfoCompat.getSignatures(context.packageManager, context.packageName)
                .toTypedArray()
        )
    }.getOrNull()

    private fun signaturesToDigests(signatures: Array<Signature>?): Set<String>? {
        if (signatures.isNullOrEmpty()) return null
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private fun archiveFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
}
```

- [ ] **步骤 2：确认编译通过**

运行：

```bash
./gradlew :app:compileDebugKotlin --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

如果 `PackageInfoCompat.getSignatures` 报 `Unresolved reference`，说明版本不符：已确认本项目用的 `androidx.core:core:1.15.0` 提供 `getSignatures(PackageManager, String): List<Signature>`，签名不符时按这个签名调整调用。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/ApkInstaller.kt
git commit -m "feat(update): APK 校验与安装（签名比对 + FileProvider）"
```

---

## 任务 8：UpdateCenter（编排与状态机）

**文件：**
- 创建：`app/src/main/java/cn/loveq/podcast/data/UpdateCenter.kt`

- [ ] **步骤 1：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/data/UpdateCenter.kt`：

```kotlin
package cn.loveq.podcast.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 检查 / 下载的进展与结果。界面只认这一份状态 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val current: String) : UpdateStatus
    data class Available(val info: ReleaseInfo) : UpdateStatus

    /** @param source 当前在试的镜像前缀（空串 = 直连），换源时进度会跳回 0 */
    data class Downloading(val progress: Float, val source: String) : UpdateStatus

    /**
     * 三道闸正在跑。
     *
     * 做成**可见状态**而不是瞬间过程：读包、比证书在慢设备上有可感知的耗时，
     * 进度条一直转却不说在做什么，看着就像卡死了。
     */
    data object Verifying : UpdateStatus
    data class ReadyToInstall(val file: File) : UpdateStatus
    data class Failed(val kind: FailureKind, val detail: String?) : UpdateStatus
}

enum class FailureKind { CHECK, DOWNLOAD, INTEGRITY, UNKNOWN_SOURCES, INSTALL }

/**
 * 在线升级的编排与状态持有者。放在 [cn.loveq.podcast.LoveqApp] 里，跟其它单例一样。
 *
 * 下载跑在自己 scope 上（照 [DownloadManager] 的写法）：关掉设置弹窗不该掐断下载，
 * 重新打开还能看到进度。
 *
 * 可见性：本类依赖的 `ReleaseSource` / `ApkInstaller` / `SourceMemory` 都是 `internal`，
 * 构造器写成 public 会直接撞 Kotlin 的 `EXPOSED_PARAMETER_TYPE`（实测确实撞了），
 * 所以本类声明为 `internal`。
 */
internal class UpdateCenter(
    context: Context,
    private val source: ReleaseSource,
    private val installer: ApkInstaller,
    memory: SourceMemory
) {

    private val appContext = context.applicationContext

    private val downloader = UpdateDownloader(File(appContext.cacheDir, DIR_UPDATE), memory)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    private var job: Job? = null

    /** 已下好并通过校验、等着（重新）交给安装器的文件 */
    private var pendingInstall: File? = null

    init {
        // 进程被杀会留下 .part 和上一个版本号不同的包，不主动清就一直躺在 cacheDir 里
        scope.launch { clearResidue() }
    }

    fun currentVersionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /**
     * 启动静默检查与手动点按钮共用这一个入口。
     *
     * @param manual 手动时为 true：失败要说出来。静默时为 false：
     *               **失败不写任何持久状态、也不显示** —— 网络抖一下不能变成
     *               「以后都不查了」，启动时也不该弹东西出来
     */
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = UpdateStatus.Checking
            try {
                val info = source.latest()
                val current = currentVersionName()
                _status.value = if (VersionTags.isNewer(info.versionName, current)) {
                    UpdateStatus.Available(info)
                } else {
                    UpdateStatus.UpToDate(current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value =
                    if (manual) UpdateStatus.Failed(FailureKind.CHECK, e.message)
                    else UpdateStatus.Idle
            }
        }
    }

    /** 下载 → 校验 → 唤安装器。只有 [UpdateStatus.Available] 时才有效 */
    fun downloadAndInstall() {
        val available = _status.value as? UpdateStatus.Available ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                _status.value = UpdateStatus.Downloading(0f, "")
                val file = downloader.fetch(available.info) { progress, source ->
                    _status.value = UpdateStatus.Downloading(progress, source)
                }

                _status.value = UpdateStatus.Verifying

                // 闸③：包内真实的 versionCode 必须**大于**已装的。
                // 方案 A 靠 tag 字符串比对，这一步兜住「tag 写错 / 忘了 bump」的情况 ——
                // 最坏只是白下载一次，不会真的降级安装
                val archiveCode = installer.versionCodeOf(file)
                val installedCode = installedVersionCode()
                if (archiveCode == null || archiveCode <= installedCode) {
                    file.delete()
                    _status.value = UpdateStatus.Failed(
                        FailureKind.INTEGRITY,
                        "下载到的版本并不比当前新（包内版本号 $archiveCode，已装 $installedCode）"
                    )
                    return@launch
                }

                // 闸②：真正的真伪闸。哈希只能拦「传输坏了」，拦不住「有人重新签了个包」
                if (!installer.isTrusted(file)) {
                    file.delete()
                    _status.value = UpdateStatus.Failed(
                        FailureKind.INTEGRITY,
                        "安装包校验失败，已删除"
                    )
                    return@launch
                }

                pendingInstall = file
                if (installer.needsUnknownSources()) {
                    _status.value = UpdateStatus.Failed(FailureKind.UNKNOWN_SOURCES, null)
                    return@launch
                }
                _status.value = UpdateStatus.ReadyToInstall(file)
                // 唤安装器单独包一层：系统里没有安装器实现时这里会抛，
                // 报成 DOWNLOAD 会让「下载失败」这个说法彻底误导人
                try {
                    installer.install(file)
                } catch (e: Exception) {
                    _status.value = UpdateStatus.Failed(FailureKind.INSTALL, e.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = UpdateStatus.Failed(FailureKind.DOWNLOAD, e.message)
            }
        }
    }

    /** 去系统设置授权的入口 */
    fun unknownSourcesIntent(): Intent? = installer.unknownSourcesIntent()

    /**
     * 从系统设置回来后调用。
     *
     * 文件还在就直接装，不必重下；还没授权就什么都不做 ——
     * 否则会立刻又弹一次「不允许安装」，看着像应用在跟自己较劲。
     */
    fun installPending() {
        val file = pendingInstall ?: return
        if (!file.exists()) {
            pendingInstall = null
            _status.value = UpdateStatus.Idle
            return
        }
        if (installer.needsUnknownSources()) return
        _status.value = UpdateStatus.ReadyToInstall(file)
        // 与 downloadAndInstall 同样的理由：从界面回调里抛出会崩溃
        try {
            installer.install(file)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Failed(FailureKind.INSTALL, e.message)
        }
    }

    /** 兜底：全部源都失败时打开 Release 页面，让用户自己下 */
    fun openReleasePage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    private fun installedVersionCode(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        )
    }.getOrDefault(0L)

    /** 清掉上次进程被杀留下的 .part 与旧包 */
    private fun clearResidue() {
        val dir = File(appContext.cacheDir, DIR_UPDATE)
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        /** 单独一个子目录：MediaCache 只用 cacheDir/media，两者互不干扰 */
        const val DIR_UPDATE = "update"

        const val RELEASES_PAGE =
            "https://github.com/pigstrong9/LoveqPodcast/releases/latest"
    }
}
```

- [ ] **步骤 2：确认编译通过**

运行：

```bash
./gradlew :app:compileDebugKotlin --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/data/UpdateCenter.kt
git commit -m "feat(update): 检查/下载/三道校验的编排与状态机"
```

---

## 任务 9：接进 LoveqApp 单例容器

**文件：**
- 修改：`app/src/main/java/cn/loveq/podcast/LoveqApp.kt`

- [ ] **步骤 1：加单例字段与初始化**

`app/src/main/java/cn/loveq/podcast/LoveqApp.kt` 改成：

```kotlin
package cn.loveq.podcast

import android.app.Application
import cn.loveq.podcast.data.ApkInstaller
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.data.EpisodeIndexStore
import cn.loveq.podcast.data.FixtureReleaseSource
import cn.loveq.podcast.data.GithubReleases
import cn.loveq.podcast.data.LoveqRepository
import cn.loveq.podcast.data.PrefsSourceMemory
import cn.loveq.podcast.data.ProgressStore
import cn.loveq.podcast.data.RateLimitGuard
import cn.loveq.podcast.data.ReleaseSource
import cn.loveq.podcast.data.UpdateCenter
// 原有 import，计划初稿的代码块漏抄了这两行（VolumeBoost / QueueStore 仍在使用）
import cn.loveq.podcast.playback.QueueStore
import cn.loveq.podcast.playback.VolumeBoost

class LoveqApp : Application() {

    lateinit var rateLimitGuard: RateLimitGuard
        private set
    lateinit var repository: LoveqRepository
        private set
    lateinit var progressStore: ProgressStore
        private set
    lateinit var indexStore: EpisodeIndexStore
        private set
    lateinit var queueStore: QueueStore
        private set
    lateinit var downloads: DownloadManager
        private set
    // 必须是 internal：public 属性会暴露 internal 类型 UpdateCenter（实测报
    // EXPOSED_PROPERTY_TYPE）。同模块内的界面照样能访问
    internal lateinit var updates: UpdateCenter
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // 限流守卫要先于 repository/indexStore 建好：三者共用同一份冷却状态，
        // 保证「被官网拒过」这件事在整个进程里只记一次、也只等一次
        rateLimitGuard = RateLimitGuard(this)
        repository = LoveqRepository(rateLimitGuard)
        progressStore = ProgressStore(this)
        indexStore = EpisodeIndexStore(this, rateLimitGuard)
        queueStore = QueueStore(this)
        downloads = DownloadManager(this, repository)

        // 夹具构建整个换掉 ReleaseSource，而不是给 GithubReleases 塞一个假基址：
        // 这样真实现里没有一行测试代码，release 里 R8 把这条分支连夹具类一起删掉
        // 「上次哪个源成功了」的两处（元数据 / 资产）共用同一份 prefs 访问
        val sourceMemory = PrefsSourceMemory(this)
        val releaseSource: ReleaseSource =
            if (BuildConfig.UPDATE_USE_FIXTURE) FixtureReleaseSource(this)
            else GithubReleases(sourceMemory)

        updates = UpdateCenter(
            context = this,
            source = releaseSource,
            installer = ApkInstaller(this),
            memory = sourceMemory
        )

        VolumeBoost.apply(progressStore.boostPercent)
    }
}
```

注意 `queueStore` 那一行原来是 `queueStore = QueueStore(t` 被截断显示的，实际文件里是完整的 `QueueStore(this)`，改的时候保持原样，不要动它。

- [ ] **步骤 2：确认编译通过**

运行：

```bash
./gradlew :app:compileDebugKotlin --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/LoveqApp.kt
git commit -m "feat(update): UpdateCenter 接进应用单例容器"
```

---

## 任务 10：UpdateSection 界面

**文件：**
- 创建：`app/src/main/java/cn/loveq/podcast/ui/UpdateSection.kt`

- [ ] **步骤 1：编写实现**

创建 `app/src/main/java/cn/loveq/podcast/ui/UpdateSection.kt`：

```kotlin
package cn.loveq.podcast.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
// 用新位置：compose-ui 1.7.5 里 androidx.compose.ui.platform.LocalLifecycleOwner 已标
// @Deprecated（挪到了 lifecycle-runtime-compose）。依赖已在 version catalog 里
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.loveq.podcast.data.FailureKind
import cn.loveq.podcast.data.UpdateStatus
import cn.loveq.podcast.ui.theme.AppColors
import kotlin.math.roundToInt

/** 镜像前缀转成能看懂的名字：空串是直连，其余去掉协议与末尾斜杠 */
private fun sourceLabel(prefix: String): String =
    if (prefix.isEmpty()) "直连"
    else prefix.removePrefix("https://").removePrefix("http://").trimEnd('/')

/** 「有新版本」的红点：图标右上角，稍微外挪一点免得压住图形本身 */
@Composable
internal fun UpdateDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(AppColors.Red)
    )
}

/**
 * 设置页的「更新」一节。
 *
 * 「打开 Release 页面」和「去授权未知来源」两个动作在这里自己发起（LocalContext），
 * 不必再往 SettingsDialog 穿一层回调。
 */
@Composable
internal fun UpdateSection(
    status: UpdateStatus,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallPending: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onRequestUnknownSources: () -> Intent?
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val busy = status is UpdateStatus.Checking ||
        status is UpdateStatus.Downloading ||
        status is UpdateStatus.Verifying
    val checking = status is UpdateStatus.Checking

    // 从「未知应用」系统设置回来后自动续上安装。
    // 只在 Failed(UNKNOWN_SOURCES) 这一种状态下订阅：否则每次回到前台都会
    // 去碰一下安装器，包括用户压根没打算装的时候
    DisposableEffect(status) {
        if (status !is UpdateStatus.Failed || status.kind != FailureKind.UNKNOWN_SOURCES) {
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onInstallPending()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            if (currentVersion.isBlank()) "当前版本：未知" else "当前版本：v$currentVersion",
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.Ink
        )
        Spacer(Modifier.height(10.dp))

        when (status) {
            is UpdateStatus.Idle -> Text(
                "从 GitHub 检查是否有新版本。启动时也会自动查一次，失败不打扰。",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted
            )

            is UpdateStatus.Checking -> StatusRow("正在检查…", spinner = true)

            is UpdateStatus.UpToDate -> Text(
                "已是最新（v${status.current}）",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Body
            )

            is UpdateStatus.Available -> {
                Text(
                    "有新版本 v${status.info.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Red,
                    fontWeight = FontWeight.Bold
                )
                if (status.info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    // 限高并可滚动：说明只会越来越长（v1.5 已经 723 字符），
                    // 不限高会把下面的「下载并安装」挤出弹窗可视区，
                    // 而设置页自己也在滚，两个滚动区嵌套很难用
                    Text(
                        status.info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Body,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                if (status.info.sha256 == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "该版本未提供哈希值，将仅按签名校验。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.Muted
                    )
                }
            }

            is UpdateStatus.Downloading -> {
                StatusRow(
                    "正在下载… ${(status.progress * 100).roundToInt()}%" +
                        "（${sourceLabel(status.source)}）",
                    spinner = false
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is UpdateStatus.Verifying -> StatusRow("正在校验安装包…", spinner = true)

            is UpdateStatus.ReadyToInstall -> {
                Text(
                    "已下载并通过校验，可以在系统安装界面完成安装。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Body
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onInstallPending) {
                    Text("再次安装", color = AppColors.Red, fontWeight = FontWeight.Bold)
                }
            }

            is UpdateStatus.Failed -> FailureText(status)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                status is UpdateStatus.Available -> TextButton(onClick = onDownloadAndInstall) {
                    Text("下载并安装", color = AppColors.Red, fontWeight = FontWeight.Bold)
                }

                status is UpdateStatus.Failed && status.kind == FailureKind.UNKNOWN_SOURCES ->
                    TextButton(onClick = {
                        onRequestUnknownSources()?.let { context.startActivity(it) }
                    }) {
                        Text("去授权「安装未知应用」", color = AppColors.Red, fontWeight = FontWeight.Bold)
                    }

                else -> Unit
            }

            TextButton(onClick = onCheck, enabled = !busy) {
                Text(
                    if (checking) "检查中…" else "检查更新",
                    color = if (busy) AppColors.Faint else AppColors.Red,
                    fontWeight = FontWeight.Bold
                )
            }
            if (checking) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            // 校验失败是安全事件，必须留一条「自己去下载」的路，
            // 不能只告诉用户「失败了」就把人堵在这
            if (status is UpdateStatus.Failed &&
                (status.kind == FailureKind.CHECK ||
                    status.kind == FailureKind.DOWNLOAD ||
                    status.kind == FailureKind.INTEGRITY)
            ) {
                TextButton(onClick = onOpenReleasePage) {
                    Text("打开下载页面", color = AppColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(text: String, spinner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (spinner) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.Body)
    }
}

@Composable
private fun FailureText(status: UpdateStatus.Failed) {
    val message = when (status.kind) {
        FailureKind.CHECK -> "检查失败，请稍后重试"
        FailureKind.DOWNLOAD -> "下载失败"
        // 校验不过要说得显眼：这不是「网不好」，是下载到的东西不对
        FailureKind.INTEGRITY -> "安装包校验失败，已删除"
        FailureKind.UNKNOWN_SOURCES -> "需要先允许本应用安装应用"
        FailureKind.INSTALL -> "无法启动系统安装界面"
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (status.kind == FailureKind.INTEGRITY) AppColors.RedDeep else AppColors.Ink,
        fontWeight = if (status.kind == FailureKind.INTEGRITY) FontWeight.Bold else FontWeight.Normal
    )
    if (!status.detail.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            status.detail,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.Muted
        )
    }
}
```

- [ ] **步骤 2：确认编译通过**

运行：

```bash
./gradlew :app:compileDebugKotlin --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

若报 `LinearProgressIndicator` 的参数不符，检查使用的是 Material3 的 lambda 版 `progress = { ... }`（compose-bom 2024.10.01 用的是这一版）。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/ui/UpdateSection.kt
git commit -m "feat(update): 设置页「更新」一节与红点组件"
```

---

## 任务 11：接进设置页与两处入口

**文件：**
- 修改：`app/src/main/java/cn/loveq/podcast/ui/EpisodesViewModel.kt`
- 修改：`app/src/main/java/cn/loveq/podcast/ui/AppDialogs.kt`
- 修改：`app/src/main/java/cn/loveq/podcast/ui/CategoryNav.kt`
- 修改：`app/src/main/java/cn/loveq/podcast/ui/AppScreen.kt`

- [ ] **步骤 1：ViewModel 暴露状态与动作**

在 `app/src/main/java/cn/loveq/podcast/ui/EpisodesViewModel.kt` 里，`private val loveqApp = app as LoveqApp` 附近（第 60 行后）加：

```kotlin
    /** 在线升级的状态与动作。放在这里而不是直接让界面拿 LoveqApp：
         跟 immersive / autoResume 一样，界面只跟 ViewModel 打交道 */
    val updateStatus = loveqApp.updates.status

    fun checkUpdate() = loveqApp.updates.check(manual = true)

    fun downloadAndInstallUpdate() = loveqApp.updates.downloadAndInstall()

    fun installPendingUpdate() = loveqApp.updates.installPending()

    fun currentVersionName(): String = loveqApp.updates.currentVersionName()

    fun openReleasePage() = loveqApp.updates.openReleasePage()

    fun unknownSourcesIntent() = loveqApp.updates.unknownSourcesIntent()
```

- [ ] **步骤 2：`SideTool` / `IconTool` 支持红点**

`app/src/main/java/cn/loveq/podcast/ui/CategoryNav.kt`：

把 `SideTool` 的签名与图标部分改成：

```kotlin
private fun SideTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    dot: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                tint = AppColors.Body,
                modifier = Modifier.size(21.dp)
            )
            if (dot) {
                UpdateDot(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Body,
            maxLines = 1
        )
    }
}
```

把 `IconTool` 改成：

```kotlin
private fun IconTool(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    dot: Boolean = false
) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = AppColors.Body, modifier = Modifier.size(20.dp))
        if (dot) {
            UpdateDot(Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 8.dp))
        }
    }
}
```

把 `Sidebar` 的签名加一个参数并透传：

```kotlin
fun Sidebar(
    selected: Category,
    onSelect: (Category) -> Unit,
    onSearch: () -> Unit,
    onCache: () -> Unit,
    cacheLabel: String?,
    showUpdateDot: Boolean,
    topInset: WindowInsets,
    modifier: Modifier = Modifier
) {
```

并把里面那行入口改成：

```kotlin
            SideTool(Icons.Filled.Settings, "设置", onCache, dot = showUpdateDot)
```

`PhoneHeader` 同样：

```kotlin
fun PhoneHeader(
    onSearch: () -> Unit,
    onCache: () -> Unit,
    showUpdateDot: Boolean,
    topInset: WindowInsets,
    modifier: Modifier = Modifier
) {
```

里面改成：

```kotlin
        IconTool(Icons.Filled.Settings, "设置", onCache, dot = showUpdateDot)
```

需要补的 import（`CategoryNav.kt` 顶部）：

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
```

- [ ] **步骤 3：设置页插入「更新」节**

`app/src/main/java/cn/loveq/podcast/ui/AppDialogs.kt` 的 `SettingsDialog`：

签名加参数：

```kotlin
internal fun SettingsDialog(
    cacheBytes: Long?,
    cacheCapBytes: Long,
    downloads: List<DownloadManager.Entry>,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    autoResume: Boolean,
    onAutoResumeChange: (Boolean) -> Unit,
    updateStatus: UpdateStatus,
    currentVersion: String,
    onCheckUpdate: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallPending: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onRequestUnknownSources: () -> android.content.Intent?,
    onRemove: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit
) {
```

在「关于」那一节之前插入：

```kotlin
                Spacer(Modifier.height(22.dp))

                // ---- 更新 ----
                Text(
                    "更新",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                UpdateSection(
                    status = updateStatus,
                    currentVersion = currentVersion,
                    onCheck = onCheckUpdate,
                    onDownloadAndInstall = onDownloadAndInstall,
                    onInstallPending = onInstallPending,
                    onOpenReleasePage = onOpenReleasePage,
                    onRequestUnknownSources = onRequestUnknownSources
                )
```

「关于」那一节的 `TextButton` 改成带红点：

```kotlin
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { aboutOpen = true }) {
                        Text(
                            "非官方声明与联系方式",
                            color = AppColors.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (updateStatus is UpdateStatus.Available) {
                        Spacer(Modifier.width(6.dp))
                        UpdateDot()
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "可更新",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.RedDeep
                        )
                    }
                }
```

需要补的 import（`AppDialogs.kt` 顶部）：

```kotlin
import cn.loveq.podcast.data.UpdateStatus
```

（`Row`、`Spacer`、`width`、`Alignment` 已在原文件的 import 里。）

- [ ] **步骤 4：AppScreen 接线**

`app/src/main/java/cn/loveq/podcast/ui/AppScreen.kt`：

在其它 `collectAsStateWithLifecycle()` 附近（第 94 行 `autoResume` 之后）加：

```kotlin
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val currentVersion = remember { viewModel.currentVersionName() }
    val showUpdateDot = updateStatus is UpdateStatus.Available
```

加 import：

```kotlin
import cn.loveq.podcast.data.UpdateStatus
```

给两个布局的 `Sidebar` / `PhoneHeader` 调用各加一个参数（`Sidebar(...)` 的 `cacheLabel = ...` 参数之后、`topInset = topInset` 之前）：

```kotlin
                            showUpdateDot = showUpdateDot,
```

```kotlin
                        showUpdateDot = showUpdateDot,
```

给 `SettingsDialog(...)` 调用加参数（`onAutoResumeChange` 之后）：

```kotlin
            updateStatus = updateStatus,
            currentVersion = currentVersion,
            onCheckUpdate = viewModel::checkUpdate,
            onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
            onInstallPending = viewModel::installPendingUpdate,
            onOpenReleasePage = viewModel::openReleasePage,
            onRequestUnknownSources = viewModel::unknownSourcesIntent,
```

- [ ] **步骤 5：确认编译通过**

运行：

```bash
./gradlew :app:assembleDebug --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/ui/EpisodesViewModel.kt app/src/main/java/cn/loveq/podcast/ui/AppDialogs.kt app/src/main/java/cn/loveq/podcast/ui/CategoryNav.kt app/src/main/java/cn/loveq/podcast/ui/AppScreen.kt
git commit -m "feat(update): 设置页接入「更新」节，设置入口与关于行挂红点"
```

---

## 任务 12：启动静默检查

**文件：**
- 修改：`app/src/main/java/cn/loveq/podcast/ui/MainActivity.kt`

- [ ] **步骤 1：在 onCreate 里静默查一次**

`MainActivity.kt` 里加一个字段：

```kotlin
    private val updates by lazy { (application as LoveqApp).updates }
```

在 `onCreate` 的 `lifecycleScope.launch { store.immersive.collect(::applyStatusBar) }` 之后加：

```kotlin
        // 启动静默检查：失败不出声、不写持久状态（见 UpdateCenter.check 的 manual 参数）。
        // 有新版时设置入口和「关于」行会出现红点，不打断用户收听
        lifecycleScope.launch { updates.check(manual = false) }
```

- [ ] **步骤 2：确认编译通过**

运行：

```bash
./gradlew :app:assembleDebug --offline --console=plain
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/cn/loveq/podcast/ui/MainActivity.kt
git commit -m "feat(update): 启动时静默检查一次"
```

---

## 任务 13：夹具与端到端验证

**文件：**
- 创建：`app/src/fixture/assets/update_fixture.json`

- [ ] **步骤 1：造一个「比已装版本新」的测试包**

先确认已装版本，再打一个 versionCode 8 / versionName 1.6 的包。**临时**改 `app/build.gradle.kts` 的 `defaultConfig`：

```kotlin
        versionCode = 8
        versionName = "1.6"
```

然后：

```bash
./gradlew :app:assembleRelease --offline --console=plain
cp app/build/outputs/apk/release/app-release.apk /private/var/folders/h6/lv46gkf11cx_1wywq492fjcc0000gn/T/opencode/update-test-1.6.apk
shasum -a 256 /private/var/folders/h6/lv46gkf11cx_1wywq492fjcc0000gn/T/opencode/update-test-1.6.apk
ls -l /private/var/folders/h6/lv46gkf11cx_1wywq492fjcc0000gn/T/opencode/update-test-1.6.apk
```

预期：拿到 sha256（小写 hex）和字节数。**记下这两个值。**

- [ ] **步骤 2：把版本号改回去**

把 `app/build.gradle.kts` 的 `defaultConfig` 恢复成：

```kotlin
        versionCode = 7
        versionName = "1.5"
```

不要把这个改动提交 —— 它只是造测试包用的。

- [ ] **步骤 3：上传为 prerelease**

```bash
TAG=v1.6-test
gh release create "$TAG" \
  /private/var/folders/h6/lv46gkf11cx_1wywq492fjcc0000gn/T/opencode/update-test-1.6.apk \
  --prerelease \
  --title "v1.6 测试包（临时，测完删除）" \
  --notes "在线升级功能的端到端验证用，不是正式版本。"
gh release view "$TAG" --json tagName,isPrerelease,assets
```

预期：`isPrerelease` 为 `true`，assets 里有 `update-test-1.6.apk`。

**这一步会在公开仓库上出现一个临时 prerelease。** prerelease 不会被 `/releases/latest` 返回，所以不影响正式版本与其它用户。

- [ ] **步骤 4：生成夹具 JSON**

用真实响应作模板，把 tag / 资产改指向测试包：

```bash
python3 - <<'PY'
import json, pathlib

src = pathlib.Path("app/src/test/resources/latest_v1_5.json")
dst = pathlib.Path("app/src/fixture/assets/update_fixture.json")
dst.parent.mkdir(parents=True, exist_ok=True)

d = json.loads(src.read_text())
d["tag_name"] = "v1.6"
d["name"] = "v1.6"
d["prerelease"] = True
d["body"] = ("### 端到端验证用的假版本\n\n"
             "这份响应来自夹具，不是真的 Release。资产指向一个临时 prerelease，"
             "用来把「检查 → 下载 → 三道校验 → 弹安装器」整条链跑通。")

ASSET = ("https://github.com/pigstrong9/LoveqPodcast/releases/download/"
         "v1.6-test/update-test-1.6.apk")
SHA = "<把步骤 1 记下的 sha256 填在这里>"
SIZE = 0  # <把步骤 1 记下的字节数填在这里>

if SHA.startswith("<"):
    raise SystemExit("先把 SHA 与 SIZE 换成步骤 1 实测的值再跑")

d["assets"] = [{
    "name": "update-test-1.6.apk",
    "size": SIZE,
    "browser_download_url": ASSET,
    "digest": "sha256:" + SHA,
}]

dst.write_text(json.dumps(d, ensure_ascii=False, indent=2))
print("写入", dst)
PY
python3 -c "
import json
d = json.load(open('app/src/fixture/assets/update_fixture.json'))
print('tag   =', d['tag_name'])
print('asset =', d['assets'][0]['name'], d['assets'][0]['size'], d['assets'][0]['digest'])
"
```

**先在脚本里把 `SHA` 和 `SIZE` 替换成步骤 1 的实测值，再执行。**

预期：打印出 `v1.6` 与测试包的名字 / 大小 / digest。

- [ ] **步骤 5：单测全绿**

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain
```

预期：`BUILD SUCCESSFUL`，任务 2/3/4/6 的测试全过。

- [ ] **步骤 6：打 fixture 包并装上平板**

```bash
./gradlew :app:assembleFixture --offline --console=plain
ls -l app/build/outputs/apk/fixture/
adb devices
adb install -r app/build/outputs/apk/fixture/app-fixture.apk
adb shell dumpsys package cn.loveq.podcast | grep -E "versionCode|versionName" | head -3
```

预期：`Success`；`versionName=1.5`、`versionCode=7`（原地覆盖，与已装 v1.5 同版本）。

- [ ] **步骤 7：在平板上走完整链**

1. 打开应用 → 进入设置（侧栏齿轮 / 顶栏齿轮）
2. 应看到「更新」节：`当前版本：v1.5`
3. 点「检查更新」→ 出现「有新版本 v1.6」
4. 点「下载并安装」→ 下载进度 → 「正在校验安装包…」
5. 未授权过时会出现「去授权「安装未知应用」」→ 点它 → 在系统设置里允许 → 返回
6. 系统安装界面出现 → 点「安装」
7. 装完检查：

```bash
adb shell dumpsys package cn.loveq.podcast | grep -E "versionCode|versionName" | head -2
```

预期：变成 `versionName=1.6`、`versionCode=8`。

**若在第 4/5 步失败**，按下表定位（这是本任务真正要验的东西）：

| 现象 | 含义 |
|---|---|
| 「下载失败」 | 源列表全部不通 —— 用 `adb logcat` 看是哪一步，并考虑补镜像 |
| 「安装包校验失败，已删除」 | 哈希或签名不符 —— 说明夹具里的 sha256 与刚打的包不一致，或是下载被镜像截断 |
| 「下载到的版本并不比当前新」 | 闸③ 生效，说明夹具 tag 与包内 versionCode 对不上 |
| 一直卡在「正在校验安装包…」 | 读包证书卡住，检查 `archiveFlags()` 的 SDK 分支 |

- [ ] **步骤 8：收尾**

```bash
gh release delete v1.6-test --yes --cleanup-tag
adb install -r -d /private/var/folders/h6/lv46gkf11cx_1wywq492fjcc0000gn/T/opencode/<官方 v1.5 的 apk>
git checkout app/build.gradle.kts
```

预期：prerelease 与 tag 都消失（`gh release list` 里只剩 v1.5 及更早）；平板回到官方 v1.5；`build.gradle.kts` 无改动（`git status` 干净）。

- [ ] **步骤 9：Release 构建验证真实 API**

```bash
./gradlew :app:assembleRelease --offline --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
```

打开设置 →「检查更新」→ 预期显示**「已是最新（v1.5）」**（因为当前最新发布就是 v1.5，与已装相同）。这一步验证的是真实 `releases/latest` 的解析与多镜像元数据轮换，以及「已是最新」分支。

- [ ] **步骤 10：Commit 夹具**

```bash
git add app/src/fixture/assets/update_fixture.json
git commit -m "test(update): 端到端验证用的夹具响应（指向临时 prerelease 的形态）"
```

---

## 任务 14：文档更新

**文件：**
- 修改：`agent.md`
- 修改：`README.md`

- [ ] **步骤 1：更换 `agent.md` 里的镜像清单**

把 `agent.md` 里镜像实测那一节（原文记的是 `gh-proxy.com` / `ghfast.top` / `ghproxy.net` 与一串已失效域名）换成 2026-10-16 实测：

```markdown
### GitHub 镜像实测（2026-10-16）

**元数据与资产的可达性完全不同**，两条名单必须分开：

| 源 | 元数据 `api.github.com` | 资产下载 |
|---|---|---|
| 直连 | ✅ 200 / 0.65s | 302 → `objects.githubusercontent.com` |
| `https://gh-proxy.com/` | ✅ 200 | ✅ 165 KB/s |
| `https://cdn.gh-proxy.com/` | ✅ 200 | ✅ 146 KB/s |
| `https://ghfast.top/` | ❌ 403 | ✅ 90 KB/s |
| `https://ghproxy.net/` | ❌ 403 | ⚠️ 慢且截断，**不用** |
| `gh.api.99988866.xyz`（上游官方演示站） | ❌ 死 | ❌ 死 |
| `ghproxy.1888866.xyz` | ❌ 死 | ❌ 死 |
| `ghproxy.link` / `github.moeyy.xyz` / `ghps.cc` / `gh-proxy.net` | ❌ 死 / 需鉴权 | ❌ |

**这份名单会腐坏**：同日实测，本文件原先记录的 11 个镜像全部失效，连上游项目
`hunshcn/gh-proxy` 文档里写的官方演示站都不通了。所以在应用内的源列表之外，
必须保留「全部源失败 → 打开 Release 页面」这条兜底路径。

源用「前缀」表示（空前缀 = 直连），名单在
`app/src/main/java/cn/loveq/podcast/data/UpdateSources.kt`。
```

- [ ] **步骤 2：`agent.md` 增加在线升级一节**

在「发布方式」相关小节之后加：

```markdown
## 在线升级（2026-10-16）

应用内可检查更新 → 下载 → 三道校验 → 唤出系统安装器（用户点「安装」）。

**发布约定（新增，必须遵守）**：

1. Release 的 **tag 必须写成 `v<versionName>`**（如 `v1.6`），且 `versionCode` 递增。
   App 从 tag 解析版本名比对；下载后再用包内真实的 `versionCode` 复核（必须严格大于），
   所以 tag 写错最多是白下载一次，不会降级安装。
2. 资产挂 `.apk`；`digest`（sha256）由 GitHub 自动生成，无需额外步骤。

**三道校验**：① sha256 == API 的 `digest`（没有 digest 时跳过）② 包的签名证书 ==
已装应用的证书 ③ 包内 `versionCode` > 已装 `versionCode`。第 ② 道是真正的真伪闸。

**实现文件**：`data/{VersionTags,UpdateSources,GithubReleases,UpdateDownloader,ApkInstaller,UpdateCenter}.kt`、
`ui/UpdateSection.kt`；单例在 `LoveqApp.updates`。

**测试**：`./gradlew :app:assembleFixture` 打出的包会读夹具
（`app/src/fixture/assets/update_fixture.json`）而不是真 API。夹具构建 `initWith(release)`
是为了拿正式签名 —— 否则签名闸必然拦下。端到端流程见
`docs/superpowers/plans/2026-10-16-online-update.md` 任务 13。
```

- [ ] **步骤 3：README 加一条功能**

在 README 的功能列表里加：

```markdown
- **应用内升级**：检查 GitHub 上的新版本 → 应用内下载 → 校验后交给系统安装界面
```

- [ ] **步骤 4：Commit**

```bash
git add agent.md README.md
git commit -m "docs(update): 记录镜像实测结论、在线升级实现与 tag 发布约定"
```

---

## 自检记录

**规格覆盖度**（逐节对照 `2026-10-16-online-update-design.md`）：

| 规格章节 | 对应任务 |
|---|---|
| §3 版本判断机制 | 任务 2（`VersionTags`）、任务 4（tag 解析）、任务 8（闸③ 复核） |
| §4 组件与职责 / §4.1 接口 | 任务 2–10，逐个文件对应 |
| §4.2 状态机 | 任务 8（`UpdateStatus`/`FailureKind`） |
| §5 镜像与网络策略 | 任务 3（名单与轮换）、任务 5（元数据轮换）、任务 6（资产轮换）、任务 8（`openReleasePage` 兜底） |
| §6 数据流 | 任务 8 + 任务 12（启动静默检查） |
| §7 三道闸 | 任务 6（闸①）、任务 8（闸②③ 的调用点）、任务 7（闸② 的实现） |
| §8 界面 | 任务 10、任务 11 |
| §9 错误处理 | 任务 8（`FailureKind` 归类）、任务 10（每种给不同的话） |
| §10 存储与生命周期 | 任务 8（`cacheDir/update`、app 级 scope、启动清残留）、任务 3（`loveq_update` prefs） |
| §11 权限与清单 | 任务 1 |
| §12 测试策略 | 任务 2/3/4/6（单测）、任务 13（夹具 + 端到端） |
| §13 已知限制 | 无需代码，已在规格记录 |
| §14 发布流程影响 | 任务 14 |
| §15 待确认 | 已确认：采用 prerelease 方式（任务 13） |

**类型一致性核对**（跨任务引用同一符号的地方）：

- `VersionTags.parse` / `isNewer`：任务 2 定义，任务 4（`parseLatestRelease` 调 `parse`）与任务 8（调 `isNewer`）使用 ✓
- `UpdateSources.API` / `ASSET` / `promoted`：任务 3 定义，任务 5（`API`）、任务 6（`ASSET`）使用 ✓
- `SourceRotation.next()`：任务 3 定义，任务 5、6 使用 ✓
- `SourceMemory` / `PrefsSourceMemory`：任务 3 定义，任务 5、6、9 使用 ✓
- `GithubReleases.KEY_API` / `KEY_ASSET` / `USER_AGENT`：任务 5 定义，任务 6（`USER_AGENT`）与任务 5/6（两个 KEY）使用 ✓
- `parseLatestRelease` / `ReleaseInfo` / `UpdateCheckException`：任务 4 定义，任务 5（`GithubReleases`、`FixtureReleaseSource`）使用 ✓
- `sha256Of`：任务 6 定义并单测，暂无生产调用方（保留给启动清残留时自检，属规格 §10 的预留）✓
- `Downloading(progress, source)`：任务 8 定义，任务 6 的回调 `(Float, String) -> Unit` 与之对齐，任务 10 显示 ✓
- `BuildConfig.UPDATE_USE_FIXTURE`：任务 1 定义（三个 buildType 都有），任务 9 使用 ✓

**发现的缺陷并已内联修复**：

1. **`SourceRotation.markOk()` / `lastOk` 是死代码**（同一件事有两个机制：它和 `SourceMemory.remember`，而成功路径只走后者）。已删除，并删掉对应单测；`SourceRotation` 只留 `next()`。
2. **`parseLatestRelease` 里原本有个 `VersionTags.parse() == null → return null` 的判断**，与规格 §3 冲突：规格要求「tag 不可解析 → 按不可比 → 已是最新处理」，而在解析层返回 null 会被 `GithubReleases` 抛成异常，最终显示成「检查失败」。已删除该判断，改由 `VersionTags.isNewer` 判为 `UpToDate`；对应单测也改成断言「解析得出、但不提示升级」。
3. **任务 2 的测试初稿有两处会红**：`assertNull(parse("v1.5 "))` 与 `assertNull(parse("1.5.1.2.3..."))` —— `parse` 会先 `trim()`，且长版本号本就该能解析。已移入正确的用例。
4. **任务 3 的测试原本用了 Kotlin 的 `assert(...)`**。已改成 `assertTrue(...)`，理由是**显式**：不依赖测试运行器是否传 `-ea`。
   （更正：本计划初稿断言它「在 JVM 上默认不生效、等于白写」是**错的**。2026-10-16 用探针实测 ——
   往测试里放一条 `assert(false)` 并运行 —— 结果是测试 **FAILED**，说明 Gradle 的 `Test` 任务
   默认 `enableAssertions = true`，Kotlin 的 `assert()` 在 `testDebugUnitTest` 下是**真的生效**的。
   所以原写法不会白写，只是不够显式；`assertTrue` 仍更稳妥。）
5. **任务 4 里「没有 apk 资产」的用例靠字符串替换真实响应**，依赖 GitHub 的 JSON 缩进恰好是 `"name": "xxx"`（带空格），换个版本就可能失效。已改成手写字面量 JSON。
6. **`FixtureReleaseSource` 若放进 `src/fixture/java/`，`src/main` 的 `LoveqApp` 会编译不过**（release 变体找不到该类）。所以它放在 `src/main`，靠 `BuildConfig.UPDATE_USE_FIXTURE` 是编译期常量让 R8 在 release 里删掉整个分支。
7. **任务 10 的 import 里有一批用不到的**（`Arrangement`、`offset`、`padding`、`Color`、`getValue`/`setValue`/`mutableStateOf`/`remember`）以及一个全限定名 `android.content.Intent`。已清理。
8. **`installer.install()` 原本被外层笼统的 `catch` 兜成 `FailureKind.DOWNLOAD`**，而 `FailureKind.INSTALL` 永远产生不出来（枚举值成了摆设）。已单独包一层，报成 `INSTALL`。
9. **任务 9 里 `PrefsSourceMemory` 被 new 了两次**（一次给 `GithubReleases`、一次给 `UpdateCenter`）。已收成一个实例。
10. 规格 §4.1 把「夹具实现」列在 `GithubReleases.kt` 的代码块里但没明说位置 —— 本计划确定为**同一文件**，与规格一致。
11. 规格 §4.2 的 `Downloading` 带 `source` 字段，本计划第一稿漏了。已补回，并让进度文案显示当前镜像名。
