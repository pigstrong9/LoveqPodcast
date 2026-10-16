@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package cn.loveq.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.playback.MediaCache
import cn.loveq.podcast.ui.theme.AppColors
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun DialogHost(
    onDismiss: () -> Unit,
    title: String,
    text: @Composable () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 弹窗是独立窗口，主窗口那套状态栏避让传不进来。设置里把「隐藏状态栏」
        // 关掉的一瞬间，状态栏就会直接压在标题和开关上，而 AlertDialog 默认
        // 不响应外部点击、没有遮罩可以救场，只能自己让开。
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            .widthIn(max = 460.dp),
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.Ink,
                fontWeight = FontWeight.Bold
            )
        },
        text = text,
        shape = RoundedCornerShape(24.dp),
        containerColor = AppColors.Card,
        titleContentColor = AppColors.Ink,
        textContentColor = AppColors.Body,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmText, color = AppColors.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it, color = AppColors.Muted) } }
        }
    )
}

/**
 * 年份 + 月份选择。
 *
 * 顶栏的日历按钮去掉后，入口变成列表里的年份胶囊，所以这里必须让人看清
 * 当前选的是哪一年；月份点一下就生效并关闭 —— 车机上少一步确认就少一次分心。
 */
@Composable
internal fun DateFilterDialog(
    initialYear: Int,
    onDismiss: () -> Unit,
    onPick: (year: Int, month: Int) -> Unit
) {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val years = (currentYear downTo 2003).toList()
    var year by rememberSaveable { mutableIntStateOf(initialYear) }
    val yearRow = rememberLazyListState()
    // 打开就得看见当前选中的年份（可能是用户翻到 2016 年时点进来的），
    // 已经在视野里就别动，免得点选时整排年份跟着弹
    LaunchedEffect(year) {
        val i = years.indexOf(year)
        if (i >= 0 && yearRow.layoutInfo.visibleItemsInfo.none { it.index == i }) {
            yearRow.scrollToItem(i)
        }
    }

    DialogHost(
        onDismiss = onDismiss,
        title = "跳转到日期",
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 手机竖屏高度有限，月份铺开有四行，超高时得能滚
                    .verticalScroll(rememberScrollState())
            ) {
                Text("年份", style = MaterialTheme.typography.titleSmall, color = AppColors.Muted)
                Spacer(Modifier.height(8.dp))
                // 年份只占一行：23 个年份要是全部平铺，弹窗比屏幕还高，
                // 月份会被挤出屏幕外根本点不到
                LazyRow(
                    state = yearRow,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(years, key = { it }) { y ->
                        DateChip(label = "$y", selected = y == year, onClick = { year = y })
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("月份", style = MaterialTheme.typography.titleSmall, color = AppColors.Muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DateChip(label = "全年", selected = false, onClick = { onPick(year, 0) })
                    (1..12).forEach { m ->
                        DateChip(label = "$m 月", selected = false, onClick = { onPick(year, m) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "选好后列表只保留那一段时间，标题右边的「看全部」随时退回完整列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        ),
        color = if (selected) Color.White else AppColors.Body,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.Red else AppColors.Canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
internal fun SettingsDialog(
    cacheBytes: Long?,
    downloads: List<DownloadManager.Entry>,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    onRemove: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onCleared: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    // 上限是启动时按可用空间算定的，取一次就够（拿不到时退回硬上限 2GB）
    val cacheCap = formatSize(MediaCache.capBytes)

    DialogHost(
        onDismiss = onDismiss,
        title = "设置",
        text = {
            Column(
                Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
            ) {
                // ---- 显示 ----
                Text(
                    "显示",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "隐藏顶部状态栏",
                    subtitle = "车机建议开启，顶栏不占画面、内容顶到上沿；" +
                        "手机和平板建议关闭，才能一直看到时间和通知图标。",
                    checked = immersive,
                    onChange = onImmersiveChange
                )

                Spacer(Modifier.height(22.dp))

                // ---- 缓存 ----
                Text(
                    "收听缓存",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "节目缓存：" + (cacheBytes?.let { formatSize(it) } ?: "统计中…") + " / $cacheCap",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "听过的片段自动存入本地，重听、续播、回退 30 秒不再消耗流量。" +
                        "上限按可用空间自动定（最多 2 GB），超过后自动淘汰最久未听的节目；" +
                        "清理会保留正在收听的一期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
                if (downloads.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "离线缓存 ${downloads.size} 期 · 听完自动删除",
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.Ink
                    )
                    downloads.forEach { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.episode.title.ifBlank { entry.episode.date },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    entry.episode.date,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.Muted
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when (entry.state) {
                                    DownloadManager.State.DONE -> formatSize(entry.bytes)
                                    DownloadManager.State.DOWNLOADING ->
                                        "${(entry.progress * 100).roundToInt()}%"
                                    DownloadManager.State.QUEUED -> "排队中"
                                    DownloadManager.State.FAILED -> "失败"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.state == DownloadManager.State.FAILED) {
                                    AppColors.RedDeep
                                } else {
                                    AppColors.Muted
                                }
                            )
                            // 失败不会自己重来：条目还占着列表，
                            // 重新长按「缓存」又会被当成已存在而跳过，只能在这里重试
                            if (entry.state == DownloadManager.State.FAILED) {
                                TextButton(onClick = { onRetry(entry.episode.id) }) {
                                    Text("重试", color = AppColors.RedDeep)
                                }
                            }
                            TextButton(onClick = { onRemove(entry.episode.id) }) {
                                Text("删除", color = AppColors.Muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 清理是本页唯一的破坏性操作，不能放在对话框右下角那个
                    // 「主按钮」位置：拨完开关顺手点右下角就会把缓存清空
                    TextButton(
                        onClick = {
                            clearing = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    MediaCache.clear(context)
                                }
                                clearing = false
                                onCleared()
                            }
                        },
                        enabled = !clearing && (cacheBytes ?: 0L) > 0
                    ) {
                        Text(
                            if (clearing) "清理中…" else "清理收听缓存",
                            color = if (clearing || (cacheBytes ?: 0L) <= 0L) AppColors.Faint else AppColors.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (clearing) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmText = "关闭",
        onConfirm = onDismiss,
        dismissText = null
    )
}

/** 设置页的开关行：左边标题 + 说明，右边开关 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
