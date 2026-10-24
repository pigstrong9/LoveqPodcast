package cn.loveq.podcast.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 倍速刻度尺的取值：吸附到 0.1 且不产生浮点尘埃
 * （0.5f + 8 * 0.1f 会算出 1.3000001，标签会跟着写成 1.3000001×）。
 */
class SpeedRulerTest {

    @Test
    fun `两端取值`() {
        assertEquals(0.5f, speedFromFraction(0f))
        assertEquals(3.0f, speedFromFraction(1f))
    }

    @Test
    fun `越界钳到两端`() {
        assertEquals(0.5f, speedFromFraction(-0.5f))
        assertEquals(3.0f, speedFromFraction(2f))
    }

    @Test
    fun `半格向上吸附`() {
        // 0.5 * 25 = 12.5 → 吸到第 13 格 = 1.8×
        assertEquals(1.8f, speedFromFraction(0.5f))
    }

    @Test
    fun `所有档位都落在精确的十分位`() {
        for (i in 0..250) {
            val speed = speedFromFraction(i / 250f)
            val tenths = speed * 10f
            assertEquals(
                "speed=$speed",
                0f,
                abs(tenths - tenths.toInt()),
                1e-4f
            )
        }
    }

    @Test
    fun `倍速转回滑动比例`() {
        assertEquals(0f, speedToFraction(0.5f))
        assertEquals(0.2f, speedToFraction(1.0f))
        assertEquals(1f, speedToFraction(3.0f))
        // 历史版本遗留的 0.75× 档位也要能定位（落在两格之间即可，不越界）
        assertEquals(0.1f, speedToFraction(0.75f))
    }

    @Test
    fun `刻度值来回转换稳定`() {
        var step = 0
        while (step <= 25) {
            val speed = speedFromFraction(step / 25f)
            assertEquals(speed, speedFromFraction(speedToFraction(speed)))
            step++
        }
    }
}
