@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.loveq.podcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.loveq.podcast.data.Episode
import cn.loveq.podcast.data.Progress
import cn.loveq.podcast.ui.theme.AppColors

/**
 * 搜索 + 收听历史面板。空关键字显示历史，输入即搜全库（2300+ 期）。
 * 横竖屏通用：居中圆角弹层，宽度自适应（最大 640dp）。
 */
@Composable
fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    history: List<Progress>,
    results: List<Episode>,
    resultTotal: Int,
    progressMap: Map<Long, Progress>,
    indexing: Boolean,
    onPlay: (Long) -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = AppColors.Canvas,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 700.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                SearchField(query = query, onQueryChange = onQueryChange, onClose = onClose)
                if (indexing) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = AppColors.Red
                    )
                }
                if (query.isBlank()) {
                    HistoryContent(history = history, onPlay = onPlay)
                } else {
                    ResultsContent(
                        results = results,
                        resultTotal = resultTotal,
                        progressMap = progressMap,
                        indexing = indexing,
                        query = query,
                        onPlay = onPlay
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = { Text("搜索节目标题 / 日期") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
        )
        TextButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp)) {
            Text("关闭")
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun HistoryContent(history: List<Progress>, onPlay: (Long) -> Unit) {
    var filter by remember { mutableIntStateOf(0) }
    val filtered = remember(history, filter) {
        when (filter) {
            1 -> history.filter { !it.done }
            2 -> history.filter { it.done }
            else -> history
        }
    }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = AppColors.RedMist,
        selectedLabelColor = AppColors.RedDeep,
        containerColor = AppColors.Card,
        labelColor = AppColors.Body
    )

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("收听历史", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "共 ${history.size} 期",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == 0,
                onClick = { filter = 0 },
                label = { Text("全部") },
                colors = chipColors,
                shape = RoundedCornerShape(12.dp)
            )
            FilterChip(
                selected = filter == 1,
                onClick = { filter = 1 },
                label = { Text("在听") },
                colors = chipColors,
                shape = RoundedCornerShape(12.dp)
            )
            FilterChip(
                selected = filter == 2,
                onClick = { filter = 2 },
                label = { Text("听完") },
                colors = chipColors,
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (filtered.isEmpty()) {
            EmptyHint("暂无收听记录\n播放过的节目会出现在这里")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                itemsIndexed(filtered, key = { _, p -> p.id }) { _, p ->
                    PanelRow(
                        title = p.title.ifBlank { p.date },
                        meta = when {
                            p.done -> "已听完 · ${p.date}"
                            p.durationMs > 0 -> "${p.date} · 已听 ${formatMillis(p.positionMs)} / ${formatMillis(p.durationMs)}"
                            else -> "${p.date} · 已听 ${formatMillis(p.positionMs)}"
                        },
                        done = p.done,
                        fraction = if (p.durationMs > 0) {
                            (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
                        } else 0f,
                        onClick = { onPlay(p.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsContent(
    results: List<Episode>,
    resultTotal: Int,
    progressMap: Map<Long, Progress>,
    indexing: Boolean,
    query: String,
    onPlay: (Long) -> Unit
) {
    if (results.isEmpty()) {
        EmptyHint(
            if (indexing) "正在建立节目索引，稍后再试…"
            else "没有找到「${query.trim()}」相关节目"
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            if (resultTotal > results.size) "找到 $resultTotal 个结果，显示前 ${results.size} 个"
            else "找到 $resultTotal 个结果",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            itemsIndexed(results, key = { _, e -> e.id }) { _, e ->
                val p = progressMap[e.id]
                PanelRow(
                    title = e.title.ifBlank { e.date },
                    meta = when {
                        p?.done == true -> "已听完 · ${e.date}"
                        p != null && p.durationMs > 0 -> "${e.date} · 已听 ${formatMillis(p.positionMs)} / ${formatMillis(p.durationMs)}"
                        else -> e.date
                    },
                    done = p?.done == true,
                    fraction = p?.takeIf { it.durationMs > 0 }
                        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) } ?: 0f,
                    onClick = { onPlay(e.id) }
                )
            }
        }
    }
}

@Composable
private fun PanelRow(
    title: String,
    meta: String,
    done: Boolean,
    fraction: Float,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (done) 0.55f else 1f)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已听完",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(0.6f)
                )
            }
        }
        if (fraction > 0.02f && fraction < 0.98f) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
