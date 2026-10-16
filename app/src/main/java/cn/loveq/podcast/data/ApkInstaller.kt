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
