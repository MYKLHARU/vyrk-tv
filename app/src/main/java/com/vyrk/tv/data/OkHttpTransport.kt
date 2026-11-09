package com.vyrk.tv.data

import com.vyrk.core.net.HttpResult
import com.vyrk.core.net.HttpTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [HttpTransport] on OkHttp with coroutine cancellation and per-call timeouts. */
class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return execute(req, timeoutMs)
    }

    override suspend fun postJson(url: String, body: String, timeoutMs: Long): HttpResult {
        val req = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
        return execute(req, timeoutMs)
    }

    override suspend fun getBytes(url: String, headers: Map<String, String>, timeoutMs: Long): ByteArray? = suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val bytes = runCatching { response.use { readCapped(it, headers["Range"]) } }.getOrNull()
                if (cont.isActive) cont.resume(bytes)
            }
        })
    }

    /** Never more than was asked for: a server that ignores Range would otherwise send the whole file into memory. */
    private fun readCapped(response: Response, range: String?): ByteArray? {
        if (!response.isSuccessful) return null
        val limit = com.vyrk.core.net.ByteRange.readLimit(range, response.code) ?: return null
        val source = response.body.source()
        source.request(limit)
        return source.buffer.readByteArray(minOf(limit, source.buffer.size))
    }

    private suspend fun execute(req: Request, timeoutMs: Long): HttpResult = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val r = try {
                    response.use { HttpResult(it.code, it.body.string()) }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(r)
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun client(userAgent: String): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            // OkHttp's default of 5 requests per host queued posters (and catalogue pages from the
            // same add-on) behind each other; image hosts serve many small files at once.
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request)
            }
            .build()
    }
}
