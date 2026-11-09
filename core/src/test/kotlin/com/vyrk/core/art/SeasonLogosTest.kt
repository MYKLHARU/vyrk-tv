package com.vyrk.core.art

import com.vyrk.core.addon.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SeasonLogosTest {
    private val child = MetaPreview("kitsu:50163", "series", "DAN DA DAN Season 3")
    private val link = Link("Prequel: Dandadan 2nd Season", "Franchise", "stremio:///detail/series/kitsu:49425")
    private val full = MetaItem(child.id, child.type, child.name, links = listOf(link))
    private val parent = MetaItem("kitsu:49425", "series", "Dandadan 2nd Season", logo = "https://synthetic.invalid/dandadan.png")

    @Test fun dandadanSeasonThreeUsesItsExplicitPrequelLogoWithoutReplacingMetadata() = runTest {
        val requests = mutableListOf<String>()
        val result = SeasonLogos.resolve(child, full) { requests += it; parent }
        assertEquals(listOf("kitsu:49425"), requests)
        assertEquals(listOf(parent.logo), result)
        assertEquals("kitsu:50163", child.id)
        assertNull(full.imdbId)
        assertNull(full.logo)
    }

    @Test fun unrelatedPrequelsWrongTypesAndWrongResponsesCannotSupplyLogos() = runTest {
        assertTrue(SeasonLogos.resolve(child, full) { parent.copy(name = "Another Show") }.isEmpty())
        assertTrue(SeasonLogos.resolve(child, full) { parent.copy(type = "movie") }.isEmpty())
        assertTrue(SeasonLogos.resolve(child, full) { parent.copy(id = "kitsu:999") }.isEmpty())
        assertNull(SeasonLogos.prequel(child, full.copy(id = "kitsu:999")))
        assertNull(SeasonLogos.prequel(child.copy(type = "movie"), full))
    }

    @Test fun externalUrlsSequelsAndCyclesAreRejected() = runTest {
        assertNull(SeasonLogos.prequel(child, full.copy(links = listOf(link.copy(url = "https://untrusted.invalid/kitsu:49425")))))
        assertNull(SeasonLogos.prequel(child, full.copy(links = listOf(link.copy(name = "Sequel: Dandadan")))))
        assertNull(SeasonLogos.prequel(child, full.copy(links = listOf(link.copy(url = "stremio:///detail/series/${child.id}")))))
        val cyclic = parent.copy(logo = null, links = listOf(link.copy(url = "stremio:///detail/series/${child.id}")))
        var calls = 0
        assertTrue(SeasonLogos.resolve(child, full) { calls++; cyclic }.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun actualAioMalEntryFollowsLowercasePrequelAndValidatesCanonicalParentAlias() = runTest {
        val preview = ProtocolJson.decodeFromString(MetaPreview.serializer(),
            """{"id":"mal:62516","type":"series","name":"Dan Da Dan Season 3","_kitsuId":"50163"}""")
        val full = ProtocolJson.decodeFromString(MetaItem.serializer(),
            """{"id":"mal:62516","type":"series","name":"DAN DA DAN Season 3","logo":null,"links":[{"name":"DAN DA DAN Season 2","category":"prequel","url":"stremio:///detail/series/kitsu:49425"}]}""")
        val canonical = ProtocolJson.decodeFromString(MetaItem.serializer(),
            """{"id":"tt30217403","type":"series","name":"DAN DA DAN","_kitsuId":"49425","_malId":"60543","imdb_id":"tt30217403","logo":"https://assets.fanart.tv/fanart/dandadan-656822e401831.png"}""")
        val ids = mutableListOf<String>()
        val logos = SeasonLogos.resolve(preview, full) { ids += it; canonical }
        assertEquals(listOf("kitsu:49425"), ids)
        assertEquals(canonical.logo, logos.first())
        assertEquals("mal:62516", preview.id)
        assertEquals("mal:62516", full.id)
        assertTrue(SeasonLogos.resolve(preview, full) { canonical.copy(kitsuId = "999") }.isEmpty())
        assertTrue(SeasonLogos.resolve(preview, full) { canonical.copy(kitsuId = null) }.isEmpty())
    }

    @Test fun malPrequelUrlsAndBlankDeepLinkNamesUseVerifiedMetadata() = runTest {
        val selected = MetaPreview("mal:62516", "series")
        val details = MetaItem(selected.id, selected.type, "Dan Da Dan Season 3", links = listOf(
            Link("DAN DA DAN Season 2", "Prequel", "stremio:///detail/series/mal:60543")))
        val canonical = MetaItem("tt30217403", "series", "DAN DA DAN", logo = "https://synthetic.invalid/logo.png", malId = "60543")
        assertEquals(canonical.logo, SeasonLogos.resolve(selected, details) { canonical }.first())
        assertTrue(SeasonLogos.resolve(selected, details) { canonical.copy(malId = "999") }.isEmpty())
    }
}
