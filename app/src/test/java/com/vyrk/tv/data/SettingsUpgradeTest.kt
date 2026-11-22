package com.vyrk.tv.data

import kotlin.test.*

class SettingsUpgradeTest {
    @Test fun automaticPreviewsBecomeManualOnceAndStayAsChosenAfter() {
        val old = AppJson.decodeFromString(ProfileSettings.serializer(), """{"previews":"AUTOMATIC"}""")
        val upgraded = old.upgraded()
        assertEquals(PreviewMode.MANUAL, upgraded.previews)
        assertEquals(1, upgraded.revision)
        // Chosen again after the upgrade: kept.
        val chosen = upgraded.copy(previews = PreviewMode.AUTOMATIC)
        val reloaded = AppJson.decodeFromString(ProfileSettings.serializer(), AppJson.encodeToString(ProfileSettings.serializer(), chosen))
        assertEquals(PreviewMode.AUTOMATIC, reloaded.upgraded().previews)
        // Off stays off; new profiles start on Manual.
        assertEquals(PreviewMode.OFF, ProfileSettings(previews = PreviewMode.OFF).upgraded().previews)
        assertEquals(PreviewMode.MANUAL, ProfileSettings().upgraded().previews)
    }
}
