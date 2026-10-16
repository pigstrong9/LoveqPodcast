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
