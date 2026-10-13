package cn.loveq.podcast.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onYear: () -> Unit,
    onCache: () -> Unit,
    cacheLabel: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
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

        Row(
            Modifier
                .padding(top = 12.dp)
                .heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SideTool(Icons.Filled.Search, "搜索", onSearch, Modifier.weight(1f))
            SideTool(Icons.Filled.CalendarMonth, "年份", onYear, Modifier.weight(1f))
            SideTool(Icons.Filled.Storage, "缓存", onCache, Modifier.weight(1f))
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
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = content
        )
    }
}

@Composable
private fun SideTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Body, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppColors.Body)
    }
}

/** 窄屏（分屏半幅 / 竖屏）顶行：logo + 名称 + 工具图标 */
@Composable
fun PhoneHeader(
    onSearch: () -> Unit,
    onYear: () -> Unit,
    onCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppLogo(36.dp, 10.dp)
        Spacer(Modifier.width(10.dp))
        BrandTitle("FM97.4 珠江经济台", Modifier.weight(1f))
        IconTool(Icons.Filled.Search, "搜索", onSearch)
        IconTool(Icons.Filled.CalendarMonth, "按年份跳转", onYear)
        IconTool(Icons.Filled.Storage, "缓存管理", onCache)
    }
}

/** 窄屏分类胶囊横排 */
@Composable
fun CategoryPills(
    selected: Category,
    onSelect: (Category) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Category.ALL.forEach { category ->
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
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun IconTool(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
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
