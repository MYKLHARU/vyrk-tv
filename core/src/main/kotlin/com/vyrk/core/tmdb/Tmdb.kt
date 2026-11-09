package com.vyrk.core.tmdb

import com.vyrk.core.addon.AddonUrls
import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Someone in a title's cast or crew. */
data class Credit(
    val personId: Long,
    val name: String,
    val role: String?,
    val department: String?,
    val profilePath: String?,
    val order: Int = Int.MAX_VALUE,
)

data class Credits(val cast: List<Credit>, val crew: List<Credit>) {
    /** Directors, writers, creators and composers first; deduplicated by person. */
    val keyCrew: List<Credit>
        get() {
            val rank = listOf("Director", "Creator", "Screenplay", "Writer", "Novel", "Story", "Original Music Composer", "Producer", "Director of Photography")
            return crew.filter { it.role in rank }
                .sortedWith(compareBy<Credit> { rank.indexOf(it.role) }.thenBy { it.name })
                .distinctBy { it.personId }
        }
}

data class Person(
    val id: Long,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val knownFor: String?,
    val profilePath: String?,
)

/** A film or show someone worked on. [type] is "movie" or "series" (Stremio's names). */
data class PersonCredit(
    val tmdbId: Long,
    val type: String,
    val title: String,
    val role: String?,
    val department: String,
    val posterPath: String?,
    val year: String?,
    val popularity: Double,
    val voteCount: Int,
)

/** One image from `/movie/{id}/images` or `/tv/{id}/images`. [language] is ISO 639-1, or null for none. */
data class TmdbImage(
    val path: String,
    val language: String?,
    val voteAverage: Double,
    val voteCount: Int,
    val width: Int,
)

/** Artwork chosen for a title, as full image URLs; each is null when TMDB has none. */
data class TmdbArtwork(val logo: String?, val backdrop: String?, val poster: String?)

/**
 * TMDB v3 API (https://developer.themoviedb.org/reference). Requests carry either a v3 API key
 * (`api_key` query) or a v4 read access token (`Authorization: Bearer`). The key comes from the
 * build (`vyrk.tmdbApiKey` / `TMDB_API_KEY`); it is never committed.
 */
