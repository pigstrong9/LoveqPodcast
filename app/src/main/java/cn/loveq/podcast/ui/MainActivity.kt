package cn.loveq.podcast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import cn.loveq.podcast.LoveqApp
import cn.loveq.podcast.ui.theme.LoveqTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val store by lazy { (application as LoveqApp).progressStore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        // 订阅而不是只在 onCreate 读一次：设置里拨动开关要立刻生效，不该要求重启应用
        lifecycleScope.launch { store.immersive.collect(::applyStatusBar) }
        setContent {
            LoveqTheme {
                AppScreen(onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded)
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
