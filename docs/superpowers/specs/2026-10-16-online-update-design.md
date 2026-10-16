# 在线升级（应用内检查 → 下载 → 安装）设计

日期：2026-10-16
状态：待评审
方案：A（tag 约定 → 版本名比对 → 下载后 versionCode 复核）

## 1. 背景与目标

应用目前只能靠用户自己去 GitHub Release 页面下载新 APK 手动安装。目标是把这个过程收进应用内：

**应用内检查是否有新版本 → 下载 APK → 校验 → 唤出系统安装界面（用户点「安装」）。**

不做静默安装：最后一步必须由用户在系统安装器上确认。

## 2. 范围

**做**

- 启动时静默检查一次（不弹框，有新版才在设置里显示）
- 设置页手动「检查更新」
- 应用内下载 APK，多镜像自动切换，边下边校验
- 安装前三道校验（哈希 / 签名 / 版本号）
- 唤出系统安装器；未授权「安装未知应用」时引导去系统设置

**不做（YAGNI，明确记录以免日后顺手加上）**

- 不后台自动下载、不静默安装（必须用户点确认）
- 不强制更新、不阻断使用
- 不做增量 / delta 更新
- 不做多 ABI 分包（车机是 arm64-v8a，单包）
- 不自建更新服务器（就用 GitHub）；不做设置里手填镜像地址
- 不渲染 markdown（更新说明按纯文本显示）
- 不做 TLS 证书固定（公开仓库，收益低且脆）

## 3. 版本判断机制（方案 A）

GitHub Release 里没有放 `versionCode` 的地方，所以：

**约定：Release 的 tag 写成 `v<versionName>`**，例如 `v1.5`、`v1.6`。

比对流程：

1. 从 `tag_name` 去掉可选的 `v`/`V` 前缀，得到远程版本名
2. 与本机已装 `versionName` **分段按数字**比较（不是字典序：`1.10 > 1.9`）
3. 判定有新版 → 下载
4. 下载完、安装前，用包内真实的 `versionCode` 与已装 `versionCode` **复核**，必须严格大于才允许安装

第 4 步是安全网：tag 写错（比如忘了 bump）时，最多是白下载一次，不会真的降级安装。

历史 tag（`v1.0.0` / `v1.1` / `v1.2` / `v1.4` / `v1.5`）形态都符合 `v<版本名>`，解析无障碍。

**无法解析时的策略**：任一段不是数字（例如 `v1.5-beta`）就整体判为「不可比」，按「已是最新」处理。宁可不提示升级，也不误升。本地版本名解析不出时同样不提示 —— 本地版本来自我们自己的构建，正常情况不会出现。

## 4. 组件与职责

`LoveqApp` 是项目的单例容器，更新服务照既有惯例在那里 `onCreate` 建好。

| 新文件 | 职责 | 依赖 |
|---|---|---|
| `data/VersionTags.kt` | 纯函数：解析 tag、分段比版本 | 无 |
| `data/UpdateSources.kt` | 两个镜像源列表；`SourceRotation` 状态机；「上次成功的源提到最前」纯函数 | 无 |
| `data/GithubReleases.kt` | **唯一碰 GitHub 的地方**。取 `releases/latest`，解析成 `ReleaseInfo` | 网络 |
| `data/UpdateDownloader.kt` | 下载 APK + 边下边算 sha256 + 按源轮换重下 | 网络 |
| `data/ApkInstaller.kt` | 读 APK 的 versionCode / 签名、比对已装应用证书、唤出安装器、判断未知来源权限 | PackageManager |
| `data/UpdateCenter.kt` | 编排 + 状态持有者，放 `LoveqApp` | 上面五个 |
| `ui/UpdateSection.kt` | 设置页的「更新」区 | UpdateCenter |

**三个刻意的设计选择**：

