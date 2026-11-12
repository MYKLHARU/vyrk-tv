package com.vyrk.core.art

/**
 * Artwork URL rules for a TV screen: Cinemeta's images by IMDb id as a fallback when an add-on
 * gives none (or a broken one), and larger copies of known CDN images for full-screen use.
 * Patterns are compiled once: Android's regex compiler is slow on TV processors.
 */
object Artwork {
    /** IDs can collide across movie/series catalogues; artwork belongs to the complete identity. */
    fun titleKey(type: String, id: String): String = "$type:$id"

    fun matchingMeta(meta: com.vyrk.core.addon.MetaPreview, full: com.vyrk.core.addon.MetaItem?): com.vyrk.core.addon.MetaItem? =
        full?.takeIf { it.type == meta.type && it.id == meta.id }

    /**
     * [answer] is what the add-ons returned when asked for [meta]'s own type and ID. A provider may
     * answer under its canonical ID (AIO Metadata returns `tt…` for a `kitsu:…` title): it is still
     * this title's metadata, as it is in Stremio. It is given the requested identity, so the library
     * and watched state stay under the catalogue's ID, and the canonical IMDb ID is kept for artwork.
     * Episode IDs are left as the provider sent them. Only for an answer to that exact request.
     */
    fun answerFor(meta: com.vyrk.core.addon.MetaPreview, answer: com.vyrk.core.addon.MetaItem?): com.vyrk.core.addon.MetaItem? {
        if (answer == null || (answer.type == meta.type && answer.id == meta.id)) return answer
        return answer.copy(id = meta.id, type = meta.type, imdbId = answer.imdbId ?: imdbId(answer.id))
    }

    /** Explicit provider mappings allow Kitsu/MAL titles to use artwork without changing playback IDs. */
    fun lookupId(meta: com.vyrk.core.addon.MetaPreview, full: com.vyrk.core.addon.MetaItem? = null): String? =
        imdbId(meta.id) ?: meta.imdbId?.let(::imdbId) ?: matchingMeta(meta, full)?.imdbId?.let(::imdbId)
            ?: meta.id.takeIf { tmdbId.matches(it) }

    /** Keep the catalogue image the viewer selected; full metadata adds fallbacks, not a swap. */
    fun backdrops(meta: com.vyrk.core.addon.MetaPreview, full: com.vyrk.core.addon.MetaItem?, tmdb: String?, size: String): List<String> =
        listOfNotNull(forBackdrop(meta.background, size), forBackdrop(matchingMeta(meta, full)?.background, size), tmdb, lookupId(meta, full)?.let(::metahubBackground)).distinct()

    fun logos(meta: com.vyrk.core.addon.MetaPreview, full: com.vyrk.core.addon.MetaItem?, tmdb: String?): List<String> =
        listOfNotNull(forLogo(meta.logo), forLogo(matchingMeta(meta, full)?.logo), tmdb, lookupId(meta, full)?.let(::metahubLogo)).distinct()

    private const val METAHUB = "https://images.metahub.space"
    private val imdb = Regex("""tt\d{5,10}""")
    private val tmdbId = Regex("""tmdb:\d+""")
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
