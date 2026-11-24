package com.vyrk.tv.player

import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import com.vyrk.core.dovi.DoviSamples
import com.vyrk.core.dovi.Dv7
import com.vyrk.core.dovi.Dv7Mode
import com.vyrk.core.dovi.Dv7Stats
import com.vyrk.tv.data.Diagnostics
import java.io.EOFException

/** The Dolby Vision profiles this device's decoders declare (4, 5, 7, 8, 9). */
object DvDecoders {
    // MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvhe* values, by profile number.
    private val PROFILES = mapOf(0x10 to 4, 0x20 to 5, 0x40 to 6, 0x80 to 7, 0x100 to 8, 0x200 to 9)

    val profiles: Set<Int> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.VIDEO_DOLBY_VISION, true) } }
                .flatMap { info -> info.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION).profileLevels.mapNotNull { PROFILES[it.profile] } }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** What to do with a profile 7 file on this device (see [Dv7.mode]). */
    fun mode7(displayDolbyVision: Boolean?, asHdr10: Boolean): Dv7Mode =
        Dv7.mode(p7Decoder = 7 in profiles, p8Decoder = 8 in profiles, displayDv = displayDolbyVision, asHdr10 = asHdr10)
}

/**
 * Wraps every extractor so that a Dolby Vision profile 7 video track (Blu-ray remuxes) plays on
 * this device: converted to profile 8.1, or reduced to its HDR10 base layer ([Dv7Mode]). Media3
 * has no decoder fallback for profile 7, so without this such files fail on most TVs.
 */
@OptIn(UnstableApi::class)
class Dv7ExtractorsFactory(base: ExtractorsFactory, private val mode: Dv7Mode) : ForwardingExtractorsFactory(base) {
    override fun createExtractors(): Array<Extractor> = super.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        super.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(e: Extractor): Extractor = if (mode == Dv7Mode.PASS) e else object : ForwardingExtractor(e) {
        override fun init(output: ExtractorOutput) = super.init(object : ForwardingExtractorOutput(output) {
            override fun track(id: Int, type: Int): TrackOutput =
                super.track(id, type).let { if (type == C.TRACK_TYPE_VIDEO) Dv7TrackOutput(it, mode) else it }
        })
    }
}

/**
 * Buffers each sample of a profile 7 track and hands the rewritten access unit on. Other tracks
 * and formats pass straight through. Encrypted samples and samples written ahead of their
 * metadata (offset > 0; not the case for MKV and MP4) are left as they are.
 */
@OptIn(UnstableApi::class)
private class Dv7TrackOutput(out: TrackOutput, private val wanted: Dv7Mode) : ForwardingTrackOutput(out) {
    private var mode = Dv7Mode.PASS
    private var buf = ByteArray(256 * 1024)
    private var len = 0
    private val stats = Dv7Stats()
    private var loggedFailure = false

    override fun format(format: Format) {
        val p7 = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && Dv7.profile(format.codecs) == 7
        mode = if (p7) wanted else Dv7Mode.PASS
        if (p7) Diagnostics.log("player", "dolby vision profile 7: ${mode.name.lowercase()}")
        super.format(when (mode) {
            Dv7Mode.PASS -> format
            Dv7Mode.TO_81 -> format.buildUpon().setCodecs(Dv7.codecsAs81(format.codecs.orEmpty())).build()
            Dv7Mode.HDR10 -> format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
        })
    }

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (mode == Dv7Mode.PASS) return super.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensure(length)
        val n = input.read(buf, len, length)
        if (n == C.RESULT_END_OF_INPUT) { if (allowEndOfInput) return n; throw EOFException() }
        len += n
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int) = sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (mode == Dv7Mode.PASS) return super.sampleData(data, length, sampleDataPart)
        ensure(length)
        data.readBytes(buf, len, length)
        len += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (mode == Dv7Mode.PASS) return super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        val end = len - offset
        val start = end - size
        val out = if (cryptoData == null && offset == 0 && start >= 0) DoviSamples.rewrite(buf, start, end, mode, stats) else null
        if (out == null) {
            pass(0, len)
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        } else {
            pass(0, start)
            super.sampleData(ParsableByteArray(out), out.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            super.sampleMetadata(timeUs, flags, out.size, 0, null)
        }
        len = 0
        if (stats.failed > 0 && !loggedFailure) { loggedFailure = true; Diagnostics.log("player", "dolby vision 7: an RPU didn't convert ($stats)") }
    }

    private fun pass(from: Int, to: Int) {
        if (to <= from) return
        val data = ParsableByteArray(buf, to)
        data.setPosition(from)
        super.sampleData(data, to - from, TrackOutput.SAMPLE_DATA_PART_MAIN)
    }
}
