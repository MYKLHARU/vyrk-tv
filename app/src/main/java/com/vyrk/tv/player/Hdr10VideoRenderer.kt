package com.vyrk.tv.player

import android.media.MediaCrypto
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/**
 * "Play Dolby Vision as HDR10": Dolby Vision files with an HDR10 (or HLG) picture underneath —
 * profiles 4, 7 and 8, which covers Blu-ray remuxes — are decoded by the ordinary HEVC decoder,
 * which plays that base picture and ignores the Dolby Vision layer. On some boxes (SHIELD among
 * them) the Dolby Vision path is what hangs; the HEVC path is the one every app uses for HDR10.
 * Profile 5 has no such base picture, so it's left alone.
 */
@OptIn(UnstableApi::class)
class Hdr10VideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, asHdr10(format))

    override fun getDecoderInfos(mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean): List<MediaCodecInfo> =
        super.getDecoderInfos(mediaCodecSelector, asHdr10(format), requiresSecureDecoder)

    override fun getMediaCodecConfiguration(codecInfo: MediaCodecInfo, format: Format, crypto: MediaCrypto?, codecOperatingRate: Float): MediaCodecAdapter.Configuration =
        super.getMediaCodecConfiguration(codecInfo, asHdr10(format), crypto, codecOperatingRate)

    companion object {
        /** The DV profile number from a codecs string like "dvhe.07.06" or "dvh1.08.06"; null when unknown. */
        fun dvProfile(codecs: String?): Int? = codecs?.split(',')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("dvhe.") || it.startsWith("dvh1.") }
            ?.split('.')?.getOrNull(1)?.toIntOrNull()

        fun asHdr10(format: Format): Format {
            if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
            val profile = dvProfile(format.codecs) ?: return format
            if (profile !in setOf(4, 7, 8)) return format
            return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
        }
    }
}
