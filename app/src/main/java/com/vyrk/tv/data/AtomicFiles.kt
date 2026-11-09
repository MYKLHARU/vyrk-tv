package com.vyrk.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/** Reserve write order at the call site, before dispatching IO. Writes survive a screen/session close. */
object AtomicFiles {
    private data class Write(val file: File?, val bytes: () -> ByteArray, val done: CompletableDeferred<Unit>?)
    private val queue = Channel<Write>(Channel.UNLIMITED)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (w in queue) {
            try {
                w.file?.let { file ->
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    val bytes = w.bytes()
                    tmp.outputStream().use { it.write(bytes); it.fd.sync() }
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        check(tmp.renameTo(file)) { "rename failed for ${file.name}" }
                    }
                }
                w.done?.complete(Unit)
            } catch (e: Exception) {
                Diagnostics.log("storage", "write failed: ${w.file?.name}: ${e.message}")
                w.done?.completeExceptionally(e)
            }
        }
    }

    suspend fun write(file: File, bytes: ByteArray) {
        val done = CompletableDeferred<Unit>()
        check(queue.trySend(Write(file, { bytes }, done)).isSuccess)
        done.await()
    }
    suspend fun writeText(file: File, text: String) = write(file, text.encodeToByteArray())
    fun writeTextLater(file: File, text: String) {
        writeTextLater(file) { text }
    }
    /** Capture immutable state at the call site; serialize it on IO in the reserved order. */
    fun writeTextLater(file: File, text: () -> String) {
        check(queue.trySend(Write(file, { text().encodeToByteArray() }, null)).isSuccess)
    }
    /** Barrier used before opening/deleting a profile, and by regression tests. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        check(queue.trySend(Write(null, { byteArrayOf() }, done)).isSuccess)
        done.await()
    }
    fun readText(file: File): String? = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    fun readBytes(file: File): ByteArray? = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
}
