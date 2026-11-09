package androidx.media3.common.audio
import java.nio.ByteBuffer
interface AudioProcessor {
    class AudioFormat(val sampleRate: Int, val channelCount: Int, val encoding: Int) {
        companion object { val NOT_SET = AudioFormat(-1, -1, -1) }
    }
}
abstract class BaseAudioProcessor : AudioProcessor {
    var auditOutput: ByteBuffer = ByteBuffer.allocate(0)
    open fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    abstract fun queueInput(inputBuffer: ByteBuffer)
    fun replaceOutputBuffer(size: Int): ByteBuffer {
        auditOutput = ByteBuffer.allocate(size)
        return auditOutput
    }
}