1. **「碰 GitHub」收成一个文件** —— 以后要审查「这个应用到底请求了哪些外部服务」，只有一处要看。
2. **版本比较和源轮换做成纯函数 / 纯状态机** —— 跟 `RequestPacer`、`Progress.canResume` 一样，把最容易写错的判断做成不碰设备、能单测的东西。这也让本项目**不必引入 `kotlinx-coroutines-test`**。
3. **`UpdateDownloader` 与 `ApkInstaller` 分开** —— 一个依赖 okhttp、一个依赖 PackageManager，混在一起以后没法单独测。

### 4.1 各组件接口

```kotlin
// data/VersionTags.kt
internal object VersionTags {
    /** "v1.5" / "1.5" / "1.5.0" → [1,5] / [1,5,0]；解析不出返回 null */
    fun parse(tag: String): List<Int>?
    /** 远程是否比本地新；任一侧解析不出都返回 false */
    fun isNewer(remote: String, local: String): Boolean
}
```

比较规则：逐段比数字，短的一侧补 0，所以 `1.5` 与 `1.5.0` 视为相同（`1.5.0` 不算更新）。

`ReleaseInfo` 定义在 `GithubReleases.kt` 里（它是唯一的产出方）；`UpdateStatus` / `FailureKind` 定义在 `UpdateCenter.kt` 里。跟 `DownloadManager.State`、`DownloadManager.Entry` 那样把相关类型收在产生它的类附近，不为一个 data class 单独开文件。

```kotlin
// data/GithubReleases.kt
data class ReleaseInfo(
    val versionName: String,   // 从 tag 解析
    val apkUrl: String,        // 选中的资产原始 URL（github.com 的 download 地址）
    val apkName: String,
    val apkSize: Long,
    val sha256: String?,       // 来自 asset.digest，形如 "sha256:<hex>"；老 Release 可能没有
    val notes: String          // body，纯文本展示
)
```

```kotlin
// data/UpdateSources.kt
internal object UpdateSources {
    /** 元数据源：能代理 api.github.com 的 */
    val API: List<String>
    /** 资产源：能下载 github.com release 资产的 */
    val ASSET: List<String>
    /** 把上次成功的源提到最前（纯函数） */
    fun promoted(sources: List<String>, lastOk: String?): List<String>
}

/**
 * 源轮换状态机。仿 RequestPacer：纯逻辑、单独放一个文件、单独测。
 * 最容易写错的是「试过的源又试一遍」和「最后一个源之后不返回 null」，都是安静的错。
 */
internal class SourceRotation(sources: List<String>) {
    fun next(): String?          // 下一个待试的源；试完返回 null
    fun markOk(source: String)   // 记下成功的源（供 promoted 下次提前）
    val lastOk: String?
}
```

```kotlin
// data/GithubReleases.kt
internal interface ReleaseSource {
    suspend fun latest(): ReleaseInfo
}

/** 真实实现。基址固定 https://api.github.com，不靠 BuildConfig 改指向 */
internal class GithubReleases(private val client: OkHttpClient) : ReleaseSource

/** 夹具实现，只在 fixture 构建里替换上来（见 §11） */
internal class FixtureReleaseSource(private val context: Context) : ReleaseSource
```

`LoveqApp` 按 `BuildConfig.UPDATE_USE_FIXTURE` 决定 new 哪一个 —— **换的是整个 `ReleaseSource` 实现，不是给 `GithubReleases` 塞一个假的基址**。这样 `GithubReleases` 里没有一行测试代码，发布包里也不存在分支。

- **独立的 OkHttpClient，刻意不复用 `LoveqRepository`**：后者的全局限速闸门与冷却阶梯是为 loveq.cn 的 405 定制的，GitHub 请求既不该被官网的冷却拦住，也不该去挤官网的请求槽。`DownloadManager` 已经示范了这个写法（自带 client + app 级 scope）。
- UA 写明身份：`LoveqPodcast/1.5 (+https://github.com/pigstrong9/LoveqPodcast)`。GitHub API 强制要求 UA；不伪装、不轮换。
- 选资产：优先名字含 tag 的 `.apk`，否则取第一个 `.apk`；一个都没有 → 判定失败。
- 解析失败 / HTTP 非 2xx → 抛异常，由 `UpdateCenter` 归类。

