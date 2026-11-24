package com.vyrk.core.debrid

import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * TorBox's API, signed in with the account's API key. TorBox says which torrents are cached, so
 * uncached ones are skipped before anything is added to the account.
 */
class TorBoxClient(
    private val http: HttpTransport,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private fun auth(key: String) = mapOf("Authorization" to "Bearer $key")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun user(key: String): Pair<Int, DebridUser?> {
        val r = runCatching { http.get("$API/user/me", auth(key), 10_000) }.getOrNull() ?: return 0 to null
        val d = if (r.isSuccess) obj(r.body)?.get("data") as? JsonObject else null
        // Plan 0 is the free plan; anything else is a paid one.
        return (if (errorOf(r) == DebridResult.SignedOut) 401 else r.code) to d?.let {
            DebridUser(it.str("email"), (it.int("plan") ?: 0) > 0, it.str("premium_expires_at")?.take(10))
        }
    }

    /** Which of [hashes] TorBox has ready (lower case). Unknown (null) when the check fails. */
    suspend fun cached(key: String, hashes: Collection<String>): Set<String>? {
        val out = mutableSetOf<String>()
        for (chunk in hashes.map { it.lowercase() }.distinct().chunked(50)) {
            val r = runCatching { http.get("$API/torrents/checkcached?hash=${chunk.joinToString(",")}&format=list&list_files=false", auth(key), 10_000) }.getOrNull()
                ?: return null
            if (!r.isSuccess) return null
            val data = obj(r.body)?.get("data")
            (data as? JsonArray)?.forEach { el -> (el as? JsonObject)?.str("hash")?.let { out += it.lowercase() } }
            (data as? JsonObject)?.keys?.forEach { out += it.lowercase() }
        }
        return out
    }

    suspend fun resolve(key: String, infoHash: String, magnet: String, hint: FileHint): DebridResult {
        val hash = infoHash.lowercase()
        val ready = cached(key, listOf(hash))
        if (ready != null && hash !in ready) return DebridResult.NotCached
        val h = auth(key)
        val add = runCatching { http.postForm("$API/torrents/createtorrent", mapOf("magnet" to magnet, "allow_zip" to "false"), h, 15_000) }
            .getOrElse { return DebridResult.Failed("network") }
        var id = (obj(add.body)?.get("data") as? JsonObject)?.int("torrent_id")
        if (id == null && errorName(add) == "DUPLICATE_ITEM") id = findByHash(h, hash)
        if (id == null) return errorOf(add) ?: DebridResult.Failed("add")
        repeat(6) { i ->
            val r = runCatching { http.get("$API/torrents/mylist?id=$id&bypass_cache=true", h, 10_000) }.getOrElse { return DebridResult.Failed("network") }
            errorOf(r)?.let { return it }
            val torrent = obj(r.body)?.get("data") as? JsonObject
            if (torrent != null && torrent.flag("download_finished") == true && torrent.flag("download_present") == true) return linkFor(key, id, torrent, hint)
            if (i < 5) sleep(POLL_MS)
        }
        return DebridResult.NotCached
    }

    private suspend fun findByHash(h: Map<String, String>, hash: String): Int? {
        val r = runCatching { http.get("$API/torrents/mylist?bypass_cache=true", h, 10_000) }.getOrNull() ?: return null
        val data = obj(r.body)?.get("data") as? JsonArray ?: return null
        return data.mapNotNull { it as? JsonObject }.firstOrNull { it.str("hash").equals(hash, true) }?.int("id")
    }

    private suspend fun linkFor(key: String, id: Int, torrent: JsonObject, hint: FileHint): DebridResult {
        val files = (torrent["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { f ->
            DebridFile(f.int("id") ?: return@mapNotNull null, f.str("name") ?: f.str("short_name").orEmpty(), f.long("size") ?: 0)
        }
        val target = DebridFiles.pick(files, hint) { it.id } ?: return DebridResult.NoFile
        val r = runCatching { http.get("$API/torrents/requestdl?token=${enc(key)}&torrent_id=$id&file_id=${target.id}&zip_link=false", emptyMap(), 15_000) }
            .getOrElse { return DebridResult.Failed("network") }
        errorOf(r)?.let { return it }
        val url = (obj(r.body)?.get("data") as? JsonPrimitive)?.contentOrNull ?: return DebridResult.Failed("link")
        return DebridResult.Ok(url, target.name, target.bytes)
    }

    private fun JsonObject.flag(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val API = "https://api.torbox.app/v1/api"
        private const val POLL_MS = 1_000L

        private fun errorName(r: HttpResult): String? = obj(r.body)?.str("error")

        internal fun errorOf(r: HttpResult): DebridResult? {
            val o = obj(r.body)
            if (r.isSuccess && o?.get("success")?.let { (it as? JsonPrimitive)?.booleanOrNull } != false) return null
            return when (val e = o?.str("error")) {
                "BAD_TOKEN", "AUTH_ERROR", "NO_AUTH" -> DebridResult.SignedOut
                "PLAN_RESTRICTED_FEATURE" -> DebridResult.NotPremium
                else -> if (r.code == 401 || r.code == 403) DebridResult.SignedOut else DebridResult.Failed(e ?: "http ${r.code}")
            }
        }
    }
}
