package cn.loveq.podcast.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件哈希。
 *
 * 这里写错的方式很安静：忘了补零（0x0a 打成 "a"）会让哈希长度不对，
 * 于是「每个源都报哈希不符」，看着像所有镜像都坏了。
 */
class Sha256Test {

    private fun tempFile(content: ByteArray): File =
        File.createTempFile("sha256", ".bin").apply {
            deleteOnExit()
            writeBytes(content)
        }

    @Test
    fun `abc 的 sha256 是已知值`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Of(tempFile("abc".toByteArray()))
        )
    }

    @Test
    fun `每个字节都固定两位十六进制`() {
        // 0x00 与 0x0f 这类值时最容易漏掉补零
        val expected = "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"
        assertEquals(expected, sha256Of(tempFile(byteArrayOf(0x00))))
        assertEquals(64, sha256Of(tempFile(byteArrayOf(0x0f))).length)
    }

    @Test
    fun `空文件的哈希也是 64 位`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Of(tempFile(ByteArray(0)))
        )
    }

    @Test
    fun `内容不同则哈希不同`() {
        val a = sha256Of(tempFile("a".toByteArray()))
        val b = sha256Of(tempFile("b".toByteArray()))
        assertTrue(a != b)
    }
}
