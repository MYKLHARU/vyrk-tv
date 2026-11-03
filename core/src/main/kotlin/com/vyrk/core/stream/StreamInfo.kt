package com.vyrk.core.stream

import com.vyrk.core.addon.Stream
import com.vyrk.core.lang.Languages

enum class Delivery { DIRECT, TORRENT, EXTERNAL, YOUTUBE, UNKNOWN }
enum class ReleaseType(val label: String, val rank: Int) {
    REMUX("Remux", 7), BLURAY("BluRay", 6), WEBDL("WEB-DL", 5), WEBRIP("WEBRip", 4),
    HDTV("HDTV", 3), DVD("DVD", 2), CAM("CAM", 0), UNKNOWN("", 1)
}
enum class VideoCodec(val label: String) { H264("H.264"), HEVC("HEVC"), AV1("AV1"), UNKNOWN("") }
enum class Hdr(val label: String) { HDR10("HDR10"), HDR10_PLUS("HDR10+"), DOLBY_VISION("Dolby Vision"), HLG("HLG") }
enum class AudioFormat(val label: String, val rank: Int) {
    ATMOS("Atmos", 9), TRUEHD("TrueHD", 8), DTS_X("DTS:X", 8), DTS_HD_MA("DTS-HD MA", 7), DTS("DTS", 5),
    EAC3("E-AC-3", 5), AC3("AC-3", 4), FLAC("FLAC", 6), OPUS("Opus", 3), AAC("AAC", 2), MP3("MP3", 1)
}

/** Everything the ranker knows about one stream, parsed from its text and behaviour hints. */
data class StreamInfo(
    val delivery: Delivery,
    val resolution: Int?,
    val releaseType: ReleaseType,
    val codec: VideoCodec,
    val hdr: Set<Hdr>,
    val dvOnly: Boolean,
    val audio: Set<AudioFormat>,
    val channels: String?,
    val languages: List<String>,
    val multiAudio: Boolean,
    val sizeBytes: Long?,
    val is3D: Boolean,
    val cached: Boolean?,
    val bingeGroup: String?,
    val releaseGroup: String?,
    val filename: String?,
) {
    val resolutionLabel: String
        get() = when {
            resolution == null -> ""
            resolution >= 2160 -> "4K"
            else -> "${resolution}p"
        }
}

object StreamParser {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val res2160 = rx("""\b(2160p|4k|uhd)\b""")
    private val res1440 = rx("""\b1440p\b""")
    private val res1080 = rx("""\b(1080p|1080i|fhd|full\s?hd)\b""")
    private val res720 = rx("""\b(720p|hd\s?ready)\b""")
    private val res576 = rx("""\b576p\b""")
    private val res480 = rx("""\b(480p|sd)\b""")
    private val res360 = rx("""\b(360p|240p)\b""")

    private val remux = rx("""\bremux\b""")
    private val bluray = rx("""\b(blu-?ray|bdrip|brrip|bd25|bd50)\b""")
    private val webdl = rx("""\b(web-?dl|web\b(?!-?rip))""")
    private val webrip = rx("""\bweb-?rip\b""")
    private val hdtv = rx("""\b(hdtv|pdtv)\b""")
    private val dvd = rx("""\b(dvd-?rip|dvd|dvdscr)\b""")
    private val cam = rx("""\b(cam-?rip|cam|hdcam|telesync|hdts|hd-?ts|telecine)\b""")

    private val hevc = rx("""\b(hevc|x\.?265|h\.?265)\b""")
    private val av1 = rx("""\bav1\b""")
    private val h264 = rx("""\b(avc|x\.?264|h\.?264)\b""")

    private val dv = rx("""\b(dolby\s?vision|dovi|dv)\b""")
    private val hdr10p = rx("""\bhdr10(\+|plus)""")
    private val hdr10 = rx("""\bhdr(10)?\b""")
    private val hlg = rx("""\bhlg\b""")

    private val atmos = rx("""\batmos\b""")
    private val truehd = rx("""\btrue-?hd\b""")
    private val dtsx = rx("""\bdts[-:]?x\b""")
    private val dtshd = rx("""\bdts[-\s]?hd([-\s]?ma)?\b""")
    private val dts = rx("""\bdts\b""")
    private val eac3 = rx("""(\be-?ac-?3|\bddp|\bdd\+|\beac3)""")
    private val ac3 = rx("""(\bac-?3\b|\bdolby\s?digital\b|\bdd(?=[257][. ][01]|\b))""")
    private val flac = rx("""\bflac\b""")
    private val opus = rx("""\bopus\b""")
    private val aac = rx("""\baac\b""")
    private val mp3 = rx("""\bmp3\b""")
    private val channelsRx = rx("""(?<!\d)(?<!\d\.)([257])[. ]([01])(?!\d)(?!\s*(?:gb|mb|gib|mib|tb))""")

    private val sizeRx = rx("""(\d+(?:[.,]\d+)?)\s*(tb|gb|mb|gib|mib)\b""")
    private val threeD = rx("""\b(3d|sbs|half-?ou|half-?sbs)\b""")
    private val multi = rx("""\b(multi|dual[-\s]?audio)\b""")
    private val cachedRx = rx("""(\[(rd|ad|pm|dl|tb|ed|oc|pp)\+\]|⚡|instant|cached)""")
    private val uncachedRx = rx("""(\[(rd|ad|pm|dl|tb|ed|oc|pp)\s?download\]|uncached|not\s+cached|⏳)""")
    private val groupRx = Regex("""-([A-Za-z0-9]{2,12})(?:\.(?:mkv|mp4|avi|m4v|ts)\b|\s*$)""")

