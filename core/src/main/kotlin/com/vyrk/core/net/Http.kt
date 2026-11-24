package com.vyrk.core.net

/** Minimal HTTP abstraction so core stays free of Android and testable without a network. */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): HttpResult
    suspend fun postJson(url: String, body: String, timeoutMs: Long = 15_000): HttpResult

    /** A JSON POST with request headers (Trakt's API key and sign-in). Transports without headers ignore them. */
    suspend fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Long = 15_000): HttpResult = postJson(url, body, timeoutMs)

    /** A form POST (application/x-www-form-urlencoded) with request headers: the debrid services' API. */
    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): HttpResult =
        throw UnsupportedOperationException("postForm")

    /** A DELETE with request headers. */
    suspend fun delete(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): HttpResult =
        throw UnsupportedOperationException("delete")

    /** Raw bytes, for range reads of media files. Returns null on any failure. */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15_000): ByteArray? = null
}

data class HttpResult(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

class HttpException(val code: Int, message: String) : Exception(message)
