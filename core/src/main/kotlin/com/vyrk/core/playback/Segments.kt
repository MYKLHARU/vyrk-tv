package com.vyrk.core.playback

import com.vyrk.core.addon.ProtocolJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

enum class SegmentKind { INTRO, RECAP, OUTRO, POST_CREDITS }

data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long, val confidence: Double = 1.0) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * IntroDB community timings (https://introdb.app/docs, verified 2026-11-04).
 * `GET https://api.introdb.app/segments?imdb_id=tt…&season=1&episode=2` (or `&is_movie=true`)
 * returns `intro`, `recap`, `outro` and `post_credits`, each null or
 * `{start_ms, end_ms, confidence, …}`. Reads are anonymous and rate limited; nothing personal is sent.
 */
object IntroDb {
    const val BASE = "https://api.introdb.app/segments"
    const val MIN_CONFIDENCE = 0.8

    /** Only IMDb IDs are supported. Series need season and episode from a `tt…:S:E` video ID. */
    fun url(type: String, metaId: String, videoId: String?): String? {
        if (!metaId.startsWith("tt")) return null
        val imdb = metaId.substringBefore(':')
        return when (type) {
            "movie" -> "$BASE?imdb_id=$imdb&is_movie=true"
            "series" -> {
                val parts = (videoId ?: return null).split(':')
                if (parts.size < 3 || parts[0] != imdb) return null
                val s = parts[1].toIntOrNull() ?: return null
                val e = parts[2].toIntOrNull() ?: return null
                "$BASE?imdb_id=$imdb&season=$s&episode=$e"
            }
            else -> null
        }
    }

    fun parse(body: String): List<Segment> {
        val root = runCatching { ProtocolJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        return listOf("intro" to SegmentKind.INTRO, "recap" to SegmentKind.RECAP, "outro" to SegmentKind.OUTRO, "post_credits" to SegmentKind.POST_CREDITS)
            .mapNotNull { (key, kind) ->
                val o = root[key] as? JsonObject ?: return@mapNotNull null
                fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
                val start = (o["start_ms"] as? JsonPrimitive)?.longOrNull ?: num("start_sec")?.let { (it * 1000).toLong() }
                val end = (o["end_ms"] as? JsonPrimitive)?.longOrNull ?: num("end_sec")?.let { (it * 1000).toLong() }
                val conf = num("confidence") ?: 1.0
                if (start == null || end == null || start < 0 || end <= start || conf < MIN_CONFIDENCE) null
                else Segment(kind, start, end, conf)
            }
            .sortedBy { it.startMs }
    }

    /** True when IntroDB confirmed the title has no intro (so "nothing to skip", not "not timed yet"). */
    fun confirmedNoIntro(body: String): Boolean =
        runCatching { ((ProtocolJson.parseToJsonElement(body) as JsonObject)["no_intro"] as? JsonPrimitive)?.booleanOrNull }.getOrNull() == true
}

/**
 * Decides when to offer "Skip intro" / "Skip recap". A segment is offered while playback is inside
 * it (until 2 s before its end), once per playback; ignoring or skipping it means it isn't offered again.
 */
class SkipOffer(private val segments: List<Segment>) {
    private val done = mutableSetOf<Segment>()

    /** The segment to offer at [positionMs], or null. */
    fun at(positionMs: Long): Segment? = segments.firstOrNull { s ->
        (s.kind == SegmentKind.INTRO || s.kind == SegmentKind.RECAP) && s !in done &&
            positionMs >= s.startMs && positionMs < s.endMs - 2_000 && s.lengthMs >= 5_000
    }

    fun dismiss(s: Segment) { done += s }

    /** Where to seek when the viewer skips [s]. */
    fun target(s: Segment): Long = s.endMs

    /** Credits start, when known: Up Next appears there rather than at a guess. */
    val creditsStartMs: Long? get() = segments.firstOrNull { it.kind == SegmentKind.OUTRO }?.startMs
}
