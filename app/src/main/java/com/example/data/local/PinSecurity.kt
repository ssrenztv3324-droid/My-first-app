package com.example.data.local

import java.security.MessageDigest
import java.security.SecureRandom

object PinSecurity {
    const val MAX_ATTEMPTS_BEFORE_COOLDOWN = 3

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$salt:$pin".toByteArray(Charsets.UTF_8)
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, salt: String, expectedHash: String): Boolean {
        if (pin.isBlank() || salt.isBlank() || expectedHash.isBlank()) return false
        return hashPin(pin, salt) == expectedHash
    }

    /**
     * Returns cooldown duration in seconds once failed attempts reach >= 3.
     * 3rd failure -> 30s, 4th -> 60s, 5th -> 120s, 6th+ -> 300s.
     */
    fun cooldownSecondsForFailures(failedAttempts: Int): Long {
        if (failedAttempts < MAX_ATTEMPTS_BEFORE_COOLDOWN) return 0L
        return when (failedAttempts - MAX_ATTEMPTS_BEFORE_COOLDOWN) {
            0 -> 30L
            1 -> 60L
            2 -> 120L
            else -> 300L
        }
    }
}
