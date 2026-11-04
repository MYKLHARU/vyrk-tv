package com.vyrk.core.playback

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroDbTest {
    // Shape of a live response from api.introdb.app/segments (tt0944947 S1E1), trimmed.
    private val live = """{"imdb_id":"tt0944947","media_type":"tv","is_movie":false,"season":1,"episode":1,
        "intro":{"start_sec":437,"end_sec":531,"start_ms":437000,"end_ms":531000,"confidence":1,"submission_count":2},
        "recap":null,
        "outro":{"start_sec":3631.5,"end_sec":3699.5,"start_ms":3631500,"end_ms":3699500,"confidence":1},
        "post_credits":null,"no_intro":false,"no_recap":true,"no_outro":false}"""

    @Test
    fun urls() {
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0944947&season=1&episode=2", IntroDb.url("series", "tt0944947", "tt0944947:1:2"))
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0111161&is_movie=true", IntroDb.url("movie", "tt0111161", "tt0111161"))
        assertNull(IntroDb.url("series", "kitsu:1", "kitsu:1:1"))
        assertNull(IntroDb.url("series", "tt1", "tt1"))
        assertNull(IntroDb.url("tv", "tt1", "tt1"))
    }

    @Test
    fun parsesSegmentsAndDropsLowConfidence() {
        val s = IntroDb.parse(live)
        assertEquals(listOf(SegmentKind.INTRO, SegmentKind.OUTRO), s.map { it.kind })
        assertEquals(437_000, s[0].startMs); assertEquals(3_631_500, s[1].startMs)
        val low = IntroDb.parse("""{"intro":{"start_ms":1000,"end_ms":60000,"confidence":0.5}}""")
        assertTrue(low.isEmpty())
        val bad = IntroDb.parse("""{"intro":{"start_ms":9000,"end_ms":1000,"confidence":1}}""")
        assertTrue(bad.isEmpty())
        assertTrue(IntroDb.parse("not json").isEmpty())
        assertTrue(IntroDb.confirmedNoIntro("""{"intro":null,"no_intro":true}"""))
    }

    @Test
    fun skipOfferedInsideIntroOnceAndCreditsKnown() {
        val offer = SkipOffer(IntroDb.parse(live))
        assertNull(offer.at(400_000))
        val seg = offer.at(450_000)!!
        assertEquals(SegmentKind.INTRO, seg.kind)
        assertEquals(531_000, offer.target(seg))
        assertNull(offer.at(530_000)) // last 2 s: not worth a button
        offer.dismiss(seg)
        assertNull(offer.at(450_000))
        assertEquals(3_631_500, offer.creditsStartMs)
    }
}

class ChaptersTest {
    private val chapters = listOf(Chapter(0, "Opening"), Chapter(440_000, "Titles"), Chapter(900_000, "The Heist"), Chapter(2_000_000, "Ending"))
    private val segments = listOf(Segment(SegmentKind.INTRO, 437_000, 531_000), Segment(SegmentKind.OUTRO, 3_600_000, 3_700_000))

    @Test
    fun mergeLetsIntroDbWinWithinFiveSeconds() {
        val m = Markers.merge(chapters, segments, 3_700_000)
        assertEquals(listOf(0L, 437_000L, 531_000L, 900_000L, 2_000_000L, 3_600_000L), m.map { it.startMs })
        assertEquals(SegmentKind.INTRO, m[1].kind)
        assertEquals("The Heist", Markers.at(m, 1_000_000)?.title)
        assertEquals(900_000, Markers.next(m, 600_000))
        assertEquals(531_000, Markers.previous(m, 900_000))
        assertEquals(0, Markers.previous(m, 500))
    }

    @Test
    fun scrubAcceleratesAndSnapsToChapters() {
        val m = Markers.merge(chapters, emptyList(), 3_700_000)
        val s = Scrubber({ m }, { 3_700_000 })
        var now = 0L
        s.step(true, 100_000, now)
        assertEquals(110_000, s.targetMs)
        repeat(3) { now += 300; s.step(true, 100_000, now) }
        assertEquals(140_000, s.targetMs) // still 10 s steps
        now += 300; s.step(true, 100_000, now)
        assertEquals(170_000, s.targetMs) // 30 s steps after four quick presses
        assertFalse(s.shouldAutoCommit(now + 500))
        assertTrue(s.shouldAutoCommit(now + 1_000))
        assertEquals(170_000, s.commit())
        assertNull(s.targetMs)
        // Snap: from 432 s, +10 s lands within 3 s of the 440 s chapter.
        s.step(true, 432_000, 10_000)
        assertEquals(440_000, s.targetMs)
        s.cancel()
        s.step(false, 2_000, 20_000)
        assertEquals(0, s.targetMs) // clamped at the start
    }

    @Test
    fun chapterModeJumpsBetweenStarts() {
        val m = Markers.merge(chapters, emptyList(), 3_700_000)
        val s = Scrubber({ m }, { 3_700_000 })
        assertTrue(s.enterChapterMode(1_000_000))
        assertEquals(900_000, s.targetMs)
        s.step(true, 1_000_000, 0); assertEquals(2_000_000, s.targetMs)
        s.step(false, 1_000_000, 0); assertEquals(900_000, s.targetMs)
        s.step(false, 1_000_000, 0); assertEquals(440_000, s.targetMs)
        assertFalse(s.shouldAutoCommit(100_000)) // chapter mode waits for OK
        assertFalse(Scrubber({ emptyList() }, { 0 }).enterChapterMode(0))
    }
}

