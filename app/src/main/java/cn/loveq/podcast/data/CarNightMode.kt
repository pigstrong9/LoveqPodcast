package cn.loveq.podcast.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 比亚迪 DiLink 的日夜模式。
 *
 * DiLink 的日/夜切换不一定同步到 Android 的 uiMode，但 Secure 设置里有
 * `ui_night_mode_override`（比亚迪自己的覆盖值）和 `ui_night_mode`
 * （Android UiModeManager 的标准键）。直接观察这两个键：
 * 2 = 夜间，1 = 日间；0 或其它值视为未知，交给 isSystemInDarkTheme() 兜底。
 *
 * 观察而非轮询：车机切换主题时系统会通知 Settings 的 ContentObserver，
 * 应用不用一直查设置，也不会因为 Activity 声明了 uiMode 配置变更而错过。
 */
class CarNightMode(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    private val _dark = MutableStateFlow(read())
    val dark: StateFlow<Boolean?> = _dark

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val value = read()
            // 车机端排查用：adb logcat -s CarNightMode
            Log.d(
                TAG,
                "settings changed -> $value " +
                    "(override=${readInt(KEYS[0])}, standard=${readInt(KEYS[1])})"
            )
            _dark.value = value
        }
    }

    init {
        runCatching {
            KEYS.forEach { key ->
                resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
            }
        }
        Log.d(
            TAG,
            "initial override=${readInt(KEYS[0])} " +
                "standard=${readInt(KEYS[1])} -> ${_dark.value}"
        )
    }

    private fun read(): Boolean? {
        for (key in KEYS) {
            when (readInt(key)) {
                2 -> return true
                1 -> return false
            }
        }
        return null
    }

    private fun readInt(key: String): Int? =
        runCatching { Settings.Secure.getInt(resolver, key) }.getOrNull()
            ?: runCatching { Settings.System.getInt(resolver, key) }.getOrNull()

    private companion object {
        const val TAG = "CarNightMode"

        /** 覆盖值优先；它不存在或为 auto 时再看 Android 标准键 */
        val KEYS = listOf("ui_night_mode_override", "ui_night_mode")
    }
}
