package com.vyrk.core.stream

import com.vyrk.core.addon.Stream
import com.vyrk.core.addon.StreamBehaviorHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamRankerTest {
    private fun c(name: String, title: String = "", url: String? = "https://h/${name.hashCode()}.mkv", order: Int = 0, index: Int = 0,
                  infoHash: String? = null, binge: String? = null, filename: String? = null) =
        Candidate(Stream(url = url, infoHash = infoHash, name = name, title = title,
            behaviorHints = StreamBehaviorHints(bingeGroup = binge, filename = filename)), "addon$order", "Addon $order", order, index)

    private val tv4kHdr = DeviceCaps(maxHeight = mapOf(VideoCodec.H264 to 2160, VideoCodec.HEVC to 2160), hevc = true,
        displayHdr10 = true, displayDolbyVision = false, display4k = true)
    private val tv1080 = DeviceCaps(maxHeight = mapOf(VideoCodec.H264 to 1080, VideoCodec.HEVC to 1080), hevc = true,
        displayHdr10 = false, displayDolbyVision = false, display4k = false)

    @Test
    fun parsesCommonReleaseNames() {
        val i = StreamParser.parse(Stream(url = "https://x", name = "[RD+] Torrentio\n4K", title =
            "Movie.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-GRP.mkv\n👤 12 💾 54.3 GB ⚙️ X\nMulti Audio ENG ITA"))
        assertEquals(2160, i.resolution)
        assertEquals(ReleaseType.REMUX, i.releaseType)
        assertEquals(VideoCodec.HEVC, i.codec)
        assertTrue(Hdr.DOLBY_VISION in i.hdr && Hdr.HDR10 in i.hdr)
        assertFalse(i.dvOnly)
        assertTrue(AudioFormat.ATMOS in i.audio && AudioFormat.TRUEHD in i.audio)
        assertEquals("7.1", i.channels)
        assertEquals(54_300_000_000L, i.sizeBytes)
        assertEquals(true, i.cached)
        assertTrue(i.multiAudio)
        assertEquals(listOf("eng", "ita"), i.languages)
        assertEquals("GRP", i.releaseGroup)

        val w = StreamParser.parse(Stream(url = "https://x", title = "Show.S01E01.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv 2.1 GB"))
        assertEquals(1080, w.resolution); assertEquals(ReleaseType.WEBDL, w.releaseType); assertEquals(VideoCodec.H264, w.codec)
        assertTrue(AudioFormat.EAC3 in w.audio); assertEquals("5.1", w.channels)
        assertEquals("NTb", w.releaseGroup)

        val cam = StreamParser.parse(Stream(url = "https://x", title = "New.Movie.2025.HDCAM.x264"))
        assertEquals(ReleaseType.CAM, cam.releaseType)
        assertEquals(false, StreamParser.parse(Stream(url = "https://x", name = "[RD download] T")).cached)
    }

    @Test
    fun hardLimitsExcludeAndExplain() {
        val prefs = RankPrefs(maxSizeBytes = 20_000_000_000L)
        val r = StreamRanker.rank(listOf(
            c("Torrent 1080p", url = null, infoHash = "abc", index = 0),
            c("YT", url = null, index = 1).let { it.copy(stream = Stream(ytId = "x", name = "YT")) },
            c("Ext", url = null, index = 2).let { it.copy(stream = Stream(externalUrl = "https://x", name = "Ext")) },
            c("[RD download] 1080p", index = 3),
            c("4K HEVC", title = "2160p HEVC", index = 4),
            c("1080p big", title = "1080p 25 GB", index = 5),
            c("720p ok", title = "720p x264 1.2 GB", index = 6),
        ), tv1080, prefs)
        assertEquals("720p ok", r.first().candidate.stream.name)
        val byName = r.associateBy { it.candidate.stream.name }
        assertEquals(Exclusion.TORRENT_NO_SERVER, byName["Torrent 1080p"]!!.exclusion)
        assertEquals(Exclusion.YOUTUBE, byName["YT"]!!.exclusion)
        assertEquals(Exclusion.EXTERNAL, byName["Ext"]!!.exclusion)
        assertEquals(Exclusion.UNCACHED, byName["[RD download] 1080p"]!!.exclusion)
        assertEquals(Exclusion.OVER_DECODER_LIMIT, byName["4K HEVC"]!!.exclusion)
        assertEquals(Exclusion.OVER_MAX_SIZE, byName["1080p big"]!!.exclusion)
    }

    @Test
    fun unknownCapabilityIsNeverUnsupported() {
        val r = StreamRanker.best(listOf(c("4K HEVC", title = "2160p HEVC")), DeviceCaps(), RankPrefs())
        assertNotNull(r)
        assertTrue(r.reasons.any { it.code == "hevc_unconfirmed" })
    }

    @Test
    fun balancedPrefers1080pUnless4kHdrOnA4kHdrDisplay() {
        val list = listOf(c("4K SDR", title = "2160p WEB-DL x264", index = 0), c("1080p", title = "1080p WEB-DL x264", index = 1))
        assertEquals("1080p", StreamRanker.best(list, tv4kHdr, RankPrefs())!!.candidate.stream.name)
        val hdr = listOf(c("4K HDR", title = "2160p WEB-DL HDR10 HEVC", index = 0), c("1080p", title = "1080p WEB-DL x264", index = 1))
        assertEquals("4K HDR", StreamRanker.best(hdr, tv4kHdr, RankPrefs())!!.candidate.stream.name)
    }

    @Test
    fun bestPictureFavoursRemuxAndHighestResolution() {
        val list = listOf(c("1080p WEB", title = "1080p WEB-DL 3 GB"), c("4K Remux", title = "2160p REMUX HDR10 HEVC 60 GB", index = 1))
        assertEquals("4K Remux", StreamRanker.best(list, tv4kHdr, RankPrefs(preset = Preset.BEST_PICTURE))!!.candidate.stream.name)
    }

    @Test
    fun fastStartAvoidsRemuxAndFavoursSmallReadyFiles() {
        val list = listOf(c("Remux", title = "1080p REMUX 30 GB"), c("[RD+] 720p", title = "720p WEB-DL 1.1 GB", index = 1))
        assertEquals("[RD+] 720p", StreamRanker.best(list, tv1080, RankPrefs(preset = Preset.FAST_START))!!.candidate.stream.name)
    }

    @Test
    fun addonOrderKeepsTheAddonsOwnOrder() {
        val list = listOf(c("480p", title = "480p", order = 0), c("1080p", title = "1080p", order = 1))
        assertEquals("480p", StreamRanker.best(list, tv1080, RankPrefs(preset = Preset.ADDON_ORDER))!!.candidate.stream.name)
    }

    @Test
    fun hdrPenaltiesFollowTheDisplay() {
        val dvOnly = c("DV", title = "1080p WEB-DL DV HEVC")
        val r = StreamRanker.score(dvOnly, tv1080, RankPrefs())
        assertTrue(r.reasons.any { it.code == "dv_only_on_non_dv" })
        val hdrOnSdr = StreamRanker.score(c("HDR", title = "1080p HDR10 HEVC"), tv1080, RankPrefs())
        assertTrue(hdrOnSdr.reasons.any { it.code == "hdr_on_sdr" })
        val hdrOnHdr = StreamRanker.score(c("HDR", title = "1080p HDR10 HEVC"), tv4kHdr, RankPrefs())
        assertTrue(hdrOnHdr.reasons.any { it.code == "hdr" && it.points > 0 })
    }

    @Test
    fun deviceFitPenalisesSourcesFasterThanTheConnection() {
        val caps = tv4kHdr.copy(measuredBps = 20_000_000.0)
        // 2 hours: 60 GB is ~66 Mbps (>1.5x), 16 GB is ~17.8 Mbps (>0.85x), 4 GB is ~4.4 Mbps.
        val prefs = RankPrefs(runtimeMinutes = 120)
        assertEquals(-90, StreamRanker.score(c("a", title = "1080p 60 GB"), caps, prefs).reasons.first { it.code == "too_fast_for_connection" }.points)
        assertEquals(-35, StreamRanker.score(c("b", title = "1080p 16 GB"), caps, prefs).reasons.first { it.code == "near_connection_limit" }.points)
        assertTrue(StreamRanker.score(c("c", title = "1080p 4 GB"), caps, prefs).reasons.none { it.code.contains("connection") })
        assertEquals(-54, StreamRanker.score(c("a", title = "1080p 60 GB"), caps, prefs.copy(preset = Preset.BEST_PICTURE)).reasons.first { it.code == "too_fast_for_connection" }.points)
    }

    @Test
    fun memoryAndBingeGroupWin() {
        val a = c("720p", title = "720p", index = 0, filename = "Show.S01E02.720p-GRP.mkv")
        val b = c("1080p", title = "1080p WEB-DL", index = 1, binge = "addon|1080p|WEB")
        assertEquals("720p", StreamRanker.best(listOf(a, b), tv1080, RankPrefs(rememberedKey = a.key))!!.candidate.stream.name)
        val r = StreamRanker.score(b, tv1080, RankPrefs(lastBingeGroup = "addon|1080p|WEB"))
        assertEquals(500, r.reasons.first { it.code == "same_release_group" }.points)
        val g = StreamRanker.score(a, tv1080, RankPrefs(lastReleaseGroup = "grp"))
        assertTrue(g.reasons.any { it.code == "same_release_group" })
    }

    @Test
    fun audioLanguagePreference() {
        val prefs = RankPrefs(audioLanguages = listOf("ita"))
        val eng = c("ENG", title = "1080p WEB-DL English", index = 0)
        val ita = c("ITA", title = "1080p WEB-DL Italian", index = 1)
        val multi = c("MULTI", title = "1080p WEB-DL MULTI", index = 2)
        val r = StreamRanker.rank(listOf(eng, ita, multi), tv1080, prefs).map { it.candidate.stream.name }
        assertEquals(listOf("ITA", "MULTI", "ENG"), r)
    }

    @Test
    fun camIsAlwaysFarDownAnd3dPenalised() {
        val r = StreamRanker.rank(listOf(c("CAM 1080p", title = "1080p HDCAM"), c("480p", title = "480p DVDRip", index = 1)), tv1080, RankPrefs())
        assertEquals("480p", r.first().candidate.stream.name)
        assertTrue(StreamRanker.score(c("3D", title = "1080p 3D SBS"), tv1080, RankPrefs()).reasons.any { it.code == "3d" })
    }

    @Test
    fun rankingIsDeterministic() {
        val list = (0 until 30).map { c("S$it", title = listOf("1080p", "720p", "2160p HDR10", "480p")[it % 4] + " ${it % 7} GB", order = it % 3, index = it) }
        val first = StreamRanker.rank(list, tv4kHdr, RankPrefs()).map { it.candidate.key }
        repeat(5) { assertEquals(first, StreamRanker.rank(list.shuffled(java.util.Random(it.toLong())), tv4kHdr, RankPrefs()).map { r -> r.candidate.key }) }
    }

    @Test
    fun maxResolutionLimit() {
        val r = StreamRanker.score(c("4K", title = "2160p"), tv4kHdr, RankPrefs(maxResolution = 1080))
        assertEquals(Exclusion.OVER_MAX_RESOLUTION, r.exclusion)
    }
}
