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
