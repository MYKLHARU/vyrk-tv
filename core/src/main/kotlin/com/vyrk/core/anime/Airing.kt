package com.vyrk.core.anime

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.net.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One episode's air time, in epoch seconds. */
data class AiredEpisode(val number: Int, val atSec: Long)

/** A followed show's schedule: the latest aired episode in the last week, and the next one. */
data class AiringStatus(val malId: String, val anilistId: Int, val latest: AiredEpisode?, val next: AiredEpisode?)

/**
 * Airing times from AniList's public GraphQL API (https://graphql.anilist.co, no key), looked up
 * by MyAnimeList id: next episodes from `Media.nextAiringEpisode`, the last week's from `Page.airingSchedules`.
 */
class AniListAiring(private val http: HttpTransport) {
    suspend fun fetch(malIds: List<String>, nowSec: Long): List<AiringStatus> {
        val ids = malIds.mapNotNull { it.toIntOrNull() }.distinct().take(50)
        if (ids.isEmpty()) return emptyList()
        val media = post(mediaQuery(ids))?.let { parseMedia(it) } ?: return emptyList()
        if (media.isEmpty()) return emptyList()
        val recent = post(recentQuery(media.map { it.anilistId }, nowSec - WEEK_SEC, nowSec))?.let { parseRecent(it) }.orEmpty()
        return media.map { it.copy(latest = recent[it.anilistId]) }
    }

    private suspend fun post(body: String): String? {
        val r = runCatching { http.post(URL, body, mapOf("Content-Type" to "application/json", "Accept" to "application/json"), 10_000) }.getOrNull()
        return r?.takeIf { it.isSuccess }?.body
    }

    companion object {
        const val URL = "https://graphql.anilist.co"
        const val WEEK_SEC = 7L * 24 * 3600

        private fun body(query: String, ids: List<Int>, extra: Map<String, Long> = emptyMap()) = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
                extra.forEach { (k, v) -> put(k, v) }
            })
        }.toString()

        fun mediaQuery(malIds: List<Int>) = body(
            "query(\$ids:[Int]){Page(perPage:50){media(idMal_in:\$ids,type:ANIME){id idMal status nextAiringEpisode{episode airingAt}}}}", malIds)

        fun recentQuery(anilistIds: List<Int>, fromSec: Long, toSec: Long) = body(
            "query(\$ids:[Int],\$from:Int,\$to:Int){Page(perPage:50){airingSchedules(mediaId_in:\$ids,airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME_DESC){episode airingAt mediaId}}}",
            anilistIds, mapOf("from" to fromSec, "to" to toSec))

        private fun page(body: String, key: String): JsonArray? = runCatching {
            (((ProtocolJson.parseToJsonElement(body) as JsonObject)["data"] as JsonObject)["Page"] as JsonObject)[key] as JsonArray
        }.getOrNull()

        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

        fun parseMedia(body: String): List<AiringStatus> = page(body, "media").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val mal = o.int("idMal") ?: return@mapNotNull null
            val next = (o["nextAiringEpisode"] as? JsonObject)?.let { n ->
                val ep = n.int("episode"); val at = n.long("airingAt")
                if (ep != null && at != null) AiredEpisode(ep, at) else null
            }
            AiringStatus(mal.toString(), id, null, next)
        }

        /** The newest aired episode per AniList id. */
        fun parseRecent(body: String): Map<Int, AiredEpisode> = page(body, "airingSchedules").orEmpty()
            .mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val m = o.int("mediaId"); val ep = o.int("episode"); val at = o.long("airingAt")
                if (m != null && ep != null && at != null) m to AiredEpisode(ep, at) else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, eps) -> eps.maxBy { it.atSec } }
    }
}

/** Which anime you follow, and what counts as a new or upcoming episode. */
object Airing {
    const val FOLLOW_DAYS = 60L
    private const val DAY_MS = 86_400_000L

    /** Kitsu and MAL titles in your library, or watched in the last [FOLLOW_DAYS] days; newest activity first. */
    fun followed(items: Collection<LibraryItem>, nowMs: Long): List<LibraryItem> = items
        .filter { !it.removed || it.temp }
        .filter { anime(it.id) && (it.inLibrary || (it.state.lastWatchedMs ?: 0) > nowMs - FOLLOW_DAYS * DAY_MS) }
        .sortedByDescending { maxOf(it.state.lastWatchedMs ?: 0, it.mtimeMs) }
        .distinctBy { it.id }
        .take(50)

    private fun anime(id: String): Boolean {
        val p = id.split(':')
        return p.size == 2 && (p[0] == "kitsu" || p[0] == "mal") && p[1].isNotEmpty() && p[1].all(Char::isDigit)
    }

    /** The MAL id when the title is a `mal:` one; Kitsu titles need [AnimeIds]. */
    fun malIdOf(id: String): String? = id.takeIf { it.startsWith("mal:") }?.substringAfter(':')
    fun kitsuIdOf(id: String): String? = id.takeIf { it.startsWith("kitsu:") }?.substringAfter(':')

    /** The episode last played, from the library's `kitsu:ID:EP` video id. */
    fun lastEpisode(item: LibraryItem): Int? = item.state.videoId?.split(':')?.takeIf { it.size >= 3 && "${it[0]}:${it[1]}" == item.id }?.last()?.toIntOrNull()

    /** Aired in the last week and not yet reached: you've played an earlier episode, or none. */
    fun isNew(item: LibraryItem, status: AiringStatus, nowMs: Long): Boolean {
        val l = status.latest ?: return false
        val at = l.atSec * 1000
        if (at > nowMs || at < nowMs - 7 * DAY_MS) return false
        return (lastEpisode(item) ?: 0) < l.number
    }

    /** Airs in the next week. */
    fun upcoming(status: AiringStatus, nowMs: Long): Boolean {
        val at = (status.next ?: return false).atSec * 1000
        return at in nowMs..(nowMs + 7 * DAY_MS)
    }

    fun episodeId(itemId: String, episode: Int) = "$itemId:$episode"
}