```kotlin
// data/UpdateDownloader.kt
internal class UpdateDownloader(private val sources: List<String>, private val dir: File) {
    /** 依次试源；成功返回落盘的 APK 文件 */
    suspend fun fetch(info: ReleaseInfo, onProgress: (Float) -> Unit): File
}
```

- 下载到 `<apkName>.part`，**全部下完才 rename 成正式名**：半截文件不会被当成下载成功。
- 边下边算 sha256；下完与 `info.sha256` 比对，不符 → **删掉 `.part`，换下一个源**。
- `info.sha256 == null` → 跳过哈希闸，仅靠签名闸（界面会注明）。
- 全部源失败 → 抛 `UpdateException.Download`（带最后一次原因；`UpdateException` 也定义在本文件）。
- 超时：connect 15s / read 30s / call 10min（APK 4.3MB，慢镜像上要留够）。

```kotlin
// data/ApkInstaller.kt
internal class ApkInstaller(private val context: Context) {
    /** 读包内真实 versionCode（getPackageArchiveInfo） */
    fun versionCodeOf(file: File): Long?
    /** 包的签名证书 == 已装应用的证书？任一读不到都返回 false（失败关闭） */
    fun isTrusted(file: File): Boolean
    fun needsUnknownSources(): Boolean        // API 26+ 才查，以下恒 false
    fun unknownSourcesIntent(): Intent?
    fun install(file: File)                    // FileProvider + ACTION_VIEW
}
```

- 签名读取统一用 `PackageInfoCompat.getSignatures()`（内部处理 API 28 前后 `GET_SIGNATURES` / `GET_SIGNING_CERTIFICATES` 的差异，minSdk 24 必需）。
- **失败关闭**：只要有一侧读不到证书，就判为不可信、不安装。

```kotlin
// data/UpdateCenter.kt（LoveqApp 持有）
class UpdateCenter(
    context: Context,
    private val source: ReleaseSource,
    private val downloader: UpdateDownloader,
    private val installer: ApkInstaller
) {
    val status: StateFlow<UpdateStatus>
    fun check(manual: Boolean)          // 启动静默与手动点按钮共用这一个入口
    fun downloadAndInstall()
    fun openReleasePage()               // 降级：浏览器打开 releases 页
    fun currentVersionName(): String
}
```

### 4.2 状态机

```kotlin
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val current: String) : UpdateStatus
    data class Available(val info: ReleaseInfo) : UpdateStatus
    data class Downloading(val progress: Float, val source: String) : UpdateStatus
    data object Verifying : UpdateStatus
    data class ReadyToInstall(val file: File) : UpdateStatus
    data class Failed(val kind: FailureKind, val detail: String?) : UpdateStatus
}

enum class FailureKind { CHECK, DOWNLOAD, INTEGRITY, UNKNOWN_SOURCES, INSTALL }
```

`Verifying` 是一个**可见状态**而不是瞬间过程：三道闸要读包、比证书，在慢设备上有可感知的耗时，进度条一直转但不说在做什么会让人以为卡死。

## 5. 镜像与网络策略

### 5.1 实测（2026-10-16）

`agent.md` 原先记录的 11 个镜像**今天一个都不通**（全部 000）。重新实测：

