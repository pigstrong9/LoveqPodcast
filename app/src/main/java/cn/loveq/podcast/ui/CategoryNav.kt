package cn.loveq.podcast.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.loveq.podcast.R
import cn.loveq.podcast.data.Category
import cn.loveq.podcast.ui.theme.AppColors

private val NavShape = RoundedCornerShape(14.dp)

/** 分类图标，与导航项一一对应 */
internal fun categoryIcon(category: Category): ImageVector = when (category.id) {
    Category.CANTONESE_ID -> Icons.AutoMirrored.Filled.Chat
    5 -> Icons.Filled.DirectionsCar
    4 -> Icons.Filled.Coffee
    35 -> Icons.AutoMirrored.Filled.MenuBook
    3 -> Icons.Filled.Star
    38 -> Icons.AutoMirrored.Filled.QueueMusic
    2 -> Icons.Filled.Translate
    else -> Icons.AutoMirrored.Filled.FormatListBulleted
}

@Composable
private fun BrandTitle(subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "一些事一些情",
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.Ink
        )
        Text(
            subtitle,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.Muted
        )
    }
}

/** 横屏左侧边栏：品牌 + 分类导航 + 工具，顶栏彻底取消 */
@Composable
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
    Column(
        modifier
            .fillMaxHeight()
            // 底色先画、再避让状态栏：色块顶到屏幕最上沿，状态栏底下不会露出一条画布色
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .windowInsetsPadding(topInset)
            .padding(horizontal = 14.dp, vertical = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 18.dp)
        ) {
            AppLogo(38.dp, 11.dp)
            Spacer(Modifier.width(10.dp))
            BrandTitle("FM97.4 · 2003 至今", Modifier.width(168.dp))
        }

        Text(
            "节目分类",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.Muted,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Category.ALL.forEach { category ->
                NavItem(
                    icon = categoryIcon(category),
                    label = category.name,
                    selected = category.id == selected.id,
                    onClick = { onSelect(category) }
                )
            }
        }

        Column(
            Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SideTool(Icons.Filled.Search, "搜索", onSearch)
            // 年份入口挪进了列表（点年份数字），侧栏不再常驻一个「年份」按钮
            // 页面里除了缓存还有显示设置，入口叫「设置」；
            // 占用容量下面那行 cacheLabel 常驻，缓存信息并没有因此变难找
            SideTool(Icons.Filled.Settings, "设置", onCache, dot = showUpdateDot)
        }
        Text(
            cacheLabel ?: "缓存统计中…",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) AppColors.Red else Color.Transparent,
        animationSpec = tween(220),
        label = "navBg"
    )
    val content by animateColorAsState(
        targetValue = if (selected) Color.White else AppColors.Body,
        animationSpec = tween(220),
        label = "navContent"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(NavShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            // 长分类名（Hugo's Story Time）在 196–252dp 边栏里一行放不下：
            // 两行换行比硬裁剪/词中省略更可读，纵向空间分类列足够
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 21.sp
            ),
            color = content
        )
    }
}

/** 边栏工具项：通栏胶囊，一行一个；高度对齐分类项，车机上好按 */
@Composable
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

/** 窄屏（分屏半幅 / 竖屏）顶行：logo + 名称 + 工具图标 */
@Composable
fun PhoneHeader(
    onSearch: () -> Unit,
    onCache: () -> Unit,
    showUpdateDot: Boolean,
    topInset: WindowInsets,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            // 状态栏区域留给品牌行自己避让：背景是画布色，画到状态栏底下才不断层
            .windowInsetsPadding(topInset)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppLogo(36.dp, 10.dp)
        Spacer(Modifier.width(10.dp))
        BrandTitle("FM97.4 珠江经济台", Modifier.weight(1f))
        IconTool(Icons.Filled.Search, "搜索", onSearch)
        // 年份入口挪进了列表本体（点年份数字），顶栏不再常驻一个日历按钮
        IconTool(Icons.Filled.Settings, "设置", onCache, dot = showUpdateDot)
    }
}

/**
 * 窄屏分类胶囊横排。
 * 用 LazyRow 而不是 Row：左右滑动列表换分类时，目标分类的胶囊可能在屏幕外，
 * 需要按 index 把它滚进视野，Row 拿不到 item 位置。
 */
@Composable
fun CategoryPills(
    selected: Category,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberLazyListState()
    LaunchedEffect(selected.id) {
        val index = Category.ALL.indexOfFirst { it.id == selected.id }
        if (index < 0) return@LaunchedEffect
        // 已经在视野里就别动，否则每次点选都会把整排胶囊弹一下
        val visible = scroll.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!visible) scroll.animateScrollToItem(index)
    }
    LazyRow(
        state = scroll,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    ) {
        items(Category.ALL, key = { it.id }) { category ->
            val current = category.id == selected.id
            val background by animateColorAsState(
                targetValue = if (current) AppColors.Red else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(220),
                label = "pillBg"
            )
            val content by animateColorAsState(
                targetValue = if (current) Color.White else AppColors.Body,
                animationSpec = tween(220),
                label = "pillContent"
            )
            Text(
                category.name,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                ),
                color = content,
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(background)
                    .clickable { onSelect(category) }
                    // 胶囊视觉 44dp：车机触控热区下限
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
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

@Composable
internal fun AppLogo(size: Dp, radius: Dp) {
    Image(
        painter = painterResource(R.drawable.loveq_logo),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
    )
}
