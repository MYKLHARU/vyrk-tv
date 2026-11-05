package com.vyrk.core.stream

import com.vyrk.core.addon.Stream
import com.vyrk.core.lang.Languages

enum class Preset { BALANCED, BEST_PICTURE, FAST_START, ADDON_ORDER }
enum class FormatPref { ANY, REMUX, ENCODE, WEB }
enum class HdrPref { ANY, DOLBY_VISION, HDR10, SDR }

/** What this TV can do. Null means unknown, and unknown is never treated as unsupported. */
data class DeviceCaps(
    val maxHeight: Map<VideoCodec, Int?> = emptyMap(),
    val hevc: Boolean? = null,
    /** An HEVC decoder declares the Main 10 profile (needed for 10-bit, HDR and Dolby Vision HEVC). */
    val hevc10: Boolean? = null,
    val av1: Boolean? = null,
    /** A Dolby Vision decoder exists (needed for Dolby Vision-only files, which have no HDR10 base). */
    val dolbyVisionDecoder: Boolean? = null,
    /** [StreamRanker.formatGroup]s whose decoding failed on this TV in this session. */
    val failedFormats: Set<String> = emptySet(),
    val displayHdr10: Boolean? = null,
    val displayDolbyVision: Boolean? = null,
    val display4k: Boolean? = null,
    val hasStreamingServer: Boolean = false,
    /** Smoothed measured connection speed in bits per second. */
    val measuredBps: Double? = null,
)

data class RankPrefs(
    val preset: Preset = Preset.BALANCED,
    val maxResolution: Int? = null,
    val maxSizeBytes: Long? = null,
    val format: FormatPref = FormatPref.ANY,
    val hdr: HdrPref = HdrPref.ANY,
    val audioLanguages: List<String> = emptyList(),
    /** Identity of the file the user picked last time for this title. */
    val rememberedKey: String? = null,
    /** Release/binge group of the previous episode. */
    val lastBingeGroup: String? = null,
    val lastReleaseGroup: String? = null,
    val runtimeMinutes: Int? = null,
)

data class Candidate(
    val stream: Stream,
    val addonId: String,
    val addonName: String,
    val addonOrder: Int,
    val index: Int,
) {
    /** Stable identity for remembering a choice: filename, else URL without query, else hash. */
    val key: String
        get() = stream.behaviorHints.filename
            ?: stream.url?.substringBefore('?')
            ?: stream.infoHash?.let { "$it:${stream.fileIdx ?: 0}" }
            ?: "$addonId#$index"
}

enum class Exclusion(val code: String) {
    TORRENT_NO_SERVER("torrent_needs_server"),
    EXTERNAL("external_link"),
    YOUTUBE("youtube_link"),
    UNKNOWN_SOURCE("unknown_source"),
    OVER_MAX_RESOLUTION("over_max_resolution"),
    OVER_MAX_SIZE("over_max_size"),
    UNCACHED("not_cached"),
    CODEC_UNSUPPORTED("codec_unsupported"),
    OVER_DECODER_LIMIT("over_decoder_limit"),
    TEN_BIT_UNSUPPORTED("ten_bit_unsupported"),
    DOLBY_VISION_UNSUPPORTED("dolby_vision_unsupported"),
    FAILED_ON_THIS_TV("failed_on_this_tv"),
}

data class Reason(val code: String, val points: Int)

data class Ranked(
    val candidate: Candidate,
    val info: StreamInfo,
    val score: Int,
    val reasons: List<Reason>,
    val exclusion: Exclusion?,
) {
    val playable: Boolean get() = exclusion == null
}

/**
 * Deterministic stream ranker. Same inputs always give the same order; every point added or
 * removed is recorded as a [Reason]. Ties break on add-on order, then the add-on's own order.
 */
object StreamRanker {

    fun rank(candidates: List<Candidate>, caps: DeviceCaps, prefs: RankPrefs): List<Ranked> {
        val ranked = candidates.map { score(it, caps, prefs) }
        return ranked.sortedWith(
            compareBy<Ranked> { if (it.playable) 0 else 1 }
                .thenByDescending { if (prefs.preset == Preset.ADDON_ORDER) 0 else it.score }
                .thenBy { it.candidate.addonOrder }
                .thenBy { it.candidate.index },
        )
    }

    fun best(candidates: List<Candidate>, caps: DeviceCaps, prefs: RankPrefs): Ranked? =
        rank(candidates, caps, prefs).firstOrNull { it.playable }

