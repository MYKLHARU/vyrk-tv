package com.vyrk.core.stream

import com.vyrk.core.lang.Languages

/** A picture or sound badge: [premium] ones (4K, Dolby Vision, Atmos) are drawn in gold. */
enum class QualityBadge(val label: String, val premium: Boolean) {
    UHD("4K", true), FHD("HD", false),
    DOLBY_VISION("Dolby Vision", true), HDR10_PLUS("HDR10+", false), HDR("HDR", false),
    ATMOS("Atmos", true), DTS_X("DTS:X", true), SURROUND_71("7.1", false), SURROUND_51("5.1", false),
}

object QualityBadges {
    /** At most one each of resolution, HDR and immersive sound, then channels: "4K · Dolby Vision · Atmos · 7.1". */
    fun of(i: StreamInfo): List<QualityBadge> = buildList {
        val res = i.resolution ?: 0
        when {
            res >= 2160 -> add(QualityBadge.UHD)
            res >= 1080 -> add(QualityBadge.FHD)
        }
        when {
            Hdr.DOLBY_VISION in i.hdr -> add(QualityBadge.DOLBY_VISION)
            Hdr.HDR10_PLUS in i.hdr -> add(QualityBadge.HDR10_PLUS)
            Hdr.HDR10 in i.hdr || Hdr.HLG in i.hdr -> add(QualityBadge.HDR)
        }
        when {
            AudioFormat.ATMOS in i.audio -> add(QualityBadge.ATMOS)
            AudioFormat.DTS_X in i.audio -> add(QualityBadge.DTS_X)
        }
        when (i.channels) {
            "7.1" -> add(QualityBadge.SURROUND_71)
            "5.1" -> add(QualityBadge.SURROUND_51)
        }
    }

    /** The title's badges: those of the source VYRK would play (the best playable one). */
    fun best(ranked: List<Ranked>): List<QualityBadge> = ranked.firstOrNull { it.playable }?.let { of(it.info) }.orEmpty()
}

/**
 * What a source card shows beside its resolution block and add-on name: the picture and sound
 * badges (resolution is already in the block), the other details as one line, and languages.
 */
data class SourceSummary(val badges: List<QualityBadge>, val details: String, val languages: String, val uhd: Boolean) {
    companion object {
        fun of(i: StreamInfo): SourceSummary {
            val badges = QualityBadges.of(i).filter { it != QualityBadge.UHD && it != QualityBadge.FHD }
            // Atmos and DTS:X already have badges.
            val audio = i.audio.filterNot { it == AudioFormat.ATMOS || it == AudioFormat.DTS_X }.joinToString(" ") { it.label }
            val details = listOf(i.releaseType.label, i.codec.label, audio, sizeLabel(i.sizeBytes)).filter { it.isNotBlank() }.joinToString("  ·  ")
            return SourceSummary(
                badges = badges,
                details = details.ifEmpty { if (badges.isEmpty()) "—" else "" },
                languages = i.languages.joinToString(", ") { Languages.label(it) },
                uhd = (i.resolution ?: 0) >= 2160,
            )
        }

        /** "4.7 GB", or "" when the size is unknown. */
        fun sizeLabel(bytes: Long?): String = bytes?.let { "%.1f GB".format(it / 1e9) }.orEmpty()
    }
}
