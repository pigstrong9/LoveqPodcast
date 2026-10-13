package cn.loveq.podcast.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 增益 + 软限幅。
 * 直接把样本乘以 2 会让超过满刻度的部分硬削波（破音），所以 |x| 超过拐点后走 tanh
 * 平滑压回 1.0：大音量段落动态被压扁而不是削顶，听感更响且没有毛刺。
 */
class VolumeBoostProcessor : BaseAudioProcessor() {

    private var floatInput = false

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(
                TAG, "拒绝格式 encoding=$encoding" +
                    " ${inputAudioFormat.sampleRate}Hz x${inputAudioFormat.channelCount}"
            )
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        floatInput = encoding == C.ENCODING_PCM_FLOAT
        Log.d(
            TAG, "生效 encoding=$encoding" +
                " ${inputAudioFormat.sampleRate}Hz x${inputAudioFormat.channelCount}"
        )
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            encoding
        )
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.limit() - inputBuffer.position()
        if (size <= 0) return

        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val boost = VolumeBoost.gain
        inputBuffer.order(ByteOrder.nativeOrder())

        if (boost <= 1.0001f) {
            output.put(inputBuffer)
        } else if (floatInput) {
            while (inputBuffer.remaining() >= FLOAT_BYTES) {
                output.putFloat(limit(inputBuffer.float * boost))
            }
        } else {
            while (inputBuffer.remaining() >= SHORT_BYTES) {
                val sample = inputBuffer.short / 32768f * boost
                val scaled = (limit(sample) * 32767f).toInt().coerceIn(-32768, 32767)
                output.putShort(scaled.toShort())
            }
        }
        output.flip()
    }

    private fun limit(sample: Float): Float {
        val magnitude = abs(sample)
        if (magnitude <= KNEE) return sample
        val over = (magnitude - KNEE) / (1f - KNEE)
        val shaped = KNEE + (1f - KNEE) * tanh(over)
        return if (sample < 0f) -shaped else shaped
    }

    private companion object {
        const val TAG = "VolumeBoost"
        const val KNEE = 0.85f
        const val SHORT_BYTES = 2
        const val FLOAT_BYTES = 4
    }
}