    fun delivery(s: Stream): Delivery = when {
        !s.url.isNullOrBlank() -> {
            val u = s.url.lowercase()
            if (u.startsWith("magnet:")) Delivery.TORRENT
            else if (u.startsWith("http://") || u.startsWith("https://")) Delivery.DIRECT
            else Delivery.UNKNOWN
        }
        !s.infoHash.isNullOrBlank() -> Delivery.TORRENT
        !s.ytId.isNullOrBlank() -> Delivery.YOUTUBE
        !s.externalUrl.isNullOrBlank() || !s.playerFrameUrl.isNullOrBlank() -> Delivery.EXTERNAL
        else -> Delivery.UNKNOWN
    }

    fun parse(s: Stream): StreamInfo {
        val text = s.text
        val resolution = when {
            res2160.containsMatchIn(text) -> 2160
            res1440.containsMatchIn(text) -> 1440
            res1080.containsMatchIn(text) -> 1080
            res720.containsMatchIn(text) -> 720
            res576.containsMatchIn(text) -> 576
            res480.containsMatchIn(text) -> 480
            res360.containsMatchIn(text) -> 360
            else -> null
        }
        val release = when {
            remux.containsMatchIn(text) -> ReleaseType.REMUX
            bluray.containsMatchIn(text) -> ReleaseType.BLURAY
            webrip.containsMatchIn(text) -> ReleaseType.WEBRIP
            webdl.containsMatchIn(text) -> ReleaseType.WEBDL
            hdtv.containsMatchIn(text) -> ReleaseType.HDTV
            dvd.containsMatchIn(text) -> ReleaseType.DVD
            cam.containsMatchIn(text) -> ReleaseType.CAM
            else -> ReleaseType.UNKNOWN
        }
        val codec = when {
            av1.containsMatchIn(text) -> VideoCodec.AV1
            hevc.containsMatchIn(text) -> VideoCodec.HEVC
            h264.containsMatchIn(text) -> VideoCodec.H264
            else -> VideoCodec.UNKNOWN
        }
        val hdr = buildSet {
            if (dv.containsMatchIn(text)) add(Hdr.DOLBY_VISION)
            if (hdr10p.containsMatchIn(text)) add(Hdr.HDR10_PLUS)
            if (hdr10.containsMatchIn(text)) add(Hdr.HDR10)
            if (hlg.containsMatchIn(text)) add(Hdr.HLG)
        }
        val dvOnly = Hdr.DOLBY_VISION in hdr && hdr.size == 1
        val audio = buildSet {
            if (atmos.containsMatchIn(text)) add(AudioFormat.ATMOS)
            if (truehd.containsMatchIn(text)) add(AudioFormat.TRUEHD)
            if (dtsx.containsMatchIn(text)) add(AudioFormat.DTS_X)
            if (dtshd.containsMatchIn(text)) add(AudioFormat.DTS_HD_MA)
            else if (dts.containsMatchIn(text) && AudioFormat.DTS_X !in this) add(AudioFormat.DTS)
            if (eac3.containsMatchIn(text)) add(AudioFormat.EAC3)
            else if (ac3.containsMatchIn(text)) add(AudioFormat.AC3)
            if (flac.containsMatchIn(text)) add(AudioFormat.FLAC)
            if (opus.containsMatchIn(text)) add(AudioFormat.OPUS)
            if (aac.containsMatchIn(text)) add(AudioFormat.AAC)
            if (mp3.containsMatchIn(text)) add(AudioFormat.MP3)
        }
        val channels = channelsRx.find(text)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
        val size = s.behaviorHints.videoSize ?: sizeRx.findAll(text).mapNotNull { parseSize(it) }.maxOrNull()
        val langs = Languages.detect(text)
        val isMulti = multi.containsMatchIn(text) || langs.size > 1
        val cached = when {
            uncachedRx.containsMatchIn(text) -> false
            cachedRx.containsMatchIn(text) -> true
            else -> null
        }
        val filename = s.behaviorHints.filename ?: text.lines().firstOrNull { it.contains('.') && groupRx.containsMatchIn(it) }
        val group = filename?.let { f -> groupRx.findAll(f.trim()).lastOrNull()?.groupValues?.get(1) }
            ?.takeUnless { it.equals("mkv", true) || it.equals("mp4", true) || it.matches(Regex("\\d+p")) }
        return StreamInfo(
            delivery = delivery(s),
            resolution = resolution,
            releaseType = release,
            codec = codec,
            hdr = hdr,
            dvOnly = dvOnly,
            audio = audio,
            channels = channels,
            languages = langs,
            multiAudio = isMulti,
            sizeBytes = size,
            is3D = threeD.containsMatchIn(text),
            cached = cached,
            bingeGroup = s.behaviorHints.bingeGroup,
            releaseGroup = group,
            filename = filename?.trim(),
        )
    }

    private fun parseSize(m: MatchResult): Long? {
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase()) {
            "tb" -> 1e12; "gb" -> 1e9; "gib" -> 1073741824.0; "mib" -> 1048576.0; else -> 1e6
        }
        return (n * mult).toLong()
    }
}
