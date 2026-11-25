package com.vyrk.core.catalog

import java.util.Locale

/**
 * What a media card shows besides its artwork: the release year, IMDb's score (out of 10) and
 * Rotten Tomatoes' critics' Tomatometer (a percentage) in a row at its foot, and the age
 * certificate for the configured region in its top corner. Each value is the title's own, from
 * its own source, or null and hidden: no value is guessed or borrowed from another provider.
 * Zero is a real score and is shown.
 */
data class CardFacts(
    val year: String? = null,
    /** IMDb's rating, 0.0–10.0. */
    val imdb: Double? = null,
    /** Rotten Tomatoes' critics' Tomatometer, 0–100. */
    val rottenTomatoes: Int? = null,
    /** The age certificate for the region ("15", "PG-13", "TV-MA"). */
    val certificate: String? = null,
) {
    /** Whether the bottom row has anything to show. */
    val hasRow: Boolean get() = year != null || imdb != null || rottenTomatoes != null

    companion object {
        private val yearRe = Regex("""(?<!\d)(1[89]\d\d|2[01]\d\d)(?!\d)""")

        /** The first year in an add-on's `releaseInfo` ("2024", "2019–2023", "2019-"), or null. */
        fun year(releaseInfo: String?): String? = releaseInfo?.let { yearRe.find(it)?.value }

        /** An IMDb rating as add-ons send it ("8.2", "8,2", "7", "0.0"), or null when missing or outside 0–10. */
        fun imdb(raw: String?): Double? =
            raw?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { !it.isNaN() && it >= 0.0 && it <= 10.0 }

        /** A Tomatometer percentage, or null outside 0–100. */
        fun tomatometer(raw: Int?): Int? = raw?.takeIf { it in 0..100 }

        /** A certificate as a source gives it, trimmed; null when blank or implausibly long. */
        fun certificate(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 10 }

        /**
         * Whether an add-on's `imdbRating` is IMDb's: only for titles keyed by IMDb ID. Other add-ons
         * use the field for their own site's score: Anime Kitsu sends Kitsu's average rating there.
         */
        fun addonRatingIsImdb(metaId: String): Boolean = metaId.startsWith("tt")

        /** The IMDb score as shown: one decimal in the reader's number format ("8.2", "8,2"). */
        fun formatImdb(score: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", score)

        /** The Tomatometer as shown, in the reader's percent format ("92%", "92 %"). */
        fun formatPercent(value: Int, locale: Locale = Locale.getDefault()): String =
            java.text.NumberFormat.getPercentInstance(locale).format(value / 100.0)

        /** One spoken line for a card: "2024, IMDb 8.2 out of 10, Rotten Tomatoes 92%, rated 15". */
        fun spoken(f: CardFacts, outOf: String = "out of 10", rated: String = "rated", locale: Locale = Locale.getDefault()): String =
            listOfNotNull(
                f.year,
                f.imdb?.let { "IMDb ${formatImdb(it, locale)} $outOf" },
                f.rottenTomatoes?.let { "Rotten Tomatoes ${formatPercent(it, locale)}" },
                f.certificate?.let { "$rated $it" },
            ).joinToString(", ")

        /** Cinemeta's `imdbRating` from a meta answer, read without building the whole (long) episode list. */
        fun imdbFromCinemeta(body: String): Double? =
            Regex(""""imdbRating"\s*:\s*"?([0-9]+(?:[.,][0-9]+)?)"?""").find(body)?.groupValues?.get(1)?.let(::imdb)
    }
}
