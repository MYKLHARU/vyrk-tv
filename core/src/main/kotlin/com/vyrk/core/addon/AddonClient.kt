package com.vyrk.core.addon

import com.vyrk.core.net.HttpException
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer

/** Outcome of one add-on call, so a slow or broken add-on never fails the others. */
sealed interface AddonResult<out T> {
    data class Ok<T>(val value: T) : AddonResult<T>
    data class Failed(val addonId: String, val reason: String, val timedOut: Boolean = false) : AddonResult<Nothing>
}

/**
 * Calls add-ons over HTTP with at most [maxConcurrent] requests in flight and a timeout per
 * provider. Errors are returned as values, with URLs redacted.
 */
class AddonClient(
    private val http: HttpTransport,
    maxConcurrent: Int = 8,
    private val timeoutMs: (Descriptor, String) -> Long = { _, resource -> if (resource == "stream") 20_000 else 12_000 },
    /** Timing hook: resource, decode milliseconds, response size, thread name. */
    private val onDecoded: ((String, Long, Int, String) -> Unit)? = null,
    /** Failure hook: resource, add-on id, redacted reason, milliseconds from the call (queue included). */
    private val onFailed: ((String, String, String, Long) -> Unit)? = null,
) {
    private val permits = Semaphore(maxConcurrent)

    suspend fun manifest(transportUrl: String): Manifest {
        val res = permits.withPermit { http.get(transportUrl, timeoutMs = 12_000) }
        if (!res.isSuccess) throw HttpException(res.code, "Manifest request failed (${res.code}) for ${Redact.url(transportUrl)}")
        return ProtocolJson.decodeFromString(Manifest.serializer(), res.body)
    }

    suspend fun <T> resource(
        addon: Descriptor,
        resource: String,
        type: String,
        id: String,
        extra: List<Pair<String, String>>,
        serializer: KSerializer<T>,
    ): AddonResult<T> {
        val url = try {
            AddonUrls.resourceUrl(addon.transportUrl, resource, type, id, extra)
        } catch (e: IllegalArgumentException) {
            return AddonResult.Failed(addon.manifest.id, "unsupported transport")
        }
        val t = timeoutMs(addon, resource)
        val called = System.nanoTime()
        return resourceCall(addon, resource, url, t, serializer).also { r ->
            if (r is AddonResult.Failed) onFailed?.invoke(resource, addon.manifest.id, r.reason, (System.nanoTime() - called) / 1_000_000)
        }
    }

    private suspend fun <T> resourceCall(addon: Descriptor, resource: String, url: String, t: Long, serializer: KSerializer<T>): AddonResult<T> {
        return try {
            // The timeout starts once the request has a slot: counting the wait for one let a few
            // slow add-ons time out every other request queued behind them (rows "didn't load").
            val res = permits.withPermit { withTimeout(t) { http.get(url, timeoutMs = t) } }
            if (!res.isSuccess) AddonResult.Failed(addon.manifest.id, "HTTP ${res.code}")
            else {
                // Decoded on a background thread whoever calls: metas for long series are large,
                // and callers include composition coroutines on the main thread.
                val value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val start = System.nanoTime()
                    ProtocolJson.decodeFromString(serializer, res.body).also {
                        onDecoded?.invoke(resource, (System.nanoTime() - start) / 1_000_000, res.body.length, Thread.currentThread().name)
                    }
                }
                AddonResult.Ok(value)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            AddonResult.Failed(addon.manifest.id, "timed out", timedOut = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AddonResult.Failed(addon.manifest.id, Redact.text(e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun catalog(addon: Descriptor, type: String, id: String, extra: List<Pair<String, String>> = emptyList()) =
        resource(addon, "catalog", type, id, extra, CatalogResponse.serializer())

    suspend fun meta(addon: Descriptor, type: String, id: String) =
        resource(addon, "meta", type, id, emptyList(), MetaResponse.serializer())

    suspend fun streams(addon: Descriptor, type: String, id: String) =
        resource(addon, "stream", type, id, emptyList(), StreamsResponse.serializer())

    suspend fun subtitles(addon: Descriptor, type: String, id: String, extra: List<Pair<String, String>> = emptyList()) =
        resource(addon, "subtitles", type, id, extra, SubtitlesResponse.serializer())

    /** Streams from every add-on that supports the type and ID, in parallel, in add-on order. */
    suspend fun allStreams(addons: List<Descriptor>, type: String, id: String): List<Pair<Descriptor, AddonResult<StreamsResponse>>> =
        coroutineScope {
            addons.filter { it.manifest.supports("stream", type, id) }
                .map { a -> async { a to streams(a, type, id) } }
                .map { it.await() }
        }

    /** First add-on (in order) that returns a meta for the ID. */
    suspend fun firstMeta(addons: List<Descriptor>, type: String, id: String): MetaItem? {
        for (a in addons.filter { it.manifest.supports("meta", type, id) }) {
            val r = meta(a, type, id)
            if (r is AddonResult.Ok && r.value.meta != null) return r.value.meta
        }
        return null
    }

    suspend fun allSubtitles(addons: List<Descriptor>, type: String, id: String, extra: List<Pair<String, String>> = emptyList()) =
        coroutineScope {
            addons.filter { it.manifest.supports("subtitles", type, id) }
                .map { a -> async { a to subtitles(a, type, id, extra) } }
                .map { it.await() }
        }
}