    fun exclusion(info: StreamInfo, caps: DeviceCaps, prefs: RankPrefs): Exclusion? {
        when (info.delivery) {
            Delivery.TORRENT -> if (!caps.hasStreamingServer) return Exclusion.TORRENT_NO_SERVER
            Delivery.EXTERNAL -> return Exclusion.EXTERNAL
            Delivery.YOUTUBE -> return Exclusion.YOUTUBE
            Delivery.UNKNOWN -> return Exclusion.UNKNOWN_SOURCE
            Delivery.DIRECT -> {}
        }
        val res = info.resolution
        if (res != null && prefs.maxResolution != null && res > prefs.maxResolution) return Exclusion.OVER_MAX_RESOLUTION
        if (info.sizeBytes != null && prefs.maxSizeBytes != null && info.sizeBytes > prefs.maxSizeBytes) return Exclusion.OVER_MAX_SIZE
        if (info.cached == false) return Exclusion.UNCACHED
        if (info.codec == VideoCodec.HEVC && caps.hevc == false) return Exclusion.CODEC_UNSUPPORTED
        if (info.codec == VideoCodec.AV1 && caps.av1 == false) return Exclusion.CODEC_UNSUPPORTED
        val codecKey = if (info.codec == VideoCodec.UNKNOWN) VideoCodec.H264 else info.codec
        val limit = caps.maxHeight[codecKey]
        if (res != null && limit != null && res > limit + 8) return Exclusion.OVER_DECODER_LIMIT
        // These apply in every preset, Add-on order included: the TV can't decode such a file at all.
        // HDR and Dolby Vision files without a codec tag are almost always HEVC.
        val hevcLike = info.codec == VideoCodec.HEVC || (info.codec == VideoCodec.UNKNOWN && info.tenBit)
        if (hevcLike && info.tenBit && caps.hevc10 == false) return Exclusion.TEN_BIT_UNSUPPORTED
        if (info.dvOnly && caps.dolbyVisionDecoder == false) return Exclusion.DOLBY_VISION_UNSUPPORTED
        if (formatGroup(info) in caps.failedFormats) return Exclusion.FAILED_ON_THIS_TV
        return null
    }

    /**
     * What decoding a stream needs, coarsely: codec, 8 or 10 bit, 4K or not. When the decoder fails
     * on one stream, others in the same group are unlikely to play either.
     */
    fun formatGroup(info: StreamInfo): String = listOf(
        (if (info.codec == VideoCodec.UNKNOWN && info.tenBit) VideoCodec.HEVC else info.codec).name,
        if (info.tenBit) "10bit" else "8bit",
        if ((info.resolution ?: 0) >= 2160) "4k" else "hd",
    ).joinToString("/")

