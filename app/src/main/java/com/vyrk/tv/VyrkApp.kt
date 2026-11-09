package com.vyrk.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.memory.MemoryCache
import com.vyrk.core.playback.PlaybackBuffering
import com.vyrk.tv.data.AppGraph
import kotlinx.coroutines.launch

class VyrkApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
        graph.scope.launch { graph.load() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(PlaybackBuffering.artworkBytes(Runtime.getRuntime().maxMemory())).build() }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .crossfade(true)
            .build()

    companion object {
        @Volatile private var instance: VyrkApp? = null
        fun graphOrNull(): AppGraph? = instance?.graph
        fun graph(app: android.content.Context): AppGraph = (app.applicationContext as VyrkApp).graph
    }
}