| 源 | 元数据 `api.github.com` | 资产下载 |
|---|---|---|
| 直连 | ✅ 200 / 0.65s | 302 → `objects.githubusercontent.com` |
| `https://gh-proxy.com/` | ✅ 200 | ✅ 165 KB/s |
| `https://cdn.gh-proxy.com/` | ✅ 200 | ✅ 146 KB/s |
| `https://ghfast.top/` | ❌ 403 | ✅ 90 KB/s |
| `https://ghproxy.net/` | ❌ 403 | ⚠️ 慢且疑似截断，**不采用**（见 `agent.md` 实测） |
| `https://gh.api.99988866.xyz/`（`hunshcn/gh-proxy` 官方演示站） | ❌ 死 | ❌ 死 |
| `https://ghproxy.1888866.xyz/`（WJQSERVER 演示站） | ❌ 死 | ❌ 死 |
| `ghproxy.link` / `github.moeyy.xyz` / `ghps.cc` / `gh-proxy.net` | ❌ 死 / 需鉴权 | ❌ |

**关键结论：元数据与资产的可达性完全不同**（`ghfast.top` 能下资产但对 API 返回 403），所以必须是**两条独立的源列表**。

注意 `gh.api.99988866.xyz` 是上游项目文档里写的官方演示站，现在已不可达 —— 镜像名单一定会腐坏。

### 5.2 内置列表

源统一表示成**前缀**：直接写原始 GitHub URL，镜像前缀拼在它前面。**空前缀 `""` 表示直连。**

```kotlin
// 元数据：拼接后为 https://api.github.com/repos/pigstrong9/LoveqPodcast/releases/latest
API   = ["", "https://gh-proxy.com/", "https://cdn.gh-proxy.com/"]

// 资产：拼接后为 https://github.com/pigstrong9/LoveqPodcast/releases/download/<tag>/<asset>
ASSET = ["https://gh-proxy.com/", "https://cdn.gh-proxy.com/", "https://ghfast.top/", ""]
```

**两条列表的顺序刻意不同**：

- 元数据**直连优先** —— 实测直连 `api.github.com` 最快最稳（0.65s），镜像反而慢一倍。
- 资产**镜像优先、直连垫底** —— 实测直连 `github.com` 只有约 1/3 成功率且失败要等约 21 秒超时，放在前面就是每次白等。垫在最后是因为**能直连的网络（海外、带代理）下它是最优的一跳**，代价只是所有镜像都失败后多等一次超时，换来一条真实的兜底路径。

`ghproxy.net` 不列入：`agent.md` 实测它 120 秒只下到 4.04 MB 就截断（`ghfast.top` 同批测试是 4.33 MB / 11.7s），属已知劣质源。截断本身会被闸① 拦下，但让用户白等两分钟不值得。

### 5.3 自适应与兜底

- **记住上次成功的源**：`SourceRotation.markOk()` 记下的源写进 `loveq_update` prefs，下次用 `promoted()` 提到列表最前。车机在热点/4G 之间切换时，这一步省掉一次必败的尝试。
- **每个源的连接超时 15 秒**；整体上限分别由 OkHttp 的 `callTimeout` 管住（元数据 60 秒、下载 10 分钟，见 §4.1）。失败立刻换下一个源，**不重试同一个** —— 同一个源刚刚失败，再试基本还是失败，只是让人白等。
- **全部源都失败** → 降级为「打开浏览器到 Release 页面」，让用户手动下载。这是最低保底的可用性。

## 6. 数据流

```
启动静默（MainActivity.onCreate）或手动点「检查更新」
  └→ UpdateCenter.check(manual)
       ├─ 读已装 versionName / versionCode（PackageManager）
       ├─ SourceRotation(API) 依次取 releases/latest
       ├─ VersionTags.isNewer(远程, 已装)？
       │    ├─ 否 → UpToDate
       │    └─ 是 → Available(info)   ← 「关于」行挂红点
       └─ 全失败 → manual ? Failed(CHECK) : 保持静默

App 内点「下载并安装」
  └→ UpdateCenter.downloadAndInstall()
       ├─ SourceRotation(ASSET) 依次下载（边下边算 sha256，按需换源）
       ├─ Verifying
       │    ├─ 闸① sha256 == digest（digest 缺失则跳过并注明）
       │    ├─ 闸② 包的签名证书 == 已装应用证书   ← 失败即删文件、报 INTEGRITY
       │    └─ 闸③ 包 versionCode > 已装 versionCode
       ├─ ReadyToInstall(file)
       ├─ needsUnknownSources()？ → 引导去系统设置，回来自动续上
       └─ installer.install(file) → 系统安装器（用户点「安装」）
```

