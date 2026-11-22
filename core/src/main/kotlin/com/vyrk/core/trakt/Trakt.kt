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

/** A Trakt sign-in in progress: the code to enter at [verificationUrl]; [codeVerifier] is this sign-in's PKCE secret. */
data class DeviceCode(
    val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int, val intervalSec: Int,
    val codeVerifier: String = "",
)

/** PKCE (RFC 7636, S256): a random verifier kept on the TV, and its hash sent with the device-code request. */
object Pkce {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun verifier(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        String(CharArray(64) { UNRESERVED[random.nextInt(UNRESERVED.length)] })

    fun challenge(verifier: String): String =
        base64Url(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Base64url without padding (java.util.Base64 needs API 26; VYRK runs on 23). */
    fun base64Url(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            append(B64URL[b0 shr 2]); append(B64URL[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) append(B64URL[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) append(B64URL[b2 and 63])
            i += 3
        }
    }
}

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
    /** Approved by an account the Trakt app doesn't allow (Development apps: the owner and up to 16 added users). */
    data object NotAllowed : TokenPoll
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
 * Trakt API v2 (https://developer.trakt.tv): device sign-in, token refresh, scrobbling and
 * history. [clientId] comes from the build; without it Trakt is off. Apps made since October 2026
 * have no client secret and sign in with the device code plus PKCE; [clientSecret] is only for
 * older apps that still have one.
 */
class TraktClient(private val http: HttpTransport, private val clientId: String, private val clientSecret: String = "") {
    val enabled: Boolean get() = clientId.isNotBlank()
    private val hasSecret get() = clientSecret.isNotBlank()

    private fun headers(token: String? = null) = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", clientId)
        if (token != null) put("Authorization", "Bearer $token")
    }

    private suspend fun post(path: String, body: JsonObject, token: String? = null, host: String = BASE): HttpResult? =
        runCatching { http.post("$host$path", body.toString(), headers(token), 15_000) }.getOrNull()

    /** Sign-in calls go to auth.trakt.tv (required from February 2027); api.trakt.tv if that can't be reached. */
    private suspend fun oauth(path: String, body: JsonObject): HttpResult? {
        val r = post(path, body, host = AUTH)
        return if (r == null || r.code >= 500) post(path, body) ?: r else r
    }

    suspend fun deviceCode(): DeviceCode? {
        val verifier = Pkce.verifier()
        val r = oauth("/oauth/device/code", buildJsonObject {
            put("client_id", clientId); put("code_challenge", Pkce.challenge(verifier)); put("code_challenge_method", "S256")
        }) ?: return null
        return if (r.isSuccess) parseDeviceCode(r.body)?.copy(codeVerifier = verifier) else null
    }

    suspend fun pollToken(code: DeviceCode, nowMs: Long): TokenPoll {
        val r = oauth("/oauth/device/token", buildJsonObject {
            put("code", code.deviceCode); put("client_id", clientId)
            if (code.codeVerifier.isNotEmpty()) put("code_verifier", code.codeVerifier)
            if (hasSecret) put("client_secret", clientSecret)
        }) ?: return TokenPoll.Failed(0)
        return parseTokenPoll(r, nowMs)
    }

    suspend fun refresh(tokens: TraktTokens, nowMs: Long): TraktTokens? {
        val r = oauth("/oauth/token", buildJsonObject {
            put("refresh_token", tokens.refreshToken); put("client_id", clientId); put("grant_type", "refresh_token")
            if (hasSecret) { put("client_secret", clientSecret); put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob") }
        }) ?: return null
        return if (r.isSuccess) parseTokens(r.body, nowMs)?.copy(username = tokens.username) else null
    }

    suspend fun revoke(token: String) {
        oauth("/oauth/revoke", buildJsonObject {
            put("token", token); put("client_id", clientId)
            if (hasSecret) put("client_secret", clientSecret)
        })
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
        const val AUTH = "https://auth.trakt.tv"

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

        /**
         * Trakt's device-token answers: 200 approved, 400 pending, 404/409 refused, 410 expired, 429 slow
         * down. 418 is a refusal too, and also what a Development app answers when the code was approved
         * by an account that isn't the app's owner or on its list of users.
         */
        fun parseTokenPoll(r: HttpResult, nowMs: Long): TokenPoll = when (r.code) {
            200 -> parseTokens(r.body, nowMs)?.let { TokenPoll.Approved(it) } ?: TokenPoll.Failed(200)
            400 -> TokenPoll.Pending
            429 -> TokenPoll.SlowDown
            410 -> TokenPoll.Expired
            418 -> TokenPoll.NotAllowed
            404, 409 -> TokenPoll.Denied
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
