package com.vyrk.tv.data

import com.vyrk.core.addon.AddonUrls
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.art.Artwork
import com.vyrk.core.catalog.CardFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Media-card facts that need a request, kept for the session (misses too) and asked four at a
 * time:
 * - the age certificate for the region, from TMDB (when the build has a key), by the title's
 *   IMDb or TMDB ID (anime by VYRK's bundled anime IDs);
 * - IMDb's score for titles whose add-on doesn't key them by IMDb ID, from Cinemeta (when it's
 *   installed) by the IMDb ID found the same way. An add-on's own `imdbRating` is trusted only for
 *   IMDb-keyed titles (CardFacts.addonRatingIsImdb).
 *
 * Rotten Tomatoes' Tomatometer has no source here yet: none of the services VYRK uses publishes
 * it. [Remote.rottenTomatoes] is where one plugs in (see docs/FEATURES.md, Media cards).
 */
class CardFactsSource(private val graph: AppGraph) {
    data class Remote(val certificate: String?, val imdb: Double?, val rottenTomatoes: Int? = null)

    private val cache = ConcurrentHashMap<String, Remote>()
    /** IMDb ID → Cinemeta's score; NaN when Cinemeta has none. */
    private val cinemeta = ConcurrentHashMap<String, Double>()
    private val gate = Semaphore(4)

    /**
     * The region certificates are for. VYRK has no region setting of its own, so it's the
     * device's (Android Settings › System › Languages & region), not the app language's.
     */
    val region: String
        get() = runCatching { android.content.res.Resources.getSystem().configuration.locales[0].country }.getOrNull().orEmpty().uppercase()

    private fun key(meta: MetaPreview, needImdb: Boolean) = "${meta.type}:${meta.id}:$needImdb:$region"

    /** What's known already, without a request. */
    fun peek(meta: MetaPreview, needImdb: Boolean): Remote? = cache[key(meta, needImdb)]

    suspend fun load(meta: MetaPreview, addons: List<Descriptor>, needImdb: Boolean): Remote = withContext(Dispatchers.IO) {
        val k = key(meta, needImdb)
        cache[k] ?: gate.withPermit {
            cache[k]?.let { return@withPermit it }
            val lookup = Artwork.lookupId(meta) ?: graph.animeArtIds.lookupId(meta.id, meta.type)
            val region = region
            val asksTmdb = lookup != null && region.isNotBlank() && graph.tmdb.enabled
            val tmdb = if (asksTmdb) graph.tmdb.facts(lookup!!, meta.type, region) else null
            val imdbId = lookup?.takeIf { it.startsWith("tt") }?.substringBefore(':') ?: tmdb?.imdbId
            val imdb = if (needImdb && imdbId != null) cinemetaScore(addons, meta.type, imdbId) else null
            val r = Remote(CardFacts.certificate(tmdb?.certification), imdb?.takeIf { !it.isNaN() })
            // A failed TMDB or Cinemeta request isn't kept, so the card asks again next time it shows.
            val settled = (!asksTmdb || tmdb != null) && (!needImdb || imdbId == null || imdb != null)
            if (settled) cache[k] = r
            r
        }
    }

    /** Cinemeta's IMDb score (NaN: it has none), or null when Cinemeta isn't installed or didn't answer. */
    private suspend fun cinemetaScore(addons: List<Descriptor>, type: String, imdbId: String): Double? {
        cinemeta[imdbId]?.let { return it }
        val addon = addons.firstOrNull { it.manifest.id == CINEMETA_ID } ?: return null
        val res = runCatching { graph.transport.get(AddonUrls.resourceUrl(addon.transportUrl, "meta", type, imdbId), emptyMap(), 10_000) }.getOrNull()
            ?: return null
        if (!res.isSuccess) return if (res.code == 404) Double.NaN.also { cinemeta[imdbId] = it } else null
        return (CardFacts.imdbFromCinemeta(res.body) ?: Double.NaN).also { cinemeta[imdbId] = it }
    }

    companion object {
        const val CINEMETA_ID = "com.linvo.cinemeta"
    }
}
