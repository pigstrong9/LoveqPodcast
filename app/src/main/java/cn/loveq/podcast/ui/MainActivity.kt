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
import cn.loveq.podcast.ui.theme.LoveqTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        hideSystemBars()
        setContent {
            LoveqTheme {
                AppScreen(onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded)
            }
        }
    }

    /**
     * 沉浸式：默认隐藏系统状态栏（车机顶栏/手机通知栏），内容全屏顶到上沿。
     * 从屏幕边缘滑动可临时呼出（transient，不占布局）。
     * 系统在弹窗、切后台等场景会重置沉浸标记，焦点恢复时重新应用。
     */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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
