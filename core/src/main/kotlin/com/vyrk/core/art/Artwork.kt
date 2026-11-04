package com.vyrk.core.art

/**
 * Artwork URL rules for a TV screen: Cinemeta's images by IMDb id as a fallback when an add-on
 * gives none (or a broken one), and larger copies of known CDN images for full-screen use.
 * Patterns are compiled once: Android's regex compiler is slow on TV processors.
 */
object Artwork {
    private const val METAHUB = "https://images.metahub.space"
    private val imdb = Regex("""tt\d{5,10}""")
    private val tmdbSized = Regex("""^(https?://image\.tmdb\.org/t/p/)(w\d+|h\d+|original)(/.+)$""")
    private val metahubSized = Regex("""^(https?://images\.metahub\.space/(?:logo|background|poster)/)(small|medium|large)(/.+)$""")

    /** The IMDb id inside a Stremio id ("tt0944947", "tt0944947:1:2"), or null. */
    fun imdbId(id: String): String? = id.substringBefore(':').takeIf { imdb.matches(it) }

    /** Cinemeta's poster for an IMDb id (the same image service Stremio uses), else null. */
    fun metahubPoster(id: String): String? = imdbId(id)?.let { "$METAHUB/poster/medium/$it/img" }

    /** Cinemeta's transparent title logo for an IMDb id; answers 404 when there is none. */
    fun metahubLogo(id: String): String? = imdbId(id)?.let { "$METAHUB/logo/medium/$it/img" }

    /** Cinemeta's 1920×1080 backdrop for an IMDb id. */
    fun metahubBackground(id: String): String? = imdbId(id)?.let { "$METAHUB/background/medium/$it/img" }

    /**
     * A copy sharp enough to fill the hero: TMDB backdrops at [tmdbSize] ("w1280" or "original"),
     * metahub at medium (1920 wide) or larger. Other URLs are returned unchanged.
     */
    fun forBackdrop(url: String?, tmdbSize: String): String? = resize(url, tmdbSize, "medium")

    /** Logos are drawn up to about 1100 px wide, so TMDB logos come at their original size. */
    fun forLogo(url: String?): String? = resize(url, "original", "medium")

    private fun resize(url: String?, tmdbSize: String, metahubMin: String): String? {
        if (url.isNullOrBlank()) return null
        tmdbSized.matchEntire(url)?.let { m -> return m.groupValues[1] + tmdbSize + m.groupValues[3] }
        metahubSized.matchEntire(url)?.let { m ->
            if (m.groupValues[2] == "small") return m.groupValues[1] + metahubMin + m.groupValues[3]
        }
        return url
    }
}
