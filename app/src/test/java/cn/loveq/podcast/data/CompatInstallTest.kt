package cn.loveq.podcast.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机兼容安装的版本门控。
 *
 * DiLink 4 是 Android 10（API 29）：标准「安装未知应用」设置页可能不存在，
 * 跳过去会闪退，这类系统改走「直接装 → 失败放下载目录让用户点」。
 * API 30+ 原生流程正常，必须保持原方案不变。
 */
class CompatInstallTest {

    @Test
    fun `API 29 及以下走兼容路径`() {
        assertTrue(useCompatInstall(21)) // Android 5
        assertTrue(useCompatInstall(26)) // Android 8
        assertTrue(useCompatInstall(29)) // Android 10 = DiLink 4
    }

    @Test
    fun `API 30 及以上走原生方案`() {
        assertFalse(useCompatInstall(30)) // Android 11
        assertFalse(useCompatInstall(34)) // Android 14
        assertFalse(useCompatInstall(35))
    }

    @Test
    fun `边界精确落在 29 和 30 之间`() {
        assertTrue(useCompatInstall(29))
        assertFalse(useCompatInstall(30))
    }
}