/** Builds Matroska bytes for tests: EBML elements with explicit sizes. */
private object Ebml {
    fun id(v: Long): ByteArray {
        val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }
    fun size(v: Long): ByteArray = byteArrayOf(0x08, 0, 0, 0, ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()).let {
        // 4-byte size: first byte marker 0x10
        byteArrayOf((0x10 or ((v shr 24).toInt() and 0x0F)).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }
    fun el(id: Long, vararg body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(); body.forEach { b.write(it) }
        val content = b.toByteArray()
        return id(id) + size(content.size.toLong()) + content
    }
    fun uint(id: Long, v: Long, n: Int = 8): ByteArray = el(id, ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() })
    fun str(id: Long, s: String): ByteArray = el(id, s.toByteArray())
    fun atom(ms: Long, title: String, hidden: Boolean = false) = el(0xB6, uint(0x73C4, ms + 1, 4), uint(0x91, ms * 1_000_000), uint(0x98, if (hidden) 1 else 0, 1), el(0x80, str(0x85, title), str(0x437C, "eng")))
    fun chapters(vararg atoms: ByteArray) = el(0x1043A770, el(0x45B9, uint(0x45DB, 1, 1), *atoms))
}

class MkvChaptersTest {
    private val header = Ebml.el(0x1A45DFA3, Ebml.str(0x4282, "matroska"))

    private fun fetcher(file: ByteArray, calls: MutableList<Pair<Long, Int>>) = { start: Long, len: Int ->
        calls += start to len
        if (start >= file.size) null else file.copyOfRange(start.toInt(), minOf(file.size, (start + len).toInt()))
    }

    @Test
    fun readsChaptersInTheFirstBlock() = runTest {
        val chapters = Ebml.chapters(Ebml.atom(0, "Opening"), Ebml.atom(312_500, "Act Two"), Ebml.atom(600_000, "Hidden", hidden = true))
        val segment = Ebml.el(0x18538067, Ebml.uint(0x2AD7B1, 1_000_000, 3), chapters, Ebml.el(0x1F43B675, ByteArray(10)))
        val file = header + segment
        val calls = mutableListOf<Pair<Long, Int>>()
        val out = MkvChapters.read { s, l -> fetcher(file, calls)(s, l) }
        assertEquals(listOf(Chapter(0, "Opening"), Chapter(312_500, "Act Two")), out)
        assertEquals(1, calls.size)
    }

    @Test
    fun followsSeekHeadToChaptersAtTheEnd() = runTest {
        val chapters = Ebml.chapters(Ebml.atom(0, "Start"), Ebml.atom(1_200_000, "Finale"))
        // Segment body: SeekHead, Info, a big Cluster beyond the first read, then Chapters.
        val cluster = Ebml.el(0x1F43B675, ByteArray(MkvChapters.FIRST_READ + 5_000))
        val info = Ebml.el(0x1549A966, Ebml.uint(0x2AD7B1, 1_000_000, 3))
        fun seekHead(pos: Long) = Ebml.el(0x114D9B74, Ebml.el(0x4DBB, Ebml.uint(0x53AB, 0x1043A770, 4), Ebml.uint(0x53AC, pos, 8)))
        val headLen = seekHead(0).size
        val chaptersPos = (headLen + info.size + cluster.size).toLong()
        val body = seekHead(chaptersPos) + info + cluster + chapters
        val file = header + Ebml.el(0x18538067, body)
        val calls = mutableListOf<Pair<Long, Int>>()
        val out = MkvChapters.read { s, l -> fetcher(file, calls)(s, l) }
        assertEquals(listOf(Chapter(0, "Start"), Chapter(1_200_000, "Finale")), out)
        assertTrue(calls.size <= 3, "range reads: $calls")
    }

    @Test
    fun notMatroskaGivesNothing() = runTest {
        assertTrue(MkvChapters.read { _, _ -> "ftypisom-mp4-data".toByteArray() }.isEmpty())
        assertTrue(MkvChapters.read { _, _ -> null }.isEmpty())
    }
}

class AudioFallbackTest {
    private val tracks = listOf(
        AudioTrack("1", "eng", "English TrueHD 7.1", 8, "audio/true-hd"),
        AudioTrack("2", "eng", "English AC-3 5.1", 6, "audio/ac3"),
        AudioTrack("3", "eng", "English AAC", 2, "audio/mp4a-latm"),
        AudioTrack("4", "ita", "Italiano", 6, "audio/ac3"),
        AudioTrack("5", "eng", "Commentary", 2, "audio/mp4a-latm"),
    )

    @Test
    fun sameLanguageSimplerCodecFirst() {
        val p = AudioPrefs(languages = listOf("eng"))
        assertEquals("2", TrackSelector.fallbackAudio(tracks, "1", emptySet(), p)!!.id)
        assertEquals("3", TrackSelector.fallbackAudio(tracks, "2", setOf("1"), p)!!.id)
        // English all broken: preference order, never commentary.
        assertEquals("4", TrackSelector.fallbackAudio(tracks, "3", setOf("1", "2"), p.copy(languages = listOf("ita")))!!.id)
        assertNull(TrackSelector.fallbackAudio(tracks.take(1), "1", emptySet(), p))
    }
}
