package com.vyrk.tv.player

import com.sun.net.httpserver.HttpServer
import com.vyrk.tv.data.OkHttpTransport
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PlaybackNetworkTest {
    private fun fetch(client: OkHttpClient, url: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { result.completeExceptionally(e) }
            override fun onResponse(call: Call, response: Response) { response.use { result.complete(it.body.string()) } }
        })
        return result
    }

    private fun close(vararg clients: OkHttpClient) = clients.forEach {
        it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow()
    }

    @Test fun blockedArtworkCannotQueueTheVideoBehindIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                if (exchange.requestURI.path == "/poster") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                val bytes = "response".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val parent = OkHttpTransport.client("VYRK/synthetic").apply {
            dispatcher.maxRequests = 1; dispatcher.maxRequestsPerHost = 1
        }
        val media = PlaybackNetwork.client(parent)
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val artwork = fetch(parent, "$base/poster")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("response", fetch(media, "$base/video").get(2, TimeUnit.SECONDS))
            assertFalse(artwork.isDone, "The assertion must run while the artwork request remains blocked")
            release.countDown(); artwork.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); close(parent, media); server.stop(0); executor.shutdownNow() }
    }

    @Test fun mediaKeepsRequiredProxyHeadersAndTheDefaultUserAgentStillWorks() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = listOf("User-Agent", "Range", "Authorization", "Referer").joinToString("|") { exchange.requestHeaders.getFirst(it).orEmpty() }.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val parent = OkHttpTransport.client("VYRK/synthetic")
        val media = PlaybackNetwork.client(parent)
        val url = "http://127.0.0.1:${server.address.port}/"
        try {
            val proxy = Request.Builder().url(url).header("User-Agent", "Required-addon-agent")
                .header("Range", "bytes=100-199").header("Authorization", "Bearer synthetic-token")
                .header("Referer", "https://synthetic.invalid/").build()
            media.newCall(proxy).execute().use {
                assertEquals("Required-addon-agent|bytes=100-199|Bearer synthetic-token|https://synthetic.invalid/", it.body.string())
            }
            parent.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("VYRK/synthetic|||", it.body.string()) }
        } finally { close(parent, media); server.stop(0) }
    }

    @Test fun mediaHasIndependentConnectionsAndNoWholeFileDeadline() {
        val parent = OkHttpTransport.client("VYRK/synthetic").newBuilder().callTimeout(500, TimeUnit.MILLISECONDS).build()
        val media = PlaybackNetwork.client(parent)
        try {
            assertNotSame(parent.dispatcher, media.dispatcher)
            assertNotSame(parent.connectionPool, media.connectionPool)
            assertEquals(0, media.callTimeoutMillis)
            assertEquals(30_000, media.readTimeoutMillis)
        } finally { close(parent, media) }
    }
}
