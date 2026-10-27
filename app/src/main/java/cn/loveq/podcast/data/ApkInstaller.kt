package cn.loveq.podcast.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
        val archive = archiveCertDigests(file)
        val installed = installedCertDigests()
        if (archive != null && installed != null) return archive == installed
        // 读不到也失败关闭，但要说清是哪一侧读不到：界面只会报「校验失败」，
        // 分不出「签名真不一致」和「这台机器读不出证书」是两件完全不同的事
        Log.w(TAG, "签名不可判定 archive=$archive installed=$installed")
        return false
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
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val legacy = info.signatures
        return signaturesToDigests(modern) ?: signaturesToDigests(legacy)
    }

    /**
     * 已装应用自己的证书摘要集合；用 PackageInfoCompat 以避开 API 28 前后的差异。
     *
     * 读不到时再退回老接口兜一次：车机上 `signingInfo` 可能为空，而
     * `GET_SIGNATURES` 那条路仍然可用。失败关闭不该建立在「一次读不到就判死」上。
     */
    private fun installedCertDigests(): Set<String>? {
        val modern = runCatching {
            PackageInfoCompat.getSignatures(context.packageManager, context.packageName)
                .toTypedArray()
        }.getOrNull()
        signaturesToDigests(modern)?.let { return it }
        return runCatching {
            @Suppress("DEPRECATION")
            val legacy = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
            signaturesToDigests(legacy)
        }.getOrNull()
    }

    private fun signaturesToDigests(signatures: Array<Signature>?): Set<String>? {
        if (signatures.isNullOrEmpty()) return null
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    /**
     * 必须把 GET_SIGNING_CERTIFICATES 和**已废弃的** GET_SIGNATURES 一起传。
     *
     * Android 9/10 上只给新版 flag 时，`getPackageArchiveInfo` 会返回
     * `signingInfo == null`（Google issue 159537841），而系统安装器走的是另一条路，
     * 照样能覆盖安装 —— 于是车机（DiLink 4.0，Android 10）上表现为
     * 「手动装得上、应用内却永远校验失败」。两个 flag 都带时新旧两条路都能读到证书，
     * 证书本身与用哪个 flag 读到无关，比对逻辑不用动。
     */
    private fun archiveFlags(): Int {
        @Suppress("DEPRECATION")
        val legacy = PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or legacy
        } else {
            legacy
        }
    }

    private companion object {
        const val TAG = "ApkInstaller"
    }
}
