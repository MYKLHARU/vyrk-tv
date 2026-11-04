package com.vyrk.core.net

/** Minimal HTTP abstraction so core stays free of Android and testable without a network. */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): HttpResult
    suspend fun postJson(url: String, body: String, timeoutMs: Long = 15_000): HttpResult

    /** Raw bytes, for range reads of media files. Returns null on any failure. */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): ByteArray? = null
}

data class HttpResult(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

class HttpException(val code: Int, message: String) : Exception(message)
