package com.vyrk.core.anime

import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

/**
 * AniList (https://anilist.co) on the Anime tab, without an account: its public GraphQL API
 * (`POST https://graphql.anilist.co`, no key) answers the tab's rows, several in one request: four
 * lists, then a row per genre (and a few popular tags). Nothing plays from AniList: titles open
 * through your add-ons by Kitsu ID ([AniListKitsu]), like the rest of the Anime tab.
 */
object AniList {
    const val ENDPOINT = "https://graphql.anilist.co"
    const val PER_ROW = 30
    const val PER_GENRE = 20

    enum class Section(val key: String) { TRENDING("trending"), SEASON("season"), POPULAR("popular"), TOP("top") }

    /** A genre, or one of the tags offered with them (AniList keeps Isekai and the demographics as tags). */
    data class Filter(val name: String, val tag: Boolean = false) {
        /** For row keys, focus labels and GraphQL aliases: "slice_of_life", "sci_fi". */
        val slug: String get() = name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
    }

    /** A row: one of the four lists, or a genre with what's popular in it now. Its key is its GraphQL alias. */
    sealed interface Shelf {
        val key: String
        data class Main(val section: Section) : Shelf { override val key: String get() = section.key }
        data class Genre(val filter: Filter) : Shelf { override val key: String get() = "genre_" + filter.slug }
    }

    val main: List<Shelf.Main> = Section.entries.map { Shelf.Main(it) }