## 7. 安全校验（三道闸）

| 闸 | 防止什么 | 依据 | 必需？ |
|---|---|---|---|
| ① sha256 == `asset.digest` | 镜像投毒、传输截断 | GitHub API 的 HTTPS 响应 | digest 存在时必需 |
| ② 签名证书 == **已装应用的证书** | 伪造包、同名冒充 | 设备上现有应用的签名 | **必需** |
| ③ 包 `versionCode` > 已装 `versionCode` | tag 写错导致降级安装 | 下载包的 `AndroidManifest` | **必需** |

**闸② 是真正的真伪闸**：只有持有本项目 keystore 的人能签出可安装的包，镜像再怎么投毒也过不了这一关。闸① 是便宜的事先拦截（还能省掉一次无意义的签名解析）；闸③ 兜住方案 A 依赖 tag 字符串的风险。

**已知残余风险**：keystore 泄露则全部防线失效。这是所有侧载分发方案共同的前提，本项目不引入额外机制。

## 8. 界面

落点跟现有结构走：

- `SettingsDialog` 现在是「显示 / 播放 / 收听缓存 / 关于」四节，**在「关于」之前插入一节「更新」**（调用 `UpdateSection`），其余三节不动。
- 「更新」节内容：当前版本 → 状态文本 → 「检查更新」按钮 → 有新版时展开更新说明（纯文本）→ 「下载并安装」按钮 → 下载时是进度条。
- 更新说明要**限高并可滚动**（v1.5 的说明是 723 字符，将来只会更长）：不限高会把「下载并安装」按钮挤出弹窗可视区，而设置页本身也在滚，两个滚动区嵌套很难用。
- 「关于」里那行 `LoveqPodcast v1.5` 已经是用 `PackageManager` 读的 `versionName`；**有新版本时在那行后面挂一个红点**，用户不点进「更新」节也能看见。
- 侧栏 / 手机顶栏的设置入口按钮（`Sidebar.onCache`、`PhoneHeader.onCache`）也加一个红点 —— 这是唯一"不进弹窗就能看见"的位置。两侧各加一个 `showUpdateDot: Boolean` 参数。

`SettingsDialog` 新增参数（沿用现有的「传值 + 回调」风格，不直接传 `UpdateCenter`）：

```kotlin
updateStatus: UpdateStatus,
showUpdateDot: Boolean,
onCheckUpdate: () -> Unit,
onDownloadAndInstall: () -> Unit,
```

「打开 Release 页面」和「去授权未知来源」两个动作在 `UpdateSection` 里用 `LocalContext` 自己发起，不必再穿一层回调。

## 9. 错误处理

每一类给用户看的话都不一样：

| 情况 | 启动静默检查 | 手动检查 |
|---|---|---|
| 全部源取不到元数据 | 完全静默，什么也不显示 | 「检查失败，请稍后重试」+ 重试 / 打开网页 |
| 已是最新 | 静默 | 「已是最新（v1.5）」 |
| 有新版 | 只在「关于」和设置入口挂红点 | 展开更新说明 + 下载按钮 |
| 下载全源失败 | —— | 「下载失败」+ 重试 / 打开网页 |
| **哈希或签名校验不过** | —— | 显眼报「安装包校验失败，已删除」+ 打开网页 ← 这是安全事件，不能只说"失败" |
| 未授权「安装未知应用」 | —— | 按钮变「去授权」，跳系统设置，回来后自动续上 |
| 用户在系统安装器点取消 | —— | 回到「已下载，可再次安装」，**不算错误** |
| 包 versionCode 没有大于已装 | —— | 「下载到的版本并不比当前新，已删除」（tag 与 versionCode 不一致） |

