package com.vyrk.core.playback

import com.vyrk.core.lang.Languages

data class AudioTrack(
    val id: String,
    val language: String?,
    val label: String?,
    val channels: Int = 0,
    val codec: String? = null,
    val isDefault: Boolean = false,
)

data class TextTrack(
    val id: String,
    val language: String?,
    val label: String?,
    val forced: Boolean = false,
    val source: String = "Embedded",
    val isDefault: Boolean = false,
)

enum class SubtitleMode { ALWAYS, OFF, FOREIGN_ONLY }

data class AudioPrefs(
    val languages: List<String> = emptyList(),
    val originalLanguage: Boolean = false,
    val originalLanguageCode: String? = null,
    val avoidCommentary: Boolean = true,
    val remembered: String? = null,
)

data class SubtitlePrefs(
    val mode: SubtitleMode = SubtitleMode.FOREIGN_ONLY,
    val languages: List<String> = emptyList(),
    val forcedOnly: Boolean = false,
    val remembered: String? = null,
    /** "off" remembered explicitly for this title or carried over from the last episode. */
    val rememberedOff: Boolean = false,
)

object TrackSelector {
    private val commentaryRx = Regex("""(?i)\b(commentary|comment|director'?s?\s+comm|kommentar|commentaire)\b""")

    fun isCommentary(t: AudioTrack) = t.label?.let { commentaryRx.containsMatchIn(it) } == true

    /** Picks an audio track; returns null to keep the player's default. */
    fun selectAudio(tracks: List<AudioTrack>, prefs: AudioPrefs): AudioTrack? {
        if (tracks.isEmpty()) return null
        prefs.remembered?.let { r -> tracks.firstOrNull { it.id == r || Languages.normalise(it.language ?: "") == Languages.normalise(r) }?.let { return it } }
        val pool = if (prefs.avoidCommentary) tracks.filterNot { isCommentary(it) }.ifEmpty { tracks } else tracks
        val wanted = buildList {
            if (prefs.originalLanguage && prefs.originalLanguageCode != null) add(Languages.normalise(prefs.originalLanguageCode))
            addAll(prefs.languages.map { Languages.normalise(it) })
        }
        for (lang in wanted) {
            val matches = pool.filter { it.language != null && Languages.normalise(it.language) == lang }
            if (matches.isNotEmpty()) return matches.maxWith(compareBy<AudioTrack> { it.isDefault }.thenBy { it.channels })
        }
        return pool.firstOrNull { it.isDefault } ?: pool.first()
    }

    /**
     * Picks a subtitle track or null for off. Foreign-only shows subtitles only when the audio
     * language isn't one the viewer reads, or the track is forced.
     */
    fun selectSubtitle(tracks: List<TextTrack>, prefs: SubtitlePrefs, audioLanguage: String?): TextTrack? {
        if (prefs.rememberedOff) return null
        prefs.remembered?.let { r -> tracks.firstOrNull { it.id == r }?.let { return it } }
        if (prefs.mode == SubtitleMode.OFF) return null
        val langs = prefs.languages.map { Languages.normalise(it) }
        val audio = audioLanguage?.let { Languages.normalise(it) }
        val audioUnderstood = audio != null && audio in langs
        fun ordered(cands: List<TextTrack>) = langs.firstNotNullOfOrNull { l ->
            cands.filter { it.language != null && Languages.normalise(it.language) == l }
                .sortedWith(compareByDescending<TextTrack> { it.source == "Embedded" }.thenByDescending { it.isDefault })
                .firstOrNull()
        }
        val forced = tracks.filter { it.forced }
        if (prefs.forcedOnly) return ordered(forced) ?: forced.firstOrNull { it.language == null || Languages.normalise(it.language) == audio }
        return when (prefs.mode) {
            SubtitleMode.ALWAYS -> ordered(tracks.filterNot { it.forced }) ?: ordered(tracks)
            SubtitleMode.FOREIGN_ONLY -> if (audioUnderstood) ordered(forced) else ordered(tracks.filterNot { it.forced }) ?: ordered(tracks)
            SubtitleMode.OFF -> null
        }
    }

    /** Higher means more demanding to decode or pass through. */
    private fun codecWeight(codec: String?): Int {
        // Media3 MIME types: audio/true-hd, audio/vnd.dts(.hd), audio/eac3, audio/ac3, audio/mp4a-latm.
        val c = codec?.lowercase()?.replace("-", "") ?: return 2
        return when {
            "truehd" in c || "mlp" in c -> 6
            "dts" in c -> 5
            "eac3" in c || "ec3" in c -> 4
            "ac3" in c -> 3
            "opus" in c || "vorbis" in c || "flac" in c -> 2
            else -> 1 // AAC, MP3 and PCM decode everywhere
        }
    }

    /**
     * Track to switch to when [failedId] breaks mid-film. Prefers the same language on a simpler
     * codec (TrueHD → E-AC-3 → AC-3 → AAC), then the usual preference order; never a track that
     * already failed. Returns null when nothing is left.
     */
    fun fallbackAudio(tracks: List<AudioTrack>, failedId: String?, failed: Set<String>, prefs: AudioPrefs): AudioTrack? {
        val broken = tracks.firstOrNull { it.id == failedId }
        val left = tracks.filter { it.id !in failed && it.id != failedId }
        if (left.isEmpty()) return null
        val pool = if (prefs.avoidCommentary) left.filterNot { isCommentary(it) }.ifEmpty { left } else left
        val brokenLang = broken?.language?.let { Languages.normalise(it) }
        if (brokenLang != null) {
            val same = pool.filter { it.language != null && Languages.normalise(it.language) == brokenLang }
            same.filter { codecWeight(it.codec) < codecWeight(broken.codec) }.maxByOrNull { codecWeight(it.codec) }?.let { return it }
            same.minByOrNull { codecWeight(it.codec) }?.let { return it }
        }
        return selectAudio(pool, prefs.copy(remembered = null))
    }

    /** Sort subtitle choices for the panel: preferred languages first, then by name. */
    fun sortForDisplay(tracks: List<TextTrack>, preferred: List<String>): List<TextTrack> {
        val p = preferred.map { Languages.normalise(it) }
        return tracks.sortedWith(
            compareBy<TextTrack> { t -> p.indexOf(t.language?.let { Languages.normalise(it) } ?: "").let { if (it < 0) Int.MAX_VALUE else it } }
                .thenBy { Languages.name(it.language ?: "zzz") }
                .thenBy { it.source }
                .thenBy { it.id },
        )
    }
}
