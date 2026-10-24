package cn.loveq.podcast.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 人声增强：高通 + 人声频段提亮 + 软限幅。
 *
 * 这不是「人声分离」：设备端实时跑 MDX/Demucs 这类模型不现实（延迟、算力、耗电），
 * 播客本身以人声为主，所以用两级 biquad 把人声频段抬出来、把低频轰鸣切掉，
 * 听感上更近、更清楚；片头片尾的歌曲也会一起变亮，开关默认关。
 *
 * 链路：高通 120Hz（切车噪/隆隆声）→ 2.6kHz +4.5dB（齿音/清晰度）→ 软限幅（防提亮后削波）。
 */
class VocalBoostProcessor : BaseAudioProcessor() {

    private var floatInput = false
    private var channels = 0
    private var sampleRate = 0

    // 每个声道两组 biquad 的状态（x1/x2/y1/y2）
    private var highPass: Biquad? = null
    private var presence: Biquad? = null

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(TAG, "拒绝格式 encoding=$encoding")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        floatInput = encoding == C.ENCODING_PCM_FLOAT
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        highPass = Biquad.highPass(sampleRate, 120.0, 0.707, channels)
        presence = Biquad.peaking(sampleRate, 2600.0, 1.0, 4.5, channels)
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun onFlush() {
        highPass?.reset()
        presence?.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.limit() - inputBuffer.position()
        if (size <= 0) return

        // 关掉时也要「激活」并原样搬运：让 isActive 恒为 true，
        // 避免每次开关都触发整条处理链重新配置（会有可听见的断音）
        if (!VocalBoost.enabled) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }

        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        val hp = highPass
        val pk = presence

        if (floatInput) {
            var ch = 0
            while (inputBuffer.remaining() >= FLOAT_BYTES) {
                val x = inputBuffer.float
                output.putFloat(processSample(hp, pk, ch, x))
                ch = (ch + 1) % channels
            }
        } else {
            var ch = 0
            while (inputBuffer.remaining() >= SHORT_BYTES) {
                val x = inputBuffer.short / 32768f
                val y = processSample(hp, pk, ch, x)
                output.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                ch = (ch + 1) % channels
            }
        }
        output.flip()
    }

    private fun processSample(
        hp: Biquad?,
        pk: Biquad?,
        channel: Int,
        input: Float
    ): Float {
        var sample = input
        if (hp != null) sample = hp.process(channel, sample)
        if (pk != null) sample = pk.process(channel, sample)
        // 软限幅：提亮后峰值可能超过满刻度，硬削波会破音
        val magnitude = abs(sample)
        if (magnitude <= KNEE) return sample
        val over = (magnitude - KNEE) / (1f - KNEE)
        val shaped = KNEE + (1f - KNEE) * tanh(over)
        return if (sample < 0f) -shaped else shaped
    }

    /** 双二阶 IIR，系数按 RBJ Audio EQ Cookbook 计算，逐声道保存状态 */
    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
        channels: Int
    ) {
        private val x1 = FloatArray(channels)
        private val x2 = FloatArray(channels)
        private val y1 = FloatArray(channels)
        private val y2 = FloatArray(channels)

        fun process(channel: Int, x: Float): Float {
            val index = channel.coerceIn(0, x1.size - 1)
            val y = b0 * x + b1 * x1[index] + b2 * x2[index] -
                a1 * y1[index] - a2 * y2[index]
            x2[index] = x1[index]
            x1[index] = x
            y2[index] = y1[index]
            y1[index] = y
            return y
        }

        fun reset() {
            x1.fill(0f)
            x2.fill(0f)
            y1.fill(0f)
            y2.fill(0f)
        }

        companion object {
            fun highPass(sampleRate: Int, freq: Double, q: Double, channels: Int): Biquad {
                val w0 = 2.0 * PI * freq / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW = cos(w0)
                val a0 = 1.0 + alpha
                val b0 = ((1.0 + cosW) / 2.0) / a0
                val b1 = (-(1.0 + cosW)) / a0
                val b2 = ((1.0 + cosW) / 2.0) / a0
                val a1 = (-2.0 * cosW) / a0
                val a2 = (1.0 - alpha) / a0
                return Biquad(
                    b0.toFloat(), b1.toFloat(), b2.toFloat(),
                    a1.toFloat(), a2.toFloat(), channels
                )
            }

            fun peaking(
                sampleRate: Int,
                freq: Double,
                q: Double,
                gainDb: Double,
                channels: Int
            ): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freq / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW = cos(w0)
                val a0 = 1.0 + alpha / a
                val b0 = (1.0 + alpha * a) / a0
                val b1 = (-2.0 * cosW) / a0
                val b2 = (1.0 - alpha * a) / a0
                val a1 = (-2.0 * cosW) / a0
                val a2 = (1.0 - alpha / a) / a0
                return Biquad(
                    b0.toFloat(), b1.toFloat(), b2.toFloat(),
                    a1.toFloat(), a2.toFloat(), channels
                )
            }
        }
    }

    private companion object {
        const val TAG = "VocalBoost"
        const val KNEE = 0.9f
        const val SHORT_BYTES = 2
        const val FLOAT_BYTES = 4
    }
}

/** 人声增强开关：音频线程只读 volatile，UI 侧走 StateFlow（与 [VolumeBoost] 同一套路） */
object VocalBoost {

    @Volatile
    var enabled: Boolean = false
        private set

    fun apply(on: Boolean) {
        enabled = on
    }
}
