package com.vyrk.tv.data

import com.vyrk.core.playback.SubtitleMode
import com.vyrk.core.stream.FormatPref
import com.vyrk.core.stream.HdrPref
import com.vyrk.core.stream.Preset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/** Top-bar tabs. Add-on management lives in Settings › Add-ons, not here. */
enum class Tab(val key: String) { HOME("home"), MOVIES("movies"), SERIES("series"), DISCOVER("discover"), ANIME("anime"), LIVE("live"), LIBRARY("library") }
fun visibleTab(current: Tab, visible: List<Tab>): Tab = current.takeIf { it in visible } ?: Tab.HOME

enum class PreviewMode { OFF, MANUAL, AUTOMATIC }
enum class SkipIntroMode { OFF, BUTTON, AUTOMATIC }

/** A local profile. No secrets here: the authKey and add-on URLs live in the vault. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val colorIndex: Int = 0,
    val pinHash: String? = null,
    val pinFailures: Int = 0,
    val pinLockedUntilMs: Long = 0,
    val accountEmail: String? = null,
    val createdMs: Long = 0,
)

/** Per-profile settings. */
@Serializable
data class ProfileSettings(
    val defaultTab: String = "home",
    val animeMode: Boolean = false,
    val animeGenre: String? = null,
    val hiddenTabs: Set<String> = emptySet(),
    val heroEnabled: Boolean = true,
    val heroRotation: Boolean = true,
    val posterSize: Int = 118,
    // Stored under a new key so everyone moved to the gold default once (the old key held FROST).
    @kotlinx.serialization.SerialName("accentColour") val accent: String = "GOLD",
    val reduceMotion: Boolean = false,
    /** Expanding cards: the focused poster widens into the title's backdrop. */
    val expandCards: Boolean = false,
    val blurUnwatched: Boolean = false,
    val hiddenRows: Set<String> = emptySet(),
    val top10CardDismissed: Boolean = false,
    val preset: Preset = Preset.BALANCED,
    val maxResolution: Int? = null,
    val maxSizeGb: Int? = null,
    val format: FormatPref = FormatPref.ANY,
    val hdr: HdrPref = HdrPref.ANY,
    val alwaysShowSources: Boolean = false,
    val sourcesOnInfoPage: Boolean = true,
    val loadSourcesOnOpen: Boolean = true,
    val autoplayNext: Boolean = true,
    val askStillWatching: Boolean = true,
    val skipIntro: SkipIntroMode = SkipIntroMode.BUTTON,
    val chapterMarkers: Boolean = true,
    val audioLanguages: List<String> = listOf("eng"),
    val originalLanguage: Boolean = false,
    val avoidCommentary: Boolean = true,
    val subtitleMode: SubtitleMode = SubtitleMode.FOREIGN_ONLY,
    val subtitleLanguages: List<String> = listOf("eng"),
    val subtitleSize: Int = 100,
    val subtitleColor: String = "WHITE",
    val subtitleBackground: String = "SHADOW",
    val previews: PreviewMode = PreviewMode.MANUAL,
    val previewSound: Boolean = false,
    val shareStreamAddons: Boolean = false,
    val useSharedAddons: Boolean = false,
    /** Which of [ProfileSettings.upgraded]'s one-time changes these settings have had. */
    val revision: Int = 0,
)

/** One-time changes to saved settings when a default changes for everyone. */
fun ProfileSettings.upgraded(): ProfileSettings {
    var s = this
    // 1 (v0.1.61): trailers that start by themselves while browsing cost the TV a web page each;
    // previews are Manual by default now, for profiles saved before too (Automatic is still there).
    if (s.revision < 1 && s.previews == PreviewMode.AUTOMATIC) s = s.copy(previews = PreviewMode.MANUAL)
    return if (s.revision < 1) s.copy(revision = 1) else s
}

/** TV-wide settings. */
@Serializable
data class DeviceSettings(
    val interfaceSize: String = "MEDIUM",
    /** Soft clicks when moving and selecting with the remote. */
    val navSounds: Boolean = false,
    /** The sound with VYRK's opening animation (TV only). Off unless switched on. */
    val introSound: Boolean = false,
    val frameRateMatching: Boolean = false,
    /** Play Dolby Vision files with an HDR10 base as HDR10 (more stable on some boxes). */
    val dolbyVisionAsHdr10: Boolean = false,
    /** Play in another app by default: its package, or "" for VYRK's own player. */
    val externalPlayer: String = "",
    val passthrough: Boolean = false,
    val tvSpeakerMode: Boolean = false,
    val softwareAudio: Boolean = false,
    val clearDialogue: Boolean = false,
    val appLanguage: String = "",
    val autoUpdateChecks: Boolean = true,
    val lastUpdateCheckMs: Long = 0,
    val lastProfileId: String? = null,
    val firstRunDone: Boolean = false,
    val measuredBps: Double? = null,
    /** The player's picture size: FIT, ZOOM or STRETCH. */
    val pictureSize: String = "FIT",
)

/** Where focus was on a tab: the row key, item index and the item ID as a tie-breaker. */
@Serializable
data class FocusMemory(val rowKey: String, val index: Int, val itemId: String? = null)

/** Per-title playback memory: last picked file and subtitle/audio choices. */
@Serializable
data class TitleMemory(
    val pickedKey: String? = null,
    val bingeGroup: String? = null,
    val releaseGroup: String? = null,
    val audio: String? = null,
    val subtitle: String? = null,
    val subtitleOff: Boolean = false,
)

@Serializable
data class SessionState(
    val focus: Map<String, FocusMemory> = emptyMap(),
    val titleMemory: Map<String, TitleMemory> = emptyMap(),
    val hiddenContinueWatching: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(),
    val newEpisodes: Map<String, Set<String>> = emptyMap(),
    val newEpisodesCheckedMs: Long? = null,
    val pendingIds: Set<String> = emptySet(),
    val addonsRevision: Long = 0,
    val addonsSyncedRevision: Long = 0,
    val addonsAccountEmail: String? = null,
    val signedOut: Boolean = false,
)

val ProfileColors = listOf(0xFFA9E1F3, 0xFF6EA8FF, 0xFF7FD1C7, 0xFFB79CFF, 0xFFFFB86B, 0xFFFF7A90)
