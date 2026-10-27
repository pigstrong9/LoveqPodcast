package cn.loveq.podcast.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计稿色板：暖灰画布 + 品牌红 + 深色播放面板。
 *
 * 浅色/深色各一份，由 [LoveqTheme] 按系统设置提供；界面一律通过 [AppColors] 取色，
 * 不要在组件里写死颜色，否则切到深色会留下一块亮色。
 */
@Immutable
data class LoveqPalette(
    val canvas: Color,
    val sidebar: Color,
    val card: Color,
    val ink: Color,
    val body: Color,
    val muted: Color,
    val faint: Color,
    val red: Color,
    val redDeep: Color,
    val redMist: Color,
    val redWash: Color,
    val redEdge: Color,
    val rail: Color,
    val player: Color,
    val playerRaised: Color,
    val playerControl: Color,
    val playerTrack: Color,
    val playerTitle: Color,
    val playerSub: Color,
    val playerMeta: Color,
    val playerOption: Color
)

private val LightPalette = LoveqPalette(
    canvas = Color(0xFFF6F3F7),
    sidebar = Color(0xFFEAE4EC),
    card = Color(0xFFFFFFFF),
    ink = Color(0xFF241E28),
    body = Color(0xFF4A4350),
    muted = Color(0xFF6A6174),
    faint = Color(0xFF9A95A0),
    red = Color(0xFFE50012),
    redDeep = Color(0xFFB70D14),
    redMist = Color(0xFFF3D9D7),
    redWash = Color(0xFFFDECEA),
    redEdge = Color(0xFFE5A6A1),
    rail = Color(0xFFEAE3EC),
    player = Color(0xFF241E28),
    playerRaised = Color(0xFF322C38),
    playerControl = Color(0xFF3A3440),
    playerTrack = Color(0xFF46404C),
    playerTitle = Color(0xFFFFFFFF),
    playerSub = Color(0xFFB7AEC0),
    playerMeta = Color(0xFF9D94A8),
    playerOption = Color(0xFFCFC7D8)
)

/**
 * 深色不是简单反相：画布压到接近黑，卡片比画布亮一档保持层次；
 * 品牌红略微提亮以保证深色底上的可读性，红底上的白字对比度仍然够用。
 */
private val DarkPalette = LoveqPalette(
    canvas = Color(0xFF131114),
    sidebar = Color(0xFF1B181D),
    card = Color(0xFF211D23),
    ink = Color(0xFFF1ECF2),
    body = Color(0xFFD6CCDA),
    muted = Color(0xFFA79CAD),
    faint = Color(0xFF7C7282),
    red = Color(0xFFEE1C2A),
    redDeep = Color(0xFFFF9098),
    redMist = Color(0xFF3A191D),
    redWash = Color(0xFF2B1417),
    redEdge = Color(0xFF8A3038),
    rail = Color(0xFF38323B),
    player = Color(0xFF241E28),
    playerRaised = Color(0xFF322C38),
    playerControl = Color(0xFF3A3440),
    playerTrack = Color(0xFF46404C),
    playerTitle = Color(0xFFFFFFFF),
    playerSub = Color(0xFFB7AEC0),
    playerMeta = Color(0xFF9D94A8),
    playerOption = Color(0xFFCFC7D8)
)

private val LocalLoveqPalette = staticCompositionLocalOf { LightPalette }

/** 界面统一取色入口；读取当前浅色/深色色板，切换主题时随重组自动更新 */
object AppColors {
    val Canvas: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.canvas

    val Sidebar: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.sidebar

    val Card: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.card

    val Ink: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.ink

    val Body: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.body

    val Muted: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.muted

    val Faint: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.faint

    val Red: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.red

    val RedDeep: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.redDeep

    val RedMist: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.redMist

    val RedWash: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.redWash

    val RedEdge: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.redEdge

    val Rail: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.rail

    val Player: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.player

    val PlayerRaised: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerRaised

    val PlayerControl: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerControl

    val PlayerTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerTrack

    val PlayerTitle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerTitle

    val PlayerSub: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerSub

    val PlayerMeta: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerMeta

    val PlayerOption: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalLoveqPalette.current.playerOption
}

private val LightColors = lightColorScheme(
    primary = LightPalette.red,
    onPrimary = Color.White,
    primaryContainer = LightPalette.redMist,
    onPrimaryContainer = LightPalette.redDeep,
    secondary = LightPalette.ink,
    onSecondary = Color.White,
    background = LightPalette.canvas,
    onBackground = LightPalette.ink,
    surface = LightPalette.card,
    onSurface = LightPalette.ink,
    surfaceVariant = LightPalette.sidebar,
    onSurfaceVariant = LightPalette.muted,
    surfaceContainer = LightPalette.card,
    surfaceContainerHigh = LightPalette.sidebar,
    surfaceContainerHighest = LightPalette.canvas,
    outline = LightPalette.muted,
    outlineVariant = LightPalette.rail,
    error = LightPalette.redDeep,
    scrim = LightPalette.ink
)

private val DarkColors = darkColorScheme(
    primary = DarkPalette.red,
    onPrimary = Color.White,
    primaryContainer = DarkPalette.redMist,
    onPrimaryContainer = DarkPalette.redDeep,
    secondary = DarkPalette.ink,
    onSecondary = DarkPalette.canvas,
    background = DarkPalette.canvas,
    onBackground = DarkPalette.ink,
    surface = DarkPalette.card,
    onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.sidebar,
    onSurfaceVariant = DarkPalette.muted,
    surfaceContainer = DarkPalette.card,
    surfaceContainerHigh = DarkPalette.sidebar,
    surfaceContainerHighest = DarkPalette.canvas,
    outline = DarkPalette.muted,
    outlineVariant = DarkPalette.rail,
    error = DarkPalette.redDeep,
    scrim = Color.Black
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
fun LoveqTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalLoveqPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

/** 窗口底色：冷启动和 Activity 重建时，Compose 首帧之前也不能闪成反色 */
fun loveqWindowBackground(dark: Boolean): Color =
    if (dark) DarkPalette.canvas else LightPalette.canvas

/**
 * 播放层底色：分屏时窗口顶部会露出状态栏那一条窗口底色，
 * 播放层打开时把它也换成这个色，才能和播放卡片连成一整块。
 */
fun loveqPlayerColor(dark: Boolean): Color =
    if (dark) DarkPalette.player else LightPalette.player
