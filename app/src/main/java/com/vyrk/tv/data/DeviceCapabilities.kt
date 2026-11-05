package com.vyrk.tv.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.vyrk.core.stream.DeviceCaps
import com.vyrk.core.stream.VideoCodec

/** What this TV can decode and display, detected from codecs and the physical display mode. */
class DeviceCapabilities(private val context: Context) {

    data class Report(
        val decoders: Map<VideoCodec, Int?>,
        val hevc: Boolean?,
        /** An HEVC decoder declares Main 10 (10-bit, HDR, Dolby Vision HEVC). Null when there's no list. */
        val hevc10: Boolean?,
        val av1: Boolean?,
        val dolbyVisionDecoder: Boolean,
        val displayWidth: Int,
        val displayHeight: Int,
        val hdr10: Boolean?,
        val hdr10Plus: Boolean?,
        val dolbyVision: Boolean?,
        val hlg: Boolean?,
        val audioEncodings: List<String>,
        val maxChannels: Int,
    )

    val report: Report by lazy { detect() }

    /**
     * Kinds of file ([com.vyrk.core.stream.StreamRanker.formatGroup]) the decoder failed on in this
     * session. Some decoders declare support they don't have (the emulator's HEVC decoder failed on
     * every 4K Dolby Vision file), so a failure is remembered and such files are skipped after it.
     */
    private val failedFormats = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun recordDecoderFailure(formatGroup: String) { failedFormats += formatGroup }

    fun caps(measuredBps: Double?): DeviceCaps = report.let { r ->
        DeviceCaps(
            maxHeight = r.decoders,
            hevc = r.hevc,
            hevc10 = r.hevc10,
            av1 = r.av1,
            dolbyVisionDecoder = r.dolbyVisionDecoder,
            failedFormats = failedFormats.toSet(),
            displayHdr10 = r.hdr10,
            displayDolbyVision = r.dolbyVision,
            display4k = if (r.displayHeight > 0) r.displayHeight >= 2000 || r.displayWidth >= 3800 else null,
            hasStreamingServer = false,
            measuredBps = measuredBps,
        )
    }

    private fun maxHeight(mime: String): Int? = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        list.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .mapNotNull { info ->
                runCatching {
                    val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(mime)
                    caps.videoCapabilities?.supportedHeights?.upper
                }.getOrNull()
            }.maxOrNull()
    }.getOrNull()

    /** True when an HEVC decoder declares a Main 10 profile; null when there are no HEVC decoders listed. */
    private fun hevcMain10(): Boolean? = runCatching {
        val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        if (decoders.isEmpty()) return@runCatching null
        val main10 = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
        decoders.any { info -> runCatching { info.getCapabilitiesForType(mime).profileLevels.any { it.profile in main10 } }.getOrDefault(false) }
    }.getOrNull()

    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    }.getOrDefault(false)

    @OptIn(UnstableApi::class)
    private fun detect(): Report {
        val h264 = maxHeight(MediaFormat.MIMETYPE_VIDEO_AVC)
        val hevcH = maxHeight(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val av1H = if (Build.VERSION.SDK_INT >= 29) maxHeight(MediaFormat.MIMETYPE_VIDEO_AV1) else maxHeight("video/av01")
        val dvDecoder = hasDecoder("video/dolby-vision")

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display: Display? = dm.getDisplay(Display.DEFAULT_DISPLAY)
        // Physical mode, not the app viewport: many 4K TVs render the UI at 1080p.
        val modes = display?.supportedModes.orEmpty()
        val w = modes.maxOfOrNull { it.physicalWidth } ?: 0
        val h = modes.maxOfOrNull { it.physicalHeight } ?: 0

        var hdr10: Boolean? = null
        var hdr10p: Boolean? = null
        var dv: Boolean? = null
        var hlg: Boolean? = null
        if (display != null && Build.VERSION.SDK_INT >= 24) {
            val types: IntArray = if (Build.VERSION.SDK_INT >= 34) {
                display.mode.supportedHdrTypes
            } else {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            }
            hdr10 = types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)
            hdr10p = if (Build.VERSION.SDK_INT >= 29) types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) else null
            dv = types.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
            hlg = types.contains(Display.HdrCapabilities.HDR_TYPE_HLG)
        }

        @Suppress("DEPRECATION")
        val audio = runCatching { AudioCapabilities.getCapabilities(context) }.getOrNull()
        val encodings = buildList {
            if (audio == null) return@buildList
            val names = mapOf(
                C.ENCODING_AC3 to "AC-3", C.ENCODING_E_AC3 to "E-AC-3", C.ENCODING_E_AC3_JOC to "Dolby Atmos (E-AC-3)",
                C.ENCODING_DTS to "DTS", C.ENCODING_DTS_HD to "DTS-HD", C.ENCODING_DOLBY_TRUEHD to "Dolby TrueHD",
            )
            names.forEach { (enc, n) -> if (audio.supportsEncoding(enc)) add(n) }
        }
        return Report(
            decoders = mapOf(VideoCodec.H264 to h264, VideoCodec.HEVC to hevcH, VideoCodec.AV1 to av1H),
            // A missing decoder list is unknown, not unsupported.
            hevc = hevcH?.let { true } ?: if (h264 != null) false else null,
            hevc10 = if (hevcH != null) hevcMain10() else if (h264 != null) false else null,
            av1 = av1H?.let { true } ?: if (h264 != null) false else null,
            dolbyVisionDecoder = dvDecoder,
            displayWidth = w,
            displayHeight = h,
            hdr10 = hdr10, hdr10Plus = hdr10p, dolbyVision = dv?.let { it && dvDecoder || it }, hlg = hlg,
            audioEncodings = encodings,
            maxChannels = audio?.maxChannelCount ?: 2,
        )
    }
}