class TmdbClient(
    private val http: HttpTransport,
    rawKey: String,
    /** Told about every failed request: the API path and a reason, never the key or the query. */
    private val onFailed: ((path: String, reason: String) -> Unit)? = null,
) {
    private val key = normalizeKey(rawKey)
    private val bearer = key.startsWith("eyJ")

    val enabled: Boolean get() = key.isNotBlank()

    /** Exception text without anything that could carry the key (URLs, query strings). */
    private fun reason(e: Throwable): String =
        e.javaClass.simpleName + (e.message?.replace(Regex("""https?://\S+|api_key=\S+"""), "<url>")?.take(120)?.let { ": $it" } ?: "")

    private fun url(path: String, query: String = ""): String {
        val q = buildList {
            if (query.isNotEmpty()) add(query)
            if (!bearer) add("api_key=${AddonUrls.encodeComponent(key)}")
        }.joinToString("&")
        return "$BASE$path" + if (q.isNotEmpty()) "?$q" else ""
    }

    private suspend fun get(path: String, query: String = ""): JsonObject? {
        if (!enabled) return null
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key", "Accept" to "application/json") else mapOf("Accept" to "application/json")
        val res = try {
            http.get(url(path, query), headers, 12_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed?.invoke(path, reason(e)); return null
        }
        if (!res.isSuccess) { onFailed?.invoke(path, "HTTP ${res.code}"); return null }
        return try {
            ProtocolJson.parseToJsonElement(res.body) as JsonObject
        } catch (e: Exception) {
            onFailed?.invoke(path, "unreadable answer: ${reason(e)}"); null
        }
    }

    /** TMDB id and media type ("movie" or "tv") for an IMDb id. */
    suspend fun findByImdb(imdbId: String, preferTv: Boolean): Pair<Long, String>? {
        val o = get("/find/$imdbId", "external_source=imdb_id") ?: return null
        return parseFind(o, preferTv)
    }

    suspend fun credits(tmdbId: Long, mediaType: String): Credits? {
        val path = if (mediaType == "tv") "/tv/$tmdbId/aggregate_credits" else "/movie/$tmdbId/credits"
        return get(path)?.let { parseCredits(it) }
    }

    suspend fun person(id: Long): Person? = get("/person/$id")?.let { parsePerson(it) }

    suspend fun personCredits(id: Long): List<PersonCredit> = get("/person/$id/combined_credits")?.let { parsePersonCredits(it) }.orEmpty()

    private val languageCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun originalLanguage(metaId: String, type: String): String? {
        if (!enabled) return null
        val cacheKey = "$type:$metaId"
        languageCache[cacheKey]?.let { return it }
        val target = when {
            metaId.startsWith("tt") -> findByImdb(metaId.substringBefore(':'), type == "series") ?: return null
            metaId.startsWith("tmdb:") -> (metaId.removePrefix("tmdb:").substringBefore(':').toLongOrNull() ?: return null) to (if (type == "series") "tv" else "movie")
            else -> return null
        }
        val language = (get("/${target.second}/${target.first}")?.get("original_language") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        languageCache[cacheKey] = language
        return language
    }

    private val artworkCache = java.util.concurrent.ConcurrentHashMap<String, TmdbArtwork>()

    /**
     * Logo, backdrop and poster for a Stremio meta id ("tt…" via /find, or "tmdb:…"), English
     * first (`include_image_language=en,null`). Answers are cached for the session, including
     * "TMDB has none"; failed requests are not, so they are tried again later.
     */
    suspend fun artwork(metaId: String, type: String, backdropSize: String = "w1280"): TmdbArtwork? {
        if (!enabled) return null
        val cacheKey = "$type:$metaId:$backdropSize"
        artworkCache[cacheKey]?.let { return it }
        val base = metaId.substringBefore(':')
        val target: Pair<Long, String> = when {
            base.startsWith("tt") -> findByImdb(base, preferTv = type == "series") ?: return null
            metaId.startsWith("tmdb:") -> (metaId.removePrefix("tmdb:").substringBefore(':').toLongOrNull() ?: return null) to (if (type == "series") "tv" else "movie")
            else -> return null
        }
        val o = get("/${target.second}/${target.first}/images", "include_image_language=en,null") ?: return null
        val art = parseArtwork(o, backdropSize)
        artworkCache[cacheKey] = art
        return art
    }

    /** IMDb id of a film or show, so VYRK can open it through the user's add-ons. */
    suspend fun imdbId(tmdbId: Long, type: String): String? {
        val path = if (type == "series") "/tv/$tmdbId/external_ids" else "/movie/$tmdbId/external_ids"
        return get(path)?.let { (it["imdb_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.startsWith("tt") } }
    }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"

        private val readToken = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
        private val v3Key = Regex("""(?<![0-9A-Fa-f])[0-9A-Fa-f]{32}(?![0-9A-Fa-f])""")

        /**
         * The key as TMDB expects it. A configured value can carry extra characters: a byte-order
         * mark (Windows PowerShell 5.1 adds one when it pipes text into `gh secret set`), spaces,
         * quotes or a label. TMDB answered 401 to every request with the BOM in front of the key.
         * A v4 read token or a 32-character v3 key inside the value is used as is; anything else
         * is trimmed.
         */
        fun normalizeKey(raw: String): String {
            val s = raw.replace("﻿", "")
            return readToken.find(s)?.value ?: v3Key.find(s)?.value ?: s.trim()
        }
        const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."

        fun image(path: String?, size: String = "w185"): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

        private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        fun parseImages(o: JsonObject, key: String): List<TmdbImage> = o.arr(key).mapNotNull { i ->
            TmdbImage(
                path = i.s("file_path") ?: return@mapNotNull null,
                language = i.s("iso_639_1"),
                voteAverage = (i["vote_average"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                voteCount = (i["vote_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                width = (i["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }

        private fun best(images: List<TmdbImage>, languageOrder: List<String?>): TmdbImage? =
            images.sortedWith(
                compareBy<TmdbImage> { languageOrder.indexOf(it.language).let { r -> if (r < 0) languageOrder.size else r } }
                    .thenByDescending { it.voteAverage }.thenByDescending { it.voteCount }.thenByDescending { it.width },
            ).firstOrNull()

        /** English logos first, then logos with no language; SVG files are skipped (not decodable here). */
        fun pickLogo(logos: List<TmdbImage>): TmdbImage? = best(logos.filterNot { it.path.endsWith(".svg", ignoreCase = true) }, listOf("en", null))

        /** Backdrops without text first (a logo goes over them), then English ones. */
        fun pickBackdrop(backdrops: List<TmdbImage>): TmdbImage? = best(backdrops, listOf(null, "en"))

        fun pickPoster(posters: List<TmdbImage>): TmdbImage? = best(posters, listOf("en", null))

        /** Logo at original size, backdrop at [backdropSize], poster at w500. */
        fun parseArtwork(o: JsonObject, backdropSize: String): TmdbArtwork = TmdbArtwork(
            logo = image(pickLogo(parseImages(o, "logos"))?.path, "original"),
            backdrop = image(pickBackdrop(parseImages(o, "backdrops"))?.path, backdropSize),
            poster = image(pickPoster(parseImages(o, "posters"))?.path, "w500"),
        )

        fun parseFind(o: JsonObject, preferTv: Boolean): Pair<Long, String>? {
            val movie = o.arr("movie_results").firstOrNull()?.l("id")?.let { it to "movie" }
            val tv = o.arr("tv_results").firstOrNull()?.l("id")?.let { it to "tv" }
            return if (preferTv) tv ?: movie else movie ?: tv
        }

        /** Handles both /movie/{id}/credits and /tv/{id}/aggregate_credits (roles/jobs arrays). */
        fun parseCredits(o: JsonObject): Credits {
            val cast = o.arr("cast").mapNotNull { c ->
                val id = c.l("id") ?: return@mapNotNull null
                val role = c.s("character") ?: c.arr("roles").firstOrNull()?.s("character")
                Credit(id, c.s("name") ?: return@mapNotNull null, role, "Acting", c.s("profile_path"), (c["order"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE)
            }.sortedBy { it.order }
            val crew = o.arr("crew").flatMap { c ->
                val id = c.l("id") ?: return@flatMap emptyList()
                val name = c.s("name") ?: return@flatMap emptyList()
                val jobs = c.s("job")?.let { listOf(it) } ?: c.arr("jobs").mapNotNull { it.s("job") }
                jobs.map { Credit(id, name, it, c.s("department"), c.s("profile_path")) }
            }
            return Credits(cast, crew)
        }

        fun parsePerson(o: JsonObject): Person? = Person(
            id = o.l("id") ?: return null,
            name = o.s("name") ?: return null,
            biography = o.s("biography"),
            birthday = o.s("birthday"),
            deathday = o.s("deathday"),
            placeOfBirth = o.s("place_of_birth"),
            knownFor = o.s("known_for_department"),
            profilePath = o.s("profile_path"),
        )

        /** Films and shows, most notable first, one entry per title (acting roles win over crew jobs). */
        fun parsePersonCredits(o: JsonObject): List<PersonCredit> {
            fun item(c: JsonObject, dept: String, role: String?): PersonCredit? {
                val type = when (c.s("media_type")) { "movie" -> "movie"; "tv" -> "series"; else -> return null }
                val title = c.s("title") ?: c.s("name") ?: return null
                val date = c.s("release_date") ?: c.s("first_air_date")
                return PersonCredit(
                    tmdbId = c.l("id") ?: return null, type = type, title = title, role = role, department = dept,
                    posterPath = c.s("poster_path"), year = date?.take(4),
                    popularity = (c["popularity"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    voteCount = (c["vote_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                )
            }
            val acting = o.arr("cast").mapNotNull { item(it, "Acting", it.s("character")) }
                // Talk shows and award ceremonies list people as "Self"; they crowd out real work.
                .filterNot { it.type == "series" && it.role?.contains("Self", ignoreCase = true) == true }
            val crew = o.arr("crew").mapNotNull { item(it, it.s("department") ?: "Crew", it.s("job")) }
            return (acting + crew)
                .distinctBy { it.type + it.tmdbId }
                .sortedWith(compareByDescending<PersonCredit> { it.voteCount }.thenByDescending { it.popularity }.thenBy { it.title })
        }
    }
}