静默检查失败**不写入任何持久状态**：网络抖一下不能变成"以后都不查了"。

## 10. 存储与生命周期

- 下载暂存到 **`cacheDir/update/`**：`MediaCache` 只用 `cacheDir/media`（受 2GB 配额与 LRU 淘汰管辖），`cacheDir/update` 天然独立，不会被缓存清理顺手删掉、也不占那 2GB 配额。
- 装完立刻删；校验失败立刻删；**启动时清一次残留**（进程被杀会留下 `.part`，不主动清会一直躺着）。
- 下载跑在 `UpdateCenter` 自己的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 上（照 `DownloadManager` 的写法）：关掉设置弹窗不会掐断下载，重新打开还能看到进度。
- 持久化：新的 prefs 文件 `loveq_update`，目前只存「上次成功的源」。**不复用 `LoveqRepository.USER_AGENT` 之外的任何官网状态**。
- **更新状态本身不持久化**：每次启动重新查一次。上一轮的结论（比如"有新版本"）在应用被更新之后仍然留着，会显示成错的。

## 11. 权限与清单改动

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

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

`res/xml/file_paths.xml`：`<cache-path name="update" path="update/" />`

理由：minSdk 24 / targetSdk 35 下必须用 `FileProvider` + content URI 才能把 APK 交给安装器 —— 直接用 `file://` 会抛 `FileUriExposedException`。

`build.gradle.kts`：

- `buildFeatures { buildConfig = true }`（当前只有 `compose = true`）—— 需要一个编译期开关来选 `ReleaseSource`
- 新增一个 buildType（名字定为 `fixture`）：

```kotlin
buildTypes {
    release { /* 不动 */ }
    debug   { /* 不动 */ }
    create("fixture") {
        initWith(getByName("release"))   // 关键：拿到 release 的正式签名
        isMinifyEnabled = false          // 测试包不需要 R8，编译更快
        isShrinkResources = false
        buildConfigField("boolean", "UPDATE_USE_FIXTURE", "true")
    }
}
```

**为什么必须是独立 buildType，而不是直接用 debug**：闸② 要求「下载包的签名证书 == 已装应用的证书」。debug 构建是 debug keystore 签的，而测试用的"新版本"包是正式 keystore 签的，两者必然不一致 —— 用 debug 包测，闸② 一定会拦下来。所以夹具模式必须跑在**正式签名**的构建上。`initWith(release)` 就是为了拿到这套签名。

夹具资产放 `app/src/fixture/assets/update_fixture.json`，只进 `fixture` 构建，不进发布包（4.5KB 的快照没必要躺在每个正式包里）。

`REQUEST_INSTALL_PACKAGES` 会被 Play 商店审查，但本应用是侧载自用、不上架，无影响；这句写在这里是为了以后有人问起时有据可查。

## 12. 测试策略

### 12.1 单元测试（纯 JVM，不碰设备）

沿用项目现有风格（中文测试名、注释写清"这里写错会怎样"），**不引入 `kotlinx-coroutines-test`**：

- `data/VersionTagsTest.kt`
  - `v1.5` / `V1.5` / `1.5` / 带空白 都能解析
  - `1.10` > `1.9`（数字比较，不是字典序）
  - `1.5` == `1.5.0`（补 0 后相等，不算更新）
  - `v1.5-beta`、空串、`v`、`vv1.5` → 解析为 null，`isNewer` 返回 false（宁可不升，不误升）
  - 本地版本比远程新时返回 false