    /** AniList's genres without its adult ones (Hentai, Ecchi), then the tags people browse by most. */
    val filters: List<Filter> = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
        "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
    ).map { Filter(it) } + listOf("Isekai", "Shounen", "Seinen", "Shoujo").map { Filter(it, tag = true) }

    /** One AniList title, as a row needs it. */
    data class Media(
        val id: Int,
        val malId: Int?,
        val title: String,
        /** TV, TV_SHORT, MOVIE, SPECIAL, OVA, ONA or MUSIC; null for some announced titles. */
        val format: String?,
        val year: Int?,
        val genres: List<String>,
        val poster: String?,
        val description: String?,
    )

    /** The anime season [today] falls in: Winter is January to March, then Spring, Summer and Fall. */
    fun season(today: LocalDate): Pair<String, Int> =
        (when (today.monthValue) { in 1..3 -> "WINTER"; in 4..6 -> "SPRING"; in 7..9 -> "SUMMER"; else -> "FALL" }) to today.year

    private const val FIELDS = "id idMal format seasonYear genres title { romaji english } coverImage { extraLarge large } description(asHtml: false)"

    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun page(shelf: Shelf): String {
        val (perPage, args) = when (shelf) {
            is Shelf.Main -> "\$n" to when (shelf.section) {
                Section.TRENDING -> "sort: TRENDING_DESC"
                Section.SEASON -> "season: \$season, seasonYear: \$year, sort: POPULARITY_DESC"
                Section.POPULAR -> "sort: POPULARITY_DESC"
                Section.TOP -> "sort: SCORE_DESC"
            }
            // What's popular in the genre now: trending first, then all-time popularity for the rest.
            is Shelf.Genre -> "\$g" to "${if (shelf.filter.tag) "tag_in" else "genre_in"}: [${quoted(shelf.filter.name)}], sort: [TRENDING_DESC, POPULARITY_DESC]"
        }
        return "${shelf.key}: Page(perPage: $perPage) { media(type: ANIME, isAdult: false, $args) { $FIELDS } }"
    }

    /** Only the variables these rows use are declared (GraphQL refuses unused ones) and sent. */
    private fun variables(shelves: List<Shelf>, today: LocalDate): Map<String, Pair<String, Any>> = buildMap {
        if (shelves.any { it is Shelf.Main }) put("n", "Int" to PER_ROW)
        if (shelves.any { it is Shelf.Genre }) put("g", "Int" to PER_GENRE)
        if (Shelf.Main(Section.SEASON) in shelves) {
            val (season, year) = season(today)
            put("season", "MediaSeason" to season)
            put("year", "Int" to year)
        }
    }

    /** One request for [shelves], each answered under its own alias. */
    fun query(shelves: List<Shelf>, today: LocalDate): String {
        val vars = variables(shelves, today)
        val decl = if (vars.isEmpty()) "" else vars.entries.joinToString(", ", "(", ") ") { (k, v) -> "\$$k: ${v.first}" }
        return "query $decl{ " + shelves.joinToString(" ") { page(it) } + " }"
    }

    fun requestBody(shelves: List<Shelf>, today: LocalDate): String = buildJsonObject {
        put("query", query(shelves, today))
        putJsonObject("variables") {
            variables(shelves, today).forEach { (k, v) ->
                when (val value = v.second) { is Int -> put(k, value); else -> put(k, value.toString()) }
            }
        }
    }.toString()

    /** The rows of an answer, or null when it carries errors instead of data. */
    fun parse(body: String, shelves: List<Shelf>): Map<Shelf, List<Media>>? {
        val root = runCatching { ProtocolJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: return null
        if ((root["errors"] as? JsonArray)?.isNotEmpty() == true && shelves.none { data[it.key] is JsonObject }) return null
        return shelves.associateWith { s ->
            ((data[s.key] as? JsonObject)?.get("media") as? JsonArray).orEmpty().mapNotNull { media(it) }.distinctBy { it.id }
        }
    }

    /** AniList's own message for a failed request ("Too Many Requests", a GraphQL error), if it gave one. */
    fun errorMessage(body: String): String? = runCatching {
        val errors = (ProtocolJson.parseToJsonElement(body) as JsonObject)["errors"] as JsonArray
        ((errors.first() as JsonObject)["message"] as JsonPrimitive).contentOrNull
    }.getOrNull()

    private fun media(e: JsonElement): Media? {
        val o = e as? JsonObject ?: return null
        val id = o.int("id") ?: return null
        val title = o["title"] as? JsonObject
        val name = title?.string("english") ?: title?.string("romaji") ?: return null
        val cover = o["coverImage"] as? JsonObject
        return Media(
            id = id, malId = o.int("idMal"), title = name, format = o.string("format"), year = o.int("seasonYear"),
            genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            poster = cover?.string("extraLarge") ?: cover?.string("large"),
            description = plainText(o.string("description")),
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private val entities = mapOf("&amp;" to "&", "&quot;" to "\"", "&#039;" to "'", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ")

    /** AniList descriptions carry `<br>` and `<i>` even as plain text, and a "(Source: …)" credit at the end. */
    fun plainText(html: String?): String? {
        if (html.isNullOrBlank()) return null
        var s = html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
        entities.forEach { (k, v) -> s = s.replace(k, v) }
        s = s.replace(Regex("\\n{3,}"), "\n\n").replace(Regex("[ \\t]+\\n"), "\n").trim()
        return s.ifBlank { null }
    }

    /** How the title is opened: a film or a series, the type Kitsu-based add-ons answer for it. */
    fun type(m: Media): String = if (m.format == "MOVIE") "movie" else "series"

    /** The poster for a row: the title as your add-ons know it (`kitsu:ID`), drawn from AniList's data. */
    fun preview(m: Media, kitsuId: Int): MetaPreview = MetaPreview(
        id = "kitsu:$kitsuId", type = type(m), name = m.title, poster = m.poster,
        description = m.description, releaseInfo = m.year?.toString(), genres = m.genres,
    )

    /** A row's posters: the titles with a Kitsu ID that one of your add-ons opens ([opens] by type and ID), in AniList's order. */
    fun previews(media: List<Media>, ids: AniListKitsu, opens: (type: String, id: String) -> Boolean): List<MetaPreview> =
        media.mapNotNull { m -> ids.kitsu(m.id)?.let { preview(m, it) }?.takeIf { opens(it.type, it.id) } }.distinctBy { it.id }

    /**
     * IDs to ask an add-on for a title newer than the snapshot: AniList's own, then MyAnimeList's.
     * Add-ons that read them (Anime Kitsu does) answer with the title's `kitsu:` entry.
     */
    fun lookupIds(m: Media): List<String> = listOfNotNull("anilist:${m.id}", m.malId?.let { "mal:$it" })

    /** Titles of [rows] still without a Kitsu ID, in row order, at most [limit]. */
    fun unmapped(rows: Map<Shelf, List<Media>>, ids: AniListKitsu, limit: Int): List<Media> =
        rows.values.flatten().distinctBy { it.id }
            .filter { ids.kitsu(it.id) == null && !ids.isKnownMiss(it.id) }.take(limit)
}

/**
 * Rows from AniList, each kept for half an hour; only the rows not kept are asked for, in one
 * request, one request at a time (AniList limits the rate).
 */
class AniListClient(private val http: HttpTransport, private val now: () -> Long = System::currentTimeMillis) {
    sealed interface Result {
        data class Ok(val rows: Map<AniList.Shelf, List<AniList.Media>>) : Result
        /** [code] is the HTTP status, or 0 when AniList could not be reached. */
        data class Failed(val code: Int, val reason: String) : Result
    }

    private val mutex = Mutex()
    private val cache = HashMap<AniList.Shelf, Pair<Long, List<AniList.Media>>>()

    suspend fun rows(shelves: List<AniList.Shelf>, today: LocalDate = LocalDate.now()): Result = mutex.withLock {
        val fresh = shelves.mapNotNull { s -> cache[s]?.takeIf { now() - it.first < FRESH_MS }?.let { s to it.second } }.toMap()
        val ask = shelves.filter { it !in fresh }
        if (ask.isEmpty()) return Result.Ok(fresh)
        val r = try {
            http.post(AniList.ENDPOINT, AniList.requestBody(ask, today), mapOf("Accept" to "application/json"), 12_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Failed(0, e.message ?: e.javaClass.simpleName)
        }
        val rows = if (r.isSuccess) AniList.parse(r.body, ask) else null
        if (rows == null) return Result.Failed(r.code, AniList.errorMessage(r.body) ?: "HTTP ${r.code}")
        val t = now()
        rows.forEach { (s, media) -> cache[s] = t to media }
        Result.Ok(shelves.associateWith { fresh[it] ?: rows[it].orEmpty() })
    }

    companion object {
        const val FRESH_MS = 30L * 60 * 1000
    }
}
