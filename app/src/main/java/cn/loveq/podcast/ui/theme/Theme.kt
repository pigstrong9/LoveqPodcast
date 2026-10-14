package cn.loveq.podcast.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设计稿色板：暖灰画布 + 品牌红 + 深色播放面板，深浅系统主题下都保持这套观感 */
object AppColors {
    val Canvas = Color(0xFFF6F3F7)
    val Sidebar = Color(0xFFEAE4EC)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF241E28)
    val Body = Color(0xFF4A4350)
    val Muted = Color(0xFF6A6174)
    val Faint = Color(0xFF9A95A0)

    val Red = Color(0xFFE50012)
    val RedDeep = Color(0xFFB70D14)
    val RedMist = Color(0xFFF3D9D7)
    val RedWash = Color(0xFFFDECEA)
    val RedEdge = Color(0xFFE5A6A1)
    val Rail = Color(0xFFEAE3EC)

    val Player = Color(0xFF241E28)
    val PlayerRaised = Color(0xFF322C38)
    val PlayerControl = Color(0xFF3A3440)
    val PlayerTrack = Color(0xFF46404C)
    val PlayerTitle = Color(0xFFFFFFFF)
    val PlayerSub = Color(0xFFB7AEC0)
    val PlayerMeta = Color(0xFF9D94A8)
    val PlayerOption = Color(0xFFCFC7D8)
}

private val Colors = lightColorScheme(
    primary = AppColors.Red,
    onPrimary = Color.White,
    primaryContainer = AppColors.RedMist,
    onPrimaryContainer = AppColors.RedDeep,
    secondary = AppColors.Ink,
    onSecondary = Color.White,
    background = AppColors.Canvas,
    onBackground = AppColors.Ink,
    surface = AppColors.Card,
    onSurface = AppColors.Ink,
    surfaceVariant = AppColors.Sidebar,
    onSurfaceVariant = AppColors.Muted,
    surfaceContainer = AppColors.Card,
    surfaceContainerHigh = AppColors.Sidebar,
    surfaceContainerHighest = AppColors.Canvas,
    outline = AppColors.Muted,
    outlineVariant = AppColors.Rail,
    error = AppColors.RedDeep,
    scrim = AppColors.Ink
)

// 车机场景：字号整体放大一档，保证驾驶时一瞥可读
private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LoveqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
