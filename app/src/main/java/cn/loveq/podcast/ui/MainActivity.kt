package cn.loveq.podcast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cn.loveq.podcast.LoveqApp
import cn.loveq.podcast.data.ThemeMode
import cn.loveq.podcast.ui.theme.LoveqTheme
import cn.loveq.podcast.ui.theme.loveqPlayerColor
import cn.loveq.podcast.ui.theme.loveqWindowBackground
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val store by lazy { (application as LoveqApp).progressStore }

    private val carNightMode by lazy { (application as LoveqApp).carNightMode }

    private val updates by lazy { (application as LoveqApp).updates }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()
        enableEdgeToEdge(
            // auto：浅色模式用深色图标，深色模式自动换成浅色图标
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        // 主题偏好从第一帧就生效：系统深色但用户选了浅色时，别先闪一下深色窗口
        applyWindowChrome(effectiveDark(store.themeMode.value))
        // 订阅而不是只在 onCreate 读一次：设置里拨动开关要立刻生效，不该要求重启应用
        lifecycleScope.launch { store.immersive.collect(::applyStatusBar) }
        // 启动静默检查：失败不出声、不写持久状态（见 UpdateCenter.check 的 manual 参数）。
        // 有新版时设置入口和「关于」行会出现红点，不打断用户收听
        lifecycleScope.launch { updates.check(manual = false) }
        setContent {
            val themeMode by store.themeMode.collectAsStateWithLifecycle()
            // 比亚迪 DiLink 的日夜切换不一定反映到 uiMode，优先读车机设置
            val carDark by carNightMode.dark.collectAsStateWithLifecycle()
            // 系统深浅色照常订阅：车机没有专用键时用它兜底
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> carDark ?: systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LoveqTheme(darkTheme = dark) {
                LaunchedEffect(dark) { applyWindowChrome(dark) }
                AppScreen(
                    onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded,
                    multiWindow = isMultiWindowNow(),
                    onPlayerOverlayChange = ::onPlayerOverlayChange
                )
            }
        }
    }

    /**
     * 按设置显示或隐藏顶部状态栏。
     *
     * 隐藏时是沉浸式：内容顶到上沿，从屏幕边缘滑动可临时呼出（transient，不占布局）。
     * 显示时交回系统默认行为，布局由各色块自己避让（见 AppScreen 的 topInset）。
     *
     * 那两个 contrastEnforced 必须关掉：状态栏透明时，系统在 targetSdk 29+ 会在
     * 状态栏底下自画一层半透明遮罩来「保证图标可读」。这层遮罩我们看不见也拦不住，
     * 结果就是顶部像贴了一条不属于应用的白条 —— 也就是「顶栏分层」的成因。
     * 应用自己把画布画到屏幕最上沿，图标对比度本来就够。
     */
    private fun applyStatusBar(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            if (hidden) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (hidden) controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())
    }

    /** 主题偏好 + 系统当前值换算成实际深浅色（窗口底色与系统栏图标都用它） */
    private fun effectiveDark(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.SYSTEM ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** 播放层是否盖满窗口（由 AppScreen 通知）；分屏时窗口底色跟着换成播放器深色 */
    private var playerOverlayOpen = false

    /** 最近一次应用的深浅色：播放层开关时不必再去问主题，直接复用 */
    private var chromeDark = false

    /** 播放层开关时只补一次窗口底色，不重建任何 UI */
    private fun onPlayerOverlayChange(open: Boolean) {
        if (playerOverlayOpen == open) return
        playerOverlayOpen = open
        applyWindowChrome(chromeDark)
    }

    /**
     * 窗口面积明显小于屏幕就当作多窗口：比亚迪 DiLink 这类 OEM 的分屏
     * 有可能只把窗口缩小、不上报标准多窗口标志。
     * 全屏时两者相等（留 5% 余量给挖孔/内缩），不会误判。
     */
    private fun windowLooksShrunk(): Boolean {
        val maxArea = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            (b.right - b.left).toLong() * (b.bottom - b.top)
        } else {
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
            p.x.toLong() * p.y
        }
        if (maxArea <= 0L) return false
        val dm = resources.displayMetrics
        val area = dm.widthPixels.toLong() * dm.heightPixels
        return area in 1 until maxArea * 95 / 100
    }

    /** 是否处于多窗口：标准标志优先，OEM 缩窗兜底 */
    private fun isMultiWindowNow(): Boolean = isInMultiWindowMode || windowLooksShrunk()

    /** 系统栏图标和窗口底色一起切，避免设置里换外观时露出旧底色 */
    private fun applyWindowChrome(dark: Boolean) {
        chromeDark = dark
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // 分屏时应用内容被排在状态栏下面，空出来的那一条露出的是窗口底色。
        // 播放层打开时把它也换成播放器深色，卡片和顶部就连成一整块，
        // 否则浅色主题下会看到「系统状态栏一条 + 播放器顶栏一条」两层。
        // 全屏时内容铺满窗口，这块底色看不见，改了也没有副作用。
        // 这里刻意不动状态栏图标明暗：分屏时状态栏是整屏共用的，车机上又没有图标，
        // 强行改成浅色会让另一栏（比如左边的地图）上的图标看不清。
        val overlay = playerOverlayOpen && isMultiWindowNow()
        val background = if (overlay) loveqPlayerColor(dark) else loveqWindowBackground(dark)
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        // 车机上分屏不一定按标准多窗口实现：这行日志用来确认标志位和实际底色，
        // 排查「顶栏还是两层」时先看这里。
        Log.i(
            "WindowChrome",
            "overlay=$overlay multiFlag=$isInMultiWindowMode shrunk=${windowLooksShrunk()} " +
                "bg=#%08X".format(background.toArgb())
        )
    }

    /** 弹窗、切后台等场景会重置沉浸标记，焦点回来时按当前设置重新应用 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyStatusBar(store.immersive.value)
    }

    /**
     * 高刷屏上跑满刷新率。
     *
     * 小米 14 这类机型的屏幕本身就在 120Hz（`mActiveRenderFrameRate=120`），
     * 但应用不声明帧率偏好时，系统可能按「未知帧率」处理并退回 60Hz。
     * preferredRefreshRate = 0 表示明确「跟随屏幕默认模式」，
     * 配合 Compose 的无障碍/动画需求，系统就会按 120Hz 调度。
     */
    private fun applyHighRefreshRate() {
        runCatching {
            window.attributes = window.attributes.apply { preferredRefreshRate = 0f }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
