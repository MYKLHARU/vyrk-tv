package com.vyrk.core.profile

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 PIN hashes stored as `pbkdf2$<iterations>$<salt>$<hash>`. */
object PinHasher {
    const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun hash(pin: String, iterations: Int = ITERATIONS, salt: ByteArray = randomSalt()): String {
        val dk = derive(pin, salt, iterations)
        val enc = Base64.getEncoder()
        return "pbkdf2$$iterations$${enc.encodeToString(salt)}$${enc.encodeToString(dk)}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val it = parts[1].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, it), expected)
    }

    fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomSalt() = ByteArray(16).also { SecureRandom().nextBytes(it) }
}

/**
 * Escalating lockout: three free attempts, then 30 s, doubling per further failure, capped at
 * 15 minutes. State is plain data so it can be persisted per profile.
 */
data class PinLockout(val failures: Int = 0, val lockedUntilMs: Long = 0) {
    fun isLocked(nowMs: Long) = nowMs < lockedUntilMs
    fun remainingMs(nowMs: Long) = (lockedUntilMs - nowMs).coerceAtLeast(0)

    fun onFailure(nowMs: Long): PinLockout {
        val f = failures + 1
        val lock = if (f < FREE_ATTEMPTS) 0L else (BASE_MS shl (f - FREE_ATTEMPTS).coerceAtMost(10)).coerceAtMost(MAX_MS)
        return PinLockout(f, if (lock > 0) nowMs + lock else 0)
    }

    fun onSuccess() = PinLockout()

    companion object {
        const val FREE_ATTEMPTS = 3
        const val BASE_MS = 30_000L
        const val MAX_MS = 15 * 60_000L
    }
}
