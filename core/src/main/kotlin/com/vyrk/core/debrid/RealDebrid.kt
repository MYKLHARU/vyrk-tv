package com.vyrk.core.debrid

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
internal fun json(body: String): JsonElement? = runCatching { ProtocolJson.parseToJsonElement(body) }.getOrNull()
internal fun obj(body: String) = json(body) as? JsonObject

sealed interface DevicePoll {
    data class Approved(val login: DebridLogin) : DevicePoll
    data object Pending : DevicePoll
    data object Failed : DevicePoll
}

/**
 * Real-Debrid's REST API: the device sign-in (with the open-source app client ID that Kodi
 * add-ons use) and turning a torrent into a direct link. Real-Debrid no longer says what's cached,
 * so a torrent is added, its videos selected, and it counts as cached only if it is ready at once.
 * A torrent VYRK added that isn't ready is deleted again, so nothing downloads unasked.
 */
class RealDebridClient(
    private val http: HttpTransport,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun deviceCode(): DebridDeviceCode? {
        val r = runCatching { http.get("$OAUTH/device/code?client_id=$CLIENT_ID&new_credentials=yes") }.getOrNull() ?: return null
        return if (r.isSuccess) parseDeviceCode(r.body) else null
    }

    /** One poll: approved once the user has entered the code (then the token is fetched). */
    suspend fun poll(code: DebridDeviceCode, nowMs: Long): DevicePoll {
        val r = runCatching { http.get("$OAUTH/device/credentials?client_id=$CLIENT_ID&code=${enc(code.deviceCode)}") }.getOrNull()
            ?: return DevicePoll.Pending
        if (!r.isSuccess) return DevicePoll.Pending
        val o = obj(r.body) ?: return DevicePoll.Pending
        val id = o.str("client_id") ?: return DevicePoll.Pending
        val secret = o.str("client_secret") ?: return DevicePoll.Pending
        return token(id, secret, code.deviceCode, nowMs)?.let { DevicePoll.Approved(it) } ?: DevicePoll.Failed
    }

    private suspend fun token(clientId: String, secret: String, code: String, nowMs: Long): DebridLogin? {
        val r = runCatching {
            http.postForm("$OAUTH/token", mapOf("client_id" to clientId, "client_secret" to secret, "code" to code, "grant_type" to GRANT))
        }.getOrNull() ?: return null
        if (!r.isSuccess) return null
        return parseToken(r.body, clientId, secret, nowMs)
    }

    /** A fresh access token from the refresh token; null when Real-Debrid refuses it. */
    suspend fun refresh(login: DebridLogin, nowMs: Long): DebridLogin? =
        token(login.clientId, login.clientSecret, login.refreshToken, nowMs)?.copy(username = login.username)

    suspend fun user(token: String): Pair<Int, DebridUser?> {
        val r = runCatching { http.get("$API/user", auth(token), 10_000) }.getOrNull() ?: return 0 to null
        val o = if (r.isSuccess) obj(r.body) else null
        return r.code to o?.let { DebridUser(it.str("username"), it.str("type") == "premium", it.str("expiration")?.take(10)) }
    }

    /** A direct link to the file [hint] describes in the torrent [infoHash]. */
    suspend fun resolve(token: String, infoHash: String, magnet: String, hint: FileHint): DebridResult {
        val h = auth(token)
        val hash = infoHash.lowercase()
        // A ready copy already in the account is reused (and never deleted).
        val existing = runCatching { http.get("$API/torrents?limit=100", h, 10_000) }.getOrNull()?.let { r ->
            errorOf(r)?.let { return it }
            (json(r.body) as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.str("hash").equals(hash, true) && it.str("status") == "downloaded" }?.str("id")
        }
        if (existing != null) {
            val info = info(h, existing)
            if (info is Info.Ok) linkFor(h, info, hint)?.let { return it }
        }
        val add = runCatching { http.postForm("$API/torrents/addMagnet", mapOf("magnet" to magnet), h, 10_000) }.getOrElse { return DebridResult.Failed("network") }
        errorOf(add)?.let { return it }
        val id = obj(add.body)?.str("id") ?: return DebridResult.Failed("add")
        val result = select(h, id, hint)
        if (result !is DebridResult.Ok) runCatching { http.delete("$API/torrents/delete/$id", h, 10_000) }
        return result
    }

    private suspend fun select(h: Map<String, String>, id: String, hint: FileHint): DebridResult {
        var info = waitFor(h, id, 8) { it == "waiting_files_selection" || it == "downloaded" || it in DEAD }
        if (info !is Info.Ok) return (info as Info.Err).result
        if (info.status in DEAD) return DebridResult.Failed(info.status)
        if (info.status == "waiting_files_selection") {
            val videos = info.files.map { it.file }.filter { DebridFiles.isVideo(it.path) }
            if (videos.isEmpty()) return DebridResult.NoFile
            val sel = runCatching {
                http.postForm("$API/torrents/selectFiles/$id", mapOf("files" to videos.joinToString(",") { it.id.toString() }), h, 10_000)
            }.getOrElse { return DebridResult.Failed("network") }
            errorOf(sel)?.let { return it }
            info = waitFor(h, id, 4) { it == "downloaded" || it in DEAD }
            if (info !is Info.Ok) return (info as Info.Err).result
        }
        if (info.status != "downloaded") return DebridResult.NotCached
        return linkFor(h, info, hint) ?: DebridResult.NoFile
    }

    /** The selected files pair up with the links in file order; unrestricting the right link gives the download URL. */
    private suspend fun linkFor(h: Map<String, String>, info: Info.Ok, hint: FileHint): DebridResult? {
        val selected = info.files.filter { it.selected }.sortedBy { it.file.id }
        if (selected.isEmpty() || selected.size != info.links.size) return null
        val target = DebridFiles.pick(selected.map { it.file }, hint) { it.id - 1 } ?: return null
        val link = info.links[selected.indexOfFirst { it.file.id == target.id }]
        val r = runCatching { http.postForm("$API/unrestrict/link", mapOf("link" to link), h, 10_000) }.getOrElse { return DebridResult.Failed("network") }
        errorOf(r)?.let { return it }
        val o = obj(r.body) ?: return DebridResult.Failed("unrestrict")
        val url = o.str("download") ?: return DebridResult.Failed("unrestrict")
        return DebridResult.Ok(url, o.str("filename") ?: target.name, o.long("filesize")?.takeIf { it > 0 } ?: target.bytes)
    }

    private class RdFile(val file: DebridFile, val selected: Boolean)

    private sealed interface Info {
        class Ok(val status: String, val files: List<RdFile>, val links: List<String>) : Info
        class Err(val result: DebridResult) : Info
    }

    private suspend fun info(h: Map<String, String>, id: String): Info {
        val r = runCatching { http.get("$API/torrents/info/$id", h, 10_000) }.getOrElse { return Info.Err(DebridResult.Failed("network")) }
        errorOf(r)?.let { return Info.Err(it) }
        val o = obj(r.body) ?: return Info.Err(DebridResult.Failed("info"))
        val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { f ->
            val fid = f.int("id") ?: return@mapNotNull null
            RdFile(DebridFile(fid, f.str("path").orEmpty(), f.long("bytes") ?: 0), f.int("selected") == 1)
        }
        val links = (o["links"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return Info.Ok(o.str("status").orEmpty(), files, links)
    }

    private suspend fun waitFor(h: Map<String, String>, id: String, tries: Int, done: (String) -> Boolean): Info {
        var last: Info = Info.Err(DebridResult.Failed("info"))
        repeat(tries) { i ->
            last = info(h, id)
            val l = last
            if (l !is Info.Ok || done(l.status)) return l
            if (i < tries - 1) sleep(POLL_MS)
        }
        return last
    }

    companion object {
        const val API = "https://api.real-debrid.com/rest/1.0"
        const val OAUTH = "https://api.real-debrid.com/oauth/v2"
        /** Real-Debrid's client ID for open-source apps (no secret): each sign-in gets its own credentials. */
        const val CLIENT_ID = "X245A4XAIBGVM"
        private const val GRANT = "http://oauth.net/grant_type/device/1.0"
        private const val POLL_MS = 900L
        private val DEAD = setOf("magnet_error", "error", "virus", "dead")

        fun parseDeviceCode(body: String): DebridDeviceCode? {
            val o = obj(body) ?: return null
            val url = o.str("verification_url") ?: return null
            return DebridDeviceCode(
                o.str("device_code") ?: return null, o.str("user_code") ?: return null, url,
                o.str("direct_verification_url") ?: url, o.int("expires_in") ?: 600, (o.int("interval") ?: 5).coerceAtLeast(1),
            )
        }

        fun parseToken(body: String, clientId: String, secret: String, nowMs: Long): DebridLogin? {
            val o = obj(body) ?: return null
            return DebridLogin(
                DebridService.REAL_DEBRID, o.str("access_token") ?: return null, o.str("refresh_token").orEmpty(),
                clientId, secret, nowMs + (o.long("expires_in") ?: 3600) * 1000,
            )
        }

        /** 401: signed out; 451 or error code 35: the file was taken down; 403 error code 20: not premium. */
        internal fun errorOf(r: HttpResult): DebridResult? {
            if (r.isSuccess) return null
            val code = obj(r.body)?.int("error_code")
            return when {
                r.code == 401 || code == 8 -> DebridResult.SignedOut
                r.code == 451 || code == 35 -> DebridResult.Removed
                code == 20 -> DebridResult.NotPremium
                else -> DebridResult.Failed("http ${r.code}${code?.let { " ($it)" }.orEmpty()}")
            }
        }
    }
}
