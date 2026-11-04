package com.vyrk.tv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vyrk.core.addon.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Atomic file writes on the IO dispatcher: write a temp file, then rename over the target. */
object AtomicFiles {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun write(file: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        locks.getOrPut(file.absolutePath) { Mutex() }.withLock {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "rename failed for ${file.name}" }
            }
        }
    }

    suspend fun writeText(file: File, text: String) = write(file, text.encodeToByteArray())

    fun readText(file: File): String? = if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    fun readBytes(file: File): ByteArray? = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
}

/**
 * Secrets vault: AES-256-GCM with a non-exportable Android Keystore key. Used for the Stremio
 * authKey and every add-on transport URL. File format: 12-byte IV followed by ciphertext+tag.
 */
class SecretVault(private val alias: String = "vyrk_vault_v1") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    suspend fun writeSecret(file: File, text: String) = AtomicFiles.write(file, encrypt(text.encodeToByteArray()))

    suspend fun readSecret(file: File): String? = withContext(Dispatchers.IO) {
        val b = AtomicFiles.readBytes(file) ?: return@withContext null
        runCatching { decrypt(b).decodeToString() }.getOrNull()
    }
}

/**
 * Recent events for "Copy diagnostics". Every message passes through [Redact.text] so URLs
 * collapse to scheme://host/… and token-like values are removed. Never logs to logcat.
 */
object Diagnostics {
    private const val MAX = 200
    private val events = ArrayDeque<String>()

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${java.time.Instant.now()} [$tag] ${Redact.text(message)}"
        events.addLast(line)
        while (events.size > MAX) events.removeFirst()
    }

    @Synchronized
    fun dump(): List<String> = events.toList()
}
