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
    private data class Write(val file: File?, val bytes: () -> ByteArray, val done: CompletableDeferred<Unit>?, val seq: Long = 0, val epoch: Long = 0)
    private val queue = Channel<Write>(Channel.UNLIMITED)
    /**
     * Newest queued [writeTextLater] per file within an epoch. An older queued one is skipped: it
     * would be overwritten at once. An awaited write or a [flush] starts a new epoch, so what was
     * queued before it is on disk (in its newest form) when the caller resumes.
     */
    private val newest = HashMap<String, Long>()
    private var counter = 0L
    private var epoch = 0L
    private fun key(file: File, epoch: Long) = "$epoch|${file.path}"
    private fun superseded(w: Write): Boolean {
        val file = w.file ?: return false
        if (w.done != null) return false
        return synchronized(newest) {
            val k = key(file, w.epoch)
            val latest = newest[k]
            if (latest == w.seq) newest.remove(k)
            latest != null && latest != w.seq
        }
    }
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (w in queue) {
            try {
                // Progress is saved every second while a film plays; on slow storage the queue
                // would otherwise grow, each entry holding a whole library snapshot.
                if (superseded(w)) continue
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
        synchronized(newest) { epoch++; check(queue.trySend(Write(file, { bytes }, done)).isSuccess) }
        done.await()
    }
    suspend fun writeText(file: File, text: String) = write(file, text.encodeToByteArray())
    fun writeTextLater(file: File, text: String) {
        writeTextLater(file) { text }
    }
    /** Capture immutable state at the call site; serialize it on IO in the reserved order. */
    fun writeTextLater(file: File, text: () -> String) {
        synchronized(newest) {
            val seq = ++counter
            newest[key(file, epoch)] = seq
            check(queue.trySend(Write(file, { text().encodeToByteArray() }, null, seq, epoch)).isSuccess)
        }
    }
    /** Barrier used before opening/deleting a profile, and by regression tests. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        synchronized(newest) { epoch++; check(queue.trySend(Write(null, { byteArrayOf() }, done)).isSuccess) }
        done.await()
    }
    fun readText(file: File): String? = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    fun readBytes(file: File): ByteArray? = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
}
