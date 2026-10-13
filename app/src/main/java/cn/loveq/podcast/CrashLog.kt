package cn.loveq.podcast

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘。车机/平板没有 logcat 时，异常会写到
 * filesDir/crash.log，可用 adb 或应用内取回。
 */
object CrashLog {

    private const val TAG = "LoveqCrash"
    private const val FILE_NAME = "crash.log"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun text(context: Context): String =
        runCatching { file(context).takeIf { it.exists() }?.readText().orEmpty() }
            .getOrDefault("")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun append(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            appendLine("=== $stamp  thread=${thread.name} ===")
            appendLine(trace)
            appendLine()
        }
        Log.e(TAG, entry)
        runCatching {
            file(context).appendText(entry)
        }
    }
}
