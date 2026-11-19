package com.vyrk.core.anime

import com.vyrk.core.playback.SegmentKind
import com.vyrk.core.playback.SkipOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AniSkipTest {
    @Test fun episodesFromKitsuAndMalIds() {
        assertEquals(AnimeEpisode(null, "46474", 5), AniSkip.episode("kitsu:46474", "kitsu:46474:5"))
        assertEquals(AnimeEpisode("52991", "46474", 5), AniSkip.episode("kitsu:46474", "kitsu:46474:5", malHint = "52991"))
        assertEquals(AnimeEpisode("52991", null, 12), AniSkip.episode("mal:52991", "mal:52991:12"))
        assertEquals(AnimeEpisode(null, "1", 1), AniSkip.episode("kitsu:1", null), "a film is episode 1")
        assertNull(AniSkip.episode("tt123", "tt123:1:2"))
        assertNull(AniSkip.episode("kitsu:1", "kitsu:2:3"))
    }

    @Test fun urlAsksForOpeningsEndingsAndRecaps() {
        assertEquals("https://api.aniskip.com/v2/skip-times/52991/5?types%5B%5D=op&types%5B%5D=ed&types%5B%5D=mixed-op&types%5B%5D=mixed-ed&types%5B%5D=recap&episodeLength=0",
            AniSkip.url("52991", 5))
    }

    private val body = """{"found":true,"results":[
        {"interval":{"startTime":1290.5,"endTime":1380.0},"skipType":"ed","skipId":"b","episodeLength":1420.0},
        {"interval":{"startTime":61.2,"endTime":150.7},"skipType":"op","skipId":"a","episodeLength":1420.0},
        {"interval":{"startTime":1.0,"endTime":0.5},"skipType":"recap","skipId":"c","episodeLength":1420.0}
        ],"message":"Successfully found skip times","statusCode":200}"""

    @Test fun parsesSecondsIntoSegments() {
        val segs = AniSkip.parse(body)
        assertEquals(listOf(SegmentKind.INTRO, SegmentKind.OUTRO), segs.map { it.kind })
        assertEquals(61_200L, segs[0].startMs); assertEquals(150_700L, segs[0].endMs)
        assertEquals(true, segs.all { it.anime })
        assertEquals(2, AniSkip.parse(body, fileDurationMs = 1_430_000).size)
        assertEquals(0, AniSkip.parse(body, fileDurationMs = 1_500_000).size, "timings for a different cut are dropped")
    }

    @Test fun endingIsSkippableOnlyWithSomethingAfterIt() {
        val offer = SkipOffer(AniSkip.parse(body))
        assertEquals(SegmentKind.INTRO, offer.at(70_000, 1_420_000)?.kind)
        // 40 s follow the ending: offer to skip it, and Up Next waits for the end.
        assertEquals(SegmentKind.OUTRO, offer.at(1_300_000, 1_420_000)?.kind)
        assertNull(offer.creditsStart(1_420_000))
        // The ending runs to the end: no skip, Up Next at the ending.
        assertNull(offer.at(1_300_000, 1_385_000))
        assertEquals(1_290_500L, offer.creditsStart(1_385_000))
    }

    @Test fun kitsuMappings() {
        val m = """{"data":[{"id":"1","type":"mappings","attributes":{"externalSite":"anidb","externalId":"9"}},
            {"id":"2","type":"mappings","attributes":{"externalSite":"myanimelist/anime","externalId":"52991"}}]}"""
        assertEquals("52991", AnimeIds.parseKitsuMappings(m))
        assertNull(AnimeIds.parseKitsuMappings("""{"data":[]}"""))
    }
}
