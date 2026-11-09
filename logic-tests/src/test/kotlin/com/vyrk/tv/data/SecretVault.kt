package com.vyrk.tv.data
import java.io.File
/** JVM boundary only: regression tests use synthetic credentials, never real accounts. */
class SecretVault {
    suspend fun writeSecret(file: File, text: String) = AtomicFiles.writeText(file, text)
    suspend fun readSecret(file: File): String? = AtomicFiles.readText(file)
}
