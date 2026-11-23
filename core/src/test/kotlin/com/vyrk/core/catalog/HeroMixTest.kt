package com.vyrk.core.catalog

import com.vyrk.core.addon.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals

class HeroMixTest {
    private fun row(vararg ids: String) = ids.map { MetaPreview(it, "movie") }
    private fun ids(list: List<MetaPreview>) = list.map { it.id }

    @Test
    fun takesTitlesInTurnFromEachRow() {
        val mix = CatalogRules.heroMix(listOf(row("a1", "a2", "a3"), row("b1", "b2"), row("c1")), count = 5)
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), ids(mix))
    }

    @Test
    fun skipsRepeatsAndStopsWhenRowsRunOut() {
        val mix = CatalogRules.heroMix(listOf(row("a", "b"), row("a", "c"), emptyList()), count = 6)
        assertEquals(listOf("a", "b", "c"), ids(mix))
        assertEquals(emptyList(), CatalogRules.heroMix(emptyList(), count = 6))
    }
}
