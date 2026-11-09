package com.vyrk.tv.player

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Media requests get their own queue/connections, keeping artwork and catalogue work out of the way. */
object PlaybackNetwork {
    fun client(parent: OkHttpClient): OkHttpClient = parent.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = 8; maxRequestsPerHost = 4 })
        .connectionPool(ConnectionPool())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
