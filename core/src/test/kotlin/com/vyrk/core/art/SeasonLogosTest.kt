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
}
