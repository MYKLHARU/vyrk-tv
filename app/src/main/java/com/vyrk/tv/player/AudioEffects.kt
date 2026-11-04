package com.vyrk.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * PCM-only dialogue and TV-speaker processing. Never active on passthrough (encoded audio
 * doesn't reach audio processors). Clear Dialogue lowers the stereo side signal so centred
 * speech stands out; TV-speaker mode adds gain with a soft limiter to tame dynamic range.
 */
@OptIn(UnstableApi::class)
class DialogueProcessor : BaseAudioProcessor() {
    @Volatile var clearDialogue = false
    @Volatile var tvSpeaker = false
    private var channels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val inS = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val outS = out.order(ByteOrder.nativeOrder()).asShortBuffer()
        val n = inS.remaining()
        val gain = if (tvSpeaker) 1.6f else 1f
        var i = 0
        while (i < n) {
            if (channels == 2 && clearDialogue && i + 1 < n) {
                val l = inS.get(i).toFloat(); val r = inS.get(i + 1).toFloat()
                val mid = (l + r) * 0.5f; val side = (l - r) * 0.5f * 0.45f
                outS.put(limit((mid * 1.15f + side) * gain)); outS.put(limit((mid * 1.15f - side) * gain))
                i += 2
            } else {
                outS.put(limit(inS.get(i) * gain)); i++
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.position(0); out.limit(remaining)
    }

    private fun limit(x: Float): Short {
        val t = 26000f
        val y = if (abs(x) <= t) x else (if (x > 0) 1 else -1) * (t + (abs(x) - t) / (1f + (abs(x) - t) / (32767f - t)))
        return y.coerceIn(-32768f, 32767f).toInt().toShort()
    }
}
