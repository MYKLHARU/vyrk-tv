package com.vyrk.core.anime

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpTransport
import com.vyrk.core.playback.Segment
import com.vyrk.core.playback.SegmentKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap

/** An anime episode as VYRK knows it: a MyAnimeList id (or a Kitsu id to look it up) and the episode number. */
data class AnimeEpisode(val malId: String?, val kitsuId: String?, val episode: Int)

/**
 * MyAnimeList ids for Kitsu ids, from Kitsu's own mappings
 * (`GET https://kitsu.app/api/edge/anime/{id}/mappings`, entries with `externalSite` "myanimelist/anime").
 * Cached for the app's lifetime; misses are cached too.
 */
class AnimeIds(private val http: HttpTransport) {
    private val malByKitsu = ConcurrentHashMap<String, String>()

    suspend fun mal(kitsuId: String): String? {
        malByKitsu[kitsuId]?.let { return it.ifEmpty { null } }
        val r = runCatching { http.get("https://kitsu.app/api/edge/anime/$kitsuId/mappings?page%5Blimit%5D=20", mapOf("Accept" to "application/vnd.api+json"), 8_000) }.getOrNull()
            ?: return null
        if (!r.isSuccess) { if (r.code == 404) malByKitsu[kitsuId] = ""; return null }
        val mal = parseKitsuMappings(r.body)
        malByKitsu[kitsuId] = mal.orEmpty()
        return mal
    }

    suspend fun mal(ep: AnimeEpisode): String? = ep.malId ?: ep.kitsuId?.let { mal(it) }

    companion object {
        fun parseKitsuMappings(body: String): String? {
            val data = runCatching { (ProtocolJson.parseToJsonElement(body) as JsonObject)["data"] as JsonArray }.getOrNull() ?: return null
            return data.firstNotNullOfOrNull { e ->
                val a = (e as? JsonObject)?.get("attributes") as? JsonObject ?: return@firstNotNullOfOrNull null
                val site = (a["externalSite"] as? JsonPrimitive)?.contentOrNull
                if (site == "myanimelist/anime") (a["externalId"] as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.all { it.isDigit() } } else null
            }
        }
    }
}

/**
 * AniSkip community timings for anime openings and endings (https://api.aniskip.com/v2, by MAL id and
 * episode number): `GET /v2/skip-times/{malId}/{episode}?types[]=op&…&episodeLength=0` answers
 * `{found, results: [{interval: {startTime, endTime}, skipType, episodeLength}]}` in seconds.
 */
object AniSkip {
    const val BASE = "https://api.aniskip.com/v2/skip-times"

    /** The episode for VYRK's ids: `kitsu:ID` / `mal:ID` titles with `…:ID:EP` episodes (a film is episode 1). */
    fun episode(metaId: String, videoId: String?, malHint: String? = null): AnimeEpisode? {
        val parts = metaId.split(':')
        val prefix = parts[0]
        val id = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val vp = videoId?.split(':')
        val ep = when {
            vp == null || videoId == metaId -> 1
            vp.size >= 3 && vp[0] == prefix && vp[1] == id -> vp.last().toIntOrNull()
            else -> null
        } ?: return null
        if (ep < 1) return null
        val hint = malHint?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        return when (prefix) {
            "mal" -> AnimeEpisode(id, null, ep)
            "kitsu" -> AnimeEpisode(hint, id, ep)
            else -> null
        }
    }

    fun url(malId: String, episode: Int): String {
        val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("&") { "types%5B%5D=$it" }
        return "$BASE/$malId/$episode?$types&episodeLength=0"
    }

    /**
     * Segments from an AniSkip answer. With [fileDurationMs] known, timings made for a cut more than
     * 20 s longer or shorter are dropped (they'd skip the wrong part).
     */
    fun parse(body: String, fileDurationMs: Long = 0): List<Segment> {
        val root = runCatching { ProtocolJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val kind = when ((o["skipType"] as? JsonPrimitive)?.contentOrNull) {
                "op", "mixed-op" -> SegmentKind.INTRO
                "ed", "mixed-ed" -> SegmentKind.OUTRO
                "recap" -> SegmentKind.RECAP
                else -> return@mapNotNull null
            }
            val iv = o["interval"] as? JsonObject ?: return@mapNotNull null
            val start = (iv["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (iv["endTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val length = (o["episodeLength"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            if (start < 0 || end <= start) return@mapNotNull null
            if (fileDurationMs > 0 && length > 0 && kotlin.math.abs(length * 1000 - fileDurationMs) > 20_000) return@mapNotNull null
            Segment(kind, (start * 1000).toLong(), (end * 1000).toLong(), anime = true)
        }.groupBy { it.kind }.map { (_, list) -> list.first() }.sortedBy { it.startMs }
    }
}
