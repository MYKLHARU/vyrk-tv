package com.vyrk.core.trakt

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A Trakt sign-in in progress: the code to enter at [verificationUrl]. */
data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int, val intervalSec: Int)

/** Trakt access, kept encrypted on the device. */
@Serializable
data class TraktTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val username: String? = null)

sealed interface TokenPoll {
    data class Approved(val tokens: TraktTokens) : TokenPoll
    /** Not approved yet: ask again after the interval. */
    data object Pending : TokenPoll
    /** Asking too often: wait longer. */
    data object SlowDown : TokenPoll
    data object Denied : TokenPoll
    data object Expired : TokenPoll
    data class Failed(val code: Int) : TokenPoll
}

/** What is being watched, by IMDb id: a film, or an episode of a show. */
sealed interface TraktItem {
    data class Movie(val imdb: String) : TraktItem
    data class Episode(val showImdb: String, val season: Int, val number: Int) : TraktItem

    companion object {
        /** From VYRK's ids: "tt123" for a film, "tt123:2:5" (show, season, episode) for an episode. Others aren't on Trakt by IMDb. */
        fun of(type: String, metaId: String, videoId: String?): TraktItem? {
            val imdb = metaId.substringBefore(':').takeIf { it.startsWith("tt") } ?: return null
            if (type == "movie") return Movie(imdb)
            val parts = (videoId ?: return null).split(':')
            if (parts.size < 3 || parts[0] != imdb) return null
            val s = parts[1].toIntOrNull() ?: return null
            val e = parts[2].toIntOrNull() ?: return null
            return Episode(imdb, s, e)
        }
    }
}

/**
 * Trakt API v2 (https://trakt.docs.apiary.io): device sign-in, token refresh, scrobbling and
 * history. [clientId] and [clientSecret] come from the build; without them Trakt is off.
 */
class TraktClient(private val http: HttpTransport, private val clientId: String, private val clientSecret: String) {
    val enabled: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    private fun headers(token: String? = null) = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", clientId)
        if (token != null) put("Authorization", "Bearer $token")
    }

    private suspend fun post(path: String, body: JsonObject, token: String? = null): HttpResult? =
        runCatching { http.post("$BASE$path", body.toString(), headers(token), 15_000) }.getOrNull()

    suspend fun deviceCode(): DeviceCode? {
        val r = post("/oauth/device/code", buildJsonObject { put("client_id", clientId) }) ?: return null
        return if (r.isSuccess) parseDeviceCode(r.body) else null
    }

    suspend fun pollToken(deviceCode: String, nowMs: Long): TokenPoll {
        val r = post("/oauth/device/token", buildJsonObject {
            put("code", deviceCode); put("client_id", clientId); put("client_secret", clientSecret)
        }) ?: return TokenPoll.Failed(0)
        return parseTokenPoll(r, nowMs)
    }

    suspend fun refresh(tokens: TraktTokens, nowMs: Long): TraktTokens? {
        val r = post("/oauth/token", buildJsonObject {
            put("refresh_token", tokens.refreshToken); put("client_id", clientId); put("client_secret", clientSecret)
            put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
        }) ?: return null
        return if (r.isSuccess) parseTokens(r.body, nowMs)?.copy(username = tokens.username) else null
    }

    suspend fun revoke(token: String) {
        post("/oauth/revoke", buildJsonObject { put("token", token); put("client_id", clientId); put("client_secret", clientSecret) })
    }

    /** The signed-in user's name. */
    suspend fun username(token: String): String? {
        val r = runCatching { http.get("$BASE/users/settings", headers(token), 15_000) }.getOrNull() ?: return null
        if (!r.isSuccess) return null
        val user = (ProtocolJson.parseToJsonElement(r.body) as? JsonObject)?.get("user") as? JsonObject
        return (user?.get("username") as? JsonPrimitive)?.contentOrNull
    }

    /** Tells Trakt about playback: [action] is start, pause or stop; stop at 80% or more counts as watched. Returns the HTTP code. */
    suspend fun scrobble(action: String, item: TraktItem, progressPercent: Float, token: String): Int {
        require(action in setOf("start", "pause", "stop"))
        return post("/scrobble/$action", scrobbleBody(item, progressPercent), token)?.code ?: 0
    }

    /** Adds titles you marked watched in VYRK to your Trakt history. */
    suspend fun addToHistory(items: List<TraktItem>, token: String): Boolean =
        items.isNotEmpty() && post("/sync/history", historyBody(items), token)?.isSuccess == true

    companion object {
        const val BASE = "https://api.trakt.tv"

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

        fun parseDeviceCode(body: String): DeviceCode? {
            val o = runCatching { ProtocolJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
            return DeviceCode(o.s("device_code") ?: return null, o.s("user_code") ?: return null,
                o.s("verification_url") ?: "https://trakt.tv/activate", o.i("expires_in") ?: 600, (o.i("interval") ?: 5).coerceAtLeast(1))
        }

        fun parseTokens(body: String, nowMs: Long): TraktTokens? {
            val o = runCatching { ProtocolJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
            val access = o.s("access_token") ?: return null
            val refresh = o.s("refresh_token") ?: return null
            val expiresIn = o.l("expires_in") ?: 86_400L
            val created = o.l("created_at")?.times(1_000) ?: nowMs
            return TraktTokens(access, refresh, created + expiresIn * 1_000)
        }

        /** Trakt's device-token answers: 200 approved, 400 pending, 404/409/418 refused, 410 expired, 429 slow down. */
        fun parseTokenPoll(r: HttpResult, nowMs: Long): TokenPoll = when (r.code) {
            200 -> parseTokens(r.body, nowMs)?.let { TokenPoll.Approved(it) } ?: TokenPoll.Failed(200)
            400 -> TokenPoll.Pending
            429 -> TokenPoll.SlowDown
            410 -> TokenPoll.Expired
            404, 409, 418 -> TokenPoll.Denied
            else -> TokenPoll.Failed(r.code)
        }

        private fun ids(imdb: String) = buildJsonObject { putJsonObject("ids") { put("imdb", imdb) } }

        fun scrobbleBody(item: TraktItem, progressPercent: Float): JsonObject = buildJsonObject {
            when (item) {
                is TraktItem.Movie -> put("movie", ids(item.imdb))
                is TraktItem.Episode -> {
                    put("show", ids(item.showImdb))
                    putJsonObject("episode") { put("season", item.season); put("number", item.number) }
                }
            }
            put("progress", "%.2f".format(java.util.Locale.ROOT, progressPercent.coerceIn(0f, 100f)).toDouble())
        }

        fun historyBody(items: List<TraktItem>): JsonObject = buildJsonObject {
            val movies = items.filterIsInstance<TraktItem.Movie>()
            val episodes = items.filterIsInstance<TraktItem.Episode>()
            if (movies.isNotEmpty()) putJsonArray("movies") { movies.forEach { add(ids(it.imdb)) } }
            if (episodes.isNotEmpty()) putJsonArray("shows") {
                episodes.groupBy { it.showImdb }.forEach { (show, eps) ->
                    add(buildJsonObject {
                        putJsonObject("ids") { put("imdb", show) }
                        putJsonArray("seasons") {
                            eps.groupBy { it.season }.forEach { (s, list) ->
                                add(buildJsonObject {
                                    put("number", s)
                                    put("episodes", buildJsonArray { list.forEach { e -> add(buildJsonObject { put("number", e.number) }) } })
                                })
                            }
                        }
                    })
                }
            }
        }
    }
}
