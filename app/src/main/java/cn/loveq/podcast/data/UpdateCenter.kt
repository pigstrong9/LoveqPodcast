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
 * 声明成 internal：构造参数 [ReleaseSource] / [ApkInstaller] / [SourceMemory] 都是 internal，
 * public 构造器会触发 EXPOSED_PARAMETER_TYPE。与 [UpdateDownloader] / [ApkInstaller] 一致，
 * 同模块内使用没有问题。
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
