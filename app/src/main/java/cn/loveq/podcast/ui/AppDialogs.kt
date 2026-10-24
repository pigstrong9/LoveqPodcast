@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package cn.loveq.podcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.loveq.podcast.data.DownloadManager
import cn.loveq.podcast.data.ThemeMode
import cn.loveq.podcast.data.UpdateStatus
import cn.loveq.podcast.playback.MediaCache
import cn.loveq.podcast.playback.SkipBlankDefaults
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
                    "选好后列表只保留那一段时间，标题右边的「回到顶部」随时退回完整列表。",
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
    cacheCapBytes: Long,
    downloads: List<DownloadManager.Entry>,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    autoResume: Boolean,
    onAutoResumeChange: (Boolean) -> Unit,
    /** 跳过空白的片头/片尾时长（2–5 分钟）；开关本身在播放设置面板里 */
    skipBlankMinutes: Int,
    onSkipBlankMinutes: (Int) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
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
    onClearEpisodeCache: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    // 上限由调用方传入：首次 get() 时按可用空间算定，拿不到时退回硬上限 2GB
    val cacheCap = formatSize(cacheCapBytes)

    // 「关于」不是另开一个窗口，而是把设置页整页换成声明页：两栏和单栏都从设置进来，
    // 这样不必给 Sidebar 和 PhoneHeader 各加一个入口，声明也只有一个来源
    if (aboutOpen) {
        AboutDialog(onBack = { aboutOpen = false })
        return
    }

    // 全屏设置页（小宇宙式）：不再是居中小弹窗，内容一屏摊开、自己滚动。
    // 作为普通覆盖层渲染在应用窗口里（不走 Dialog），天然铺满全屏，
    // 也绕开了 MIUI 对弹窗窗口「内容尺寸+居中」的摆放怪癖
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶栏：标题 + 关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.Ink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = AppColors.Red, fontWeight = FontWeight.Bold)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    // 车机三栏很宽：内容限宽居中，行不会被拉成通栏
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // ---- 显示 ----
                Text(
                    "显示",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                Text("外观", style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "跟随手机/车机的日夜模式，或固定不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeModeChip(
                        label = "跟随系统",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
                    )
                    ThemeModeChip(
                        label = "浅色",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { onThemeModeChange(ThemeMode.LIGHT) }
                    )
                    ThemeModeChip(
                        label = "深色",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { onThemeModeChange(ThemeMode.DARK) }
                    )
                }
                Spacer(Modifier.height(18.dp))
                SwitchRow(
                    title = "隐藏顶部状态栏",
                    subtitle = "车机建议开启，手机建议关闭。",
                    checked = immersive,
                    onChange = onImmersiveChange
                )

                Spacer(Modifier.height(22.dp))

                // ---- 播放 ----
                Text(
                    "播放",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "启动自动续播",
                    subtitle = "打开应用自动接着上次那期播放。",
                    checked = autoResume,
                    onChange = onAutoResumeChange
                )
                Spacer(Modifier.height(14.dp))
                Text("跳过空白时长", style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "每期自动跳过片头/片尾各 N 分钟，短的节目不跳；开关在播放设置面板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
                // 四个按钮等宽分布：包裹内容的写法在窄弹窗里放不下时，
                // 最后一个会被压缩换行（实测「5 分钟」变形成两行）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (SkipBlankDefaults.MIN_MINUTES..SkipBlankDefaults.MAX_MINUTES).forEach { minutes ->
                        ThemeModeChip(
                            label = "$minutes 分钟",
                            selected = skipBlankMinutes == minutes,
                            onClick = { onSkipBlankMinutes(minutes) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

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
                    "重听、续播不耗流量；超限自动清理最久未听的。",
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
                Spacer(Modifier.height(6.dp))
                Text(
                    "浏览过的历史月份本地留存，重选不联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )
                TextButton(onClick = onClearEpisodeCache) {
                    Text(
                        "清理节目列表缓存",
                        color = AppColors.Red,
                        fontWeight = FontWeight.Bold
                    )
                }

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

                Spacer(Modifier.height(22.dp))

                // ---- 关于 ----
                Text(
                    "关于",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Muted
                )
                Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 关于：非官方声明 + 版权归属 + 维权联系入口。
 *
 * 这段文字是有实际用处的（法律上的"去混淆"——讲清楚和官方没有任何隶属关系），
 * 所以必须让用户在 App 里就看得见。只写在 README 里，对装了 APK 的人等于没声明。
 */
@Composable
private fun AboutDialog(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    DialogHost(
        onDismiss = onBack,
        title = "关于",
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (version.isBlank()) "LoveqPodcast" else "LoveqPodcast  v$version",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "《一些事一些情》第三方播客客户端",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted
                )

                Spacer(Modifier.height(18.dp))

                // 可选中有两个原因：里面的仓库地址要能长按复制（车机上没法手打），
                // 也免得日后有人截图时说"看不清写的是什么"
                SelectionContainer {
                    Column {
                        Text(
                            "本应用是非官方第三方客户端，与 LoveQ 官网及《一些事一些情》节目方" +
                                "无任何隶属关系。节目内容版权归原作者所有。本应用不存储、不分发" +
                                "任何音频，仅代为访问官网公开链接。非商业用途。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Body
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "如权利人认为不妥，可在项目仓库提 Issue 联系" +
                                "（github.com/pigstrong9/LoveqPodcast/issues），" +
                                "我将立即停止分发并下架。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Body
                        )
                    }
                }
            }
        },
        confirmText = "返回",
        onConfirm = onBack,
        dismissText = null
    )
}

/** 外观三档选择：胶囊样式与日期选择一致；modifier 传入 weight(1f) 可做等宽分布 */
@Composable
private fun ThemeModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else AppColors.Body,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.Red else AppColors.Canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
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
