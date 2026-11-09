package com.vyrk.core.art

import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI

/** Direct logo lookup by verified IDs only. Keys travel in headers and are never logged. */
class FanartClient(private val http: HttpTransport, rawKey: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val key = rawKey.trim().takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }.orEmpty()
    val enabled: Boolean get() = key.isNotEmpty()
    private data class Entry(val urls: List<String>, val expires: Long)
    private val cache = LinkedHashMap<String, Entry>()
    private val mutex = Mutex()
    private var retryAfter = 0L

    suspend fun logos(id: String, type: String): List<String> {
        if (!enabled || type !in setOf("movie", "series", "anime")) return emptyList()
        val source = when {
            Regex("tt\\d{5,10}").matches(id) -> "imdb"
            Regex("tmdb:\\d+").matches(id) -> "tmdb"
            else -> return emptyList()
        }
        val resource = if (type == "movie") "movies" else "tv"
        val cacheKey = "$resource:$id"
        return mutex.withLock {
            cache[cacheKey]?.takeIf { it.expires > clock() }?.let { return@withLock it.urls }
            if (clock() < retryAfter) return@withLock emptyList()
            val response = try {
                http.get("https://webservice.fanart.tv/v3.2/$resource/${id.removePrefix("tmdb:")}?source=$source",
                    mapOf("api-key" to key, "Accept" to "application/json"), 5_000)
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { return@withLock emptyList() }
            if (response.code == 429 || response.code == 401 || response.code == 403) {
                retryAfter = clock() + 60_000
                return@withLock emptyList()
            }
            if (!response.isSuccess && response.code != 404) return@withLock emptyList()
            val urls = if (response.code == 404) emptyList() else try {
                parseLogos(ProtocolJson.parseToJsonElement(response.body) as JsonObject, resource == "movies")
            } catch (_: Exception) { return@withLock emptyList() }
            if (cache.size >= 256) cache.remove(cache.keys.first())
            cache[cacheKey] = Entry(urls, clock() + if (urls.isEmpty()) 15 * 60_000 else 6 * 60 * 60_000)
            urls
        }
    }

    companion object {
        fun parseLogos(json: JsonObject, movie: Boolean): List<String> {
            val fields = if (movie) listOf("hdmovielogo", "movielogo") else listOf("hdtvlogo", "clearlogo")
            fun JsonObject.s(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return fields.flatMapIndexed { quality, field ->
                (json[field] as? JsonArray).orEmpty().mapNotNull { raw -> (raw as? JsonObject)?.let { quality to it } }
            }.sortedWith(compareBy<Pair<Int, JsonObject>> {
                when (it.second.s("lang")) { "en" -> 0; "", "00", "null" -> 1; "ja" -> 2; else -> 3 }
            }.thenBy { it.first }.thenByDescending { it.second.s("likes").toIntOrNull() ?: 0 })
                .mapNotNull { (_, item) -> item.s("url").takeIf { url ->
                    runCatching { URI(url).let { it.scheme == "https" && it.host == "assets.fanart.tv" && it.userInfo == null && it.port == -1 } }.getOrDefault(false)
                } }.distinct().take(6)
        }
    }
}
