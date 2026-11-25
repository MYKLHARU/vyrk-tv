package com.vyrk.tv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.addon.RuntimeText
import com.vyrk.core.addon.Stream
import com.vyrk.core.debrid.DebridResult
import com.vyrk.core.library.LibraryRules
import com.vyrk.core.stream.Delivery
import com.vyrk.core.stream.Ranked
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.Diagnostics
import com.vyrk.tv.data.ProfileSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Instant start: while a title page is open, gets ready what Play will play. After a short pause
 * (so scrolling past titles costs nothing) it ranks the sources for the video Play starts (the
 * film, or the series' resume or first episode), fetches a torrent's debrid link, and downloads
 * the first seconds of the top source into a small cache the player reads from. The player then
 * starts from local storage while it connects for the rest.
 *
 * One title at a time; leaving the page cancels it. Only plain files (not HLS/DASH) are cached.
 */
@OptIn(UnstableApi::class)
class InstantStart(private val graph: AppGraph) {
    /** The ranked sources for one video, handed to the player so it doesn't ask the add-ons again. */
    private class Ready(val titleId: String, val requestedVideoId: String?, val ranked: List<Ranked>, val atMs: Long)

    private var job: Job? = null
    @Volatile private var ready: Ready? = null

    /** Opened once, for the app's life; the player reads it, only instant start writes it. */
    val cache: Cache by lazy {
        SimpleCache(File(graph.context.cacheDir, "instant-start"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(graph.context))
    }

    private val enabled get() = graph.deviceSettings.value.instantStart

    /** The title page shows [meta]; [ranked] when it has already loaded the film's sources. */
    fun prepare(session: ProfileSession, meta: MetaPreview, ranked: List<Ranked>?) {
        if (!enabled) return
        job?.cancel()
        job = graph.scope.launch {
            delay(DWELL_MS)
            runCatching { run(session, meta, ranked) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Diagnostics.log("instant", "failed: ${it.javaClass.simpleName}") }
        }
    }

    /** The title page closed. */
    fun cancel() { job?.cancel(); job = null }

    /** Sources ranked here for what Play is about to play, when still fresh; null otherwise. */
    fun rankedFor(titleId: String, requestedVideoId: String?): List<Ranked>? = ready
        ?.takeIf { it.titleId == titleId && it.requestedVideoId == requestedVideoId && System.currentTimeMillis() - it.atMs < FRESH_MS }?.ranked

    private suspend fun run(session: ProfileSession, meta: MetaPreview, given: List<Ranked>?) {
        val requested = Playback.videoFor(session, meta)
        val addons = session.effectiveAddons()
        val full = graph.metaCache.get(addons, meta.type, meta.id)
        // The player's choice when nothing was asked for: the first episode of the first season.
        val vid = requested ?: full?.videos?.filter { (it.season ?: 1) > 0 }?.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))?.firstOrNull()?.id ?: meta.id
        val runtime = full?.runtime?.let { RuntimeText.minutes(it) }
        val ranked = given?.takeIf { meta.type != "series" } ?: graph.rankStreams(graph.addonClient.allStreams(addons, meta.type, vid), session.rankPrefs(meta.id, runtime))
        ready = Ready(meta.id, requested, ranked, System.currentTimeMillis())
        val startMs = LibraryRules.resumeMs(session.itemFor(meta), vid)
        // The top source, or the next if the top one turns out dead (the player will find that too).
        for (r in ranked.filter { it.playable }.take(2)) {
            val stream = streamFor(r, vid) ?: continue
            when (val got = warm(stream, startMs, r, runtime)) {
                null -> return
                else -> if (got > 0) { Diagnostics.trace("instant_start ready $got"); Diagnostics.log("instant", "ready: ${got / 1024} KB of ${r.candidate.addonId}"); return }
            }
        }
    }

    /** The stream as the player will open it: a torrent through the debrid service (the link is kept for the player). */
    private suspend fun streamFor(r: Ranked, vid: String): Stream? {
        val s = r.candidate.stream
        if (s.url != null) return s
        if (r.info.delivery != Delivery.TORRENT || graph.debrid.service == null) return null
        val ok = graph.debrid.resolve(s, vid) as? DebridResult.Ok ?: return null
        return s.copy(url = ok.url)
    }

    /**
     * Downloads the start of [stream] into [cache]: a few seconds of picture for a fresh start,
     * just the header when resuming (the player reads it first). Returns the bytes cached, 0 when
     * the source answered with an error (try the next), null when there's nothing to do.
     */
    private suspend fun warm(stream: Stream, startMs: Long, r: Ranked, runtimeMinutes: Int?): Long? {
        val url = stream.url ?: return null
        if (!url.startsWith("http") || url.contains(".m3u8", true) || url.contains(".mpd", true)) return null
        val bytes = if (startMs > 0) HEADER_BYTES else budget(r, runtimeMinutes)
        val upstream = OkHttpDataSource.Factory(PlaybackNetwork.client(graph.http))
            .setDefaultRequestProperties(stream.behaviorHints.proxyHeaders?.request.orEmpty())
            .createDataSource()
        val writer = CacheWriter(CacheDataSource(cache, upstream), DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(bytes).build(), null, null)
        return try {
            coroutineScope {
                val work = async(Dispatchers.IO) { writer.cache() }
                // Leaving the page stops the download at its next read.
                try { work.await() } catch (e: kotlinx.coroutines.CancellationException) { writer.cancel(); throw e }
            }
            cache.getCachedBytes(url, 0, bytes)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            Diagnostics.log("instant", "source answered ${e.responseCode}")
            0
        } catch (e: java.io.IOException) {
            Diagnostics.log("instant", "warm failed: ${e.javaClass.simpleName}")
            0
        }
    }

    /** About [SECONDS] of the file at its average bitrate (size over runtime), within [MIN_BYTES]..[MAX_BYTES]. */
    private fun budget(r: Ranked, runtimeMinutes: Int?): Long {
        val size = r.info.sizeBytes ?: return DEFAULT_BYTES
        val perSecond = size / ((runtimeMinutes?.takeIf { it > 0 } ?: TYPICAL_MINUTES) * 60.0)
        return (perSecond * SECONDS).toLong().coerceIn(MIN_BYTES, MAX_BYTES)
    }

    private companion object {
        const val DWELL_MS = 1_500L
        const val FRESH_MS = 10 * 60_000L
        const val CACHE_BYTES = 192L * 1024 * 1024
        const val HEADER_BYTES = 2L * 1024 * 1024
        const val DEFAULT_BYTES = 12L * 1024 * 1024
        const val MIN_BYTES = 6L * 1024 * 1024
        const val MAX_BYTES = 40L * 1024 * 1024
        const val SECONDS = 8
        const val TYPICAL_MINUTES = 110
    }
}
