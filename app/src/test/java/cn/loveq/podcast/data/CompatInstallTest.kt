package cn.loveq.podcast.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机兼容安装的版本门控。
 *
 * DiLink 4 是 Android 10（API 29）：标准「安装未知应用」设置页可能不存在，
 * 跳过去会闪退，这类系统改走「直接装 → 失败放下载目录让用户点」。
 * 其余版本（含 API 30+ 与其他低版本）原生流程正常，必须保持原方案不变。
 */
class CompatInstallTest {

    @Test
    fun `仅 API 29 走兼容路径`() {
        assertTrue(useCompatInstall(29)) // Android 10 = DiLink 4
    }

    @Test
    fun `API 30 及以上走原生方案`() {
        assertFalse(useCompatInstall(30)) // Android 11
        assertFalse(useCompatInstall(34)) // Android 14
        assertFalse(useCompatInstall(35))
    }

    @Test
    fun `低版本也走原生方案`() {
        // API 26-28 的原生「安装未知应用」页正常；API<26 无此限制、直接装。
        // 且 MediaStore.Downloads 是 API 29 才引入，低版本不能进兼容路径。
        assertFalse(useCompatInstall(21))
        assertFalse(useCompatInstall(26))
        assertFalse(useCompatInstall(28))
    }

    @Test
    fun `门控精确等于 29 而非区间`() {
        assertFalse(useCompatInstall(28))
        assertTrue(useCompatInstall(29))
        assertFalse(useCompatInstall(30))
    }
}
