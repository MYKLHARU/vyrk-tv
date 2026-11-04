package com.vyrk.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.library.LibraryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes Continue Watching to the launcher's Watch Next row for the active, unlocked profile
 * only, and clears it when the profile changes. Deep links open vyrk://open?type=…&id=….
 */
class WatchNext(private val context: Context) {
    private val supported = Build.VERSION.SDK_INT >= 26

    @SuppressLint("RestrictedApi")
    suspend fun publish(items: Collection<LibraryItem>) = withContext(Dispatchers.IO) {
        if (!supported) return@withContext
        runCatching {
            clear()
            LibraryRules.continueWatching(items).take(10).forEach { item ->
                val b = WatchNextProgram.Builder()
                    .setType(if (item.type == "series") TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(item.state.lastWatchedMs ?: item.mtimeMs)
                    .setTitle(item.name)
                    .setInternalProviderId(item.id)
                    .setIntentUri(Uri.parse("vyrk://open?type=${Uri.encode(item.type)}&id=${Uri.encode(item.id)}"))
                    .setLastPlaybackPositionMillis(item.state.timeOffset.toInt())
                    .setDurationMillis(item.state.duration.toInt())
                item.poster?.let { b.setPosterArtUri(Uri.parse(it)) }
                context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
            }
        }.onFailure { Diagnostics.log("watchnext", "publish failed: ${it.javaClass.simpleName}") }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (!supported) return@withContext
        runCatching { context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
    }
}
