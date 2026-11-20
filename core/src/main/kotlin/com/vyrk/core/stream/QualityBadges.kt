package com.vyrk.core.stream

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