    fun score(c: Candidate, caps: DeviceCaps, prefs: RankPrefs): Ranked {
        val info = StreamParser.parse(c.stream)
        val reasons = mutableListOf<Reason>()
        fun add(code: String, pts: Int) { if (pts != 0) reasons += Reason(code, pts) }
        val excl = exclusion(info, caps, prefs)
        val res = info.resolution ?: 0
        val hdrCapable = caps.displayHdr10 == true || caps.displayDolbyVision == true
        val gb = info.sizeBytes?.let { it / 1e9 }

        when (prefs.preset) {
            Preset.BALANCED -> {
                val allow4k = caps.display4k == true && hdrCapable && info.hdr.isNotEmpty()
                add("resolution", when {
                    res >= 2160 -> if (allow4k) 120 else 40
                    res >= 1080 -> 100
                    res >= 720 -> 60
                    res >= 480 -> 25
                    res > 0 -> 10
                    else -> 30
                })
                add("release", when (info.releaseType) {
                    ReleaseType.WEBDL, ReleaseType.BLURAY -> 30
                    ReleaseType.REMUX -> 15
                    ReleaseType.WEBRIP -> 15
                    ReleaseType.HDTV -> 5
                    else -> 0
                })
                if (gb != null && gb > 15) add("large_file", -(((gb - 15) * 3).toInt().coerceAtMost(60)) - 10)
                if (info.cached == true) add("ready_on_service", 60)
            }
            Preset.BEST_PICTURE -> {
                add("resolution", when {
                    res >= 2160 -> 160
                    res >= 1440 -> 120
                    res >= 1080 -> 100
                    res >= 720 -> 50
                    res > 0 -> 15
                    else -> 30
                })
                add("release", when (info.releaseType) {
                    ReleaseType.REMUX -> 50
                    ReleaseType.BLURAY -> 40
                    ReleaseType.WEBDL -> 25
                    ReleaseType.WEBRIP -> 10
                    else -> 0
                })
                if (gb != null) add("file_size", (gb * 1.5).toInt().coerceAtMost(40))
                add("audio_quality", (info.audio.maxOfOrNull { it.rank } ?: 0) * 3)
                if (info.cached == true) add("ready_on_service", 20)
            }
            Preset.FAST_START -> {
                add("resolution", when {
                    res >= 2160 -> 10
                    res >= 1080 -> 90
                    res >= 720 -> 85
                    res >= 480 -> 40
                    res > 0 -> 15
                    else -> 30
                })
                if (info.cached == true) add("ready_on_service", 90)
                if (gb != null) add("small_file", when {
                    gb < 2 -> 40; gb < 4 -> 25; gb < 8 -> 10; gb < 15 -> 0; else -> -30
                })
                if (info.releaseType == ReleaseType.REMUX) add("avoid_remux", -50)
                if (info.releaseType == ReleaseType.WEBDL) add("release", 15)
            }
            Preset.ADDON_ORDER -> {}
        }

        if (prefs.preset != Preset.ADDON_ORDER) {
            // Display-aware HDR bonuses and penalties.
            if (Hdr.DOLBY_VISION in info.hdr && caps.displayDolbyVision == true) add("dolby_vision", 25)
            if ((Hdr.HDR10 in info.hdr || Hdr.HDR10_PLUS in info.hdr) && caps.displayHdr10 == true) add("hdr", 15)
            if (info.dvOnly && caps.displayDolbyVision != true) add("dv_only_on_non_dv", -70)
            if (info.hdr.isNotEmpty() && !info.dvOnly && caps.displayHdr10 == false && caps.displayDolbyVision != true) add("hdr_on_sdr", -25)

            when (prefs.format) {
                FormatPref.REMUX -> if (info.releaseType == ReleaseType.REMUX) add("format_pref", 40)
                FormatPref.ENCODE -> if (info.releaseType in setOf(ReleaseType.BLURAY, ReleaseType.WEBRIP)) add("format_pref", 40)
                FormatPref.WEB -> if (info.releaseType in setOf(ReleaseType.WEBDL, ReleaseType.WEBRIP)) add("format_pref", 40)
                FormatPref.ANY -> {}
            }
            when (prefs.hdr) {
                HdrPref.DOLBY_VISION -> if (Hdr.DOLBY_VISION in info.hdr) add("hdr_pref", 40)
                HdrPref.HDR10 -> if (Hdr.HDR10 in info.hdr || Hdr.HDR10_PLUS in info.hdr) add("hdr_pref", 40)
                HdrPref.SDR -> if (info.hdr.isEmpty()) add("hdr_pref", 40)
                HdrPref.ANY -> {}
            }

            // Audio language: preferred first, then multi-audio, other known languages last.
            if (prefs.audioLanguages.isNotEmpty()) {
                val pref = prefs.audioLanguages.map { Languages.normalise(it) }
                val idx = info.languages.map { pref.indexOf(it) }.filter { it >= 0 }.minOrNull()
                when {
                    idx != null -> add("preferred_language", 50 - idx * 10)
                    info.multiAudio -> add("multi_audio", 20)
                    info.languages.isNotEmpty() -> add("other_language", -40)
                }
            }

            if (info.codec == VideoCodec.HEVC && caps.hevc == null) add("hevc_unconfirmed", -5)
            if (info.codec == VideoCodec.AV1 && caps.av1 == null) add("av1_unconfirmed", -8)
            if (info.is3D) add("3d", -15)
            if (info.releaseType == ReleaseType.CAM) add("cam", -400)

            // Connection fit: estimated bitrate (size / runtime) against measured speed.
            val bps = caps.measuredBps
            val runtime = prefs.runtimeMinutes
            if (bps != null && bps > 0 && info.sizeBytes != null && runtime != null && runtime > 0) {
                val streamBps = info.sizeBytes * 8.0 / (runtime * 60.0)
                val factor = if (prefs.preset == Preset.BEST_PICTURE) 0.6 else 1.0
                when {
                    streamBps > 1.5 * bps -> add("too_fast_for_connection", (-90 * factor).toInt())
                    streamBps > 0.85 * bps -> add("near_connection_limit", (-35 * factor).toInt())
                }
            }
        }

        // Memory always applies, even to add-on order.
        if (prefs.rememberedKey != null && prefs.rememberedKey == c.key) add("picked_last_time", 1000)
        val sameGroup = (prefs.lastBingeGroup != null && prefs.lastBingeGroup == info.bingeGroup) ||
            (prefs.lastReleaseGroup != null && info.releaseGroup != null && prefs.lastReleaseGroup.equals(info.releaseGroup, true))
        if (sameGroup) add("same_release_group", 500)

        return Ranked(c, info, reasons.sumOf { it.points }, reasons, excl)
    }
}