- `data/UpdateSourcesTest.kt`
  - `SourceRotation` 不重复给出同一个源、试完返回 null
  - `markOk` 之后 `promoted` 把该源提到最前
  - `promoted(null)` 保持原顺序

### 12.2 测试夹具（fixture 构建专用）

`app/src/fixture/assets/update_fixture.json` —— 一份**真实的 `releases/latest` 响应快照**（已抓取，4541 字节），改掉 `tag_name` / 资产 URL / `digest` 指向测试包。

`fixture` 构建用 `FixtureReleaseSource` 读它，`fixture` 之外的构建一律用 `GithubReleases` 打真 API。这样"检查"这一步可控可重复，而**下载、哈希、签名、唤安装器这几步全部走真实网络与真实校验** —— 风险都在后半段，夹具正是不该替它们兜底的地方。

### 12.3 端到端验证

夹具模式需要一个"比已装 v1.5 更新"的包，所以要先造一个：

1. 用**同一套 keystore** 打一个 `versionCode 8` / `versionName 1.6` 的本地包（**只用于测试，不发布**）
2. 把它作为 **prerelease** 资产上传到仓库 —— prerelease 不会被 `/releases/latest` 返回，不会污染正式版本；测完删除
3. 夹具 JSON 指向该 prerelease 资产的真实下载地址，`digest` 填该包的真实 sha256
4. 平板上已装官方 v1.5 → `adb install -r` 装上 `fixture` 构建（同为正式签名、同为 versionCode 7，可原地覆盖）→ 启动 → 设置 →「检查更新」→ 应显示 1.6 → 下载 → 三道闸 → 弹系统安装器 → 点安装 → 变成 1.6
5. 收尾：删掉 prerelease；用 `adb install -r -d` 装回官方 v1.5（versionCode 7 < 8，必须带 `-d`）

另外用 **Release 构建 + 真 API** 跑一次，验证真实 `releases/latest` 的解析与「已是最新」分支（当前最新是 v1.5，与已装相同，所以这一轮只走到 `UpToDate`，属于预期）。

### 12.4 需要用户确认的一点

第 12.3 步会在**公开仓库上短暂出现一个 prerelease**（约几分钟，测完即删）。如果不想让仓库出现任何临时条目，替代方案是自己搭本地 HTTP 服务（需要给 `fixture` 构建开明文流量豁免 + `adb reverse`），代价是测不到镜像这一跳。

## 13. 已知限制与风险

- **tag 与 versionName 靠约定**：写错会误判，但闸③ 保证最坏也只是白下载一次。
- **镜像名单会腐坏**：今天实测已有一批（含上游官方演示站）失效。内置列表是"当前最优快照"，不是长期保证；兜底是打开 Release 页面。
- **GitHub 未认证 API 限 60 次/小时/IP**：启动查一次 + 手动查，远够用。不做缓存跳过。
- **Android 7（API 24/25）没有 per-app 未知来源开关**：`ACTION_MANAGE_UNKNOWN_APP_SOURCES` 是 API 26 才有的，低版本只能靠系统级的"未知来源"总开关，这里不做额外引导。
- **车机的授权路径可能被厂商定制**：跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 后各家的界面不一样，属于系统行为，应用侧无法完全掌控。
- **请求 GitHub 会暴露 IP**：与使用任何在线服务同理；UA 写明身份、不伪装不轮换。
- **不做断点续传**：换源重下。APK 4.3MB，不值得为此加状态。

## 14. 对发布流程的影响

每次发版多两条约定：

1. **tag 必须写成 `v<versionName>`**（如 `v1.6`），且 `versionCode` 递增
2. 资产挂 `.apk`；`digest` 由 GitHub 自动生成（2026 已实测有值），无需额外步骤

`agent.md` 里的镜像清单和首次发包流程要一并更新（见实现计划）。

## 15. 待确认

- 第 12.4 节的测试方式：接受短暂出现一个临时 prerelease，还是改用本地 HTTP 服务？
