package com.vellum.notes.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * End-to-end encryption for portable payloads (encrypted backups, sync-folder
 * snapshots). AES-256-GCM with a PBKDF2-SHA256 stretched passphrase key —
 * pure JCA, no new dependencies, fully offline and unit-testable.
 *
 * Wire format: `MAGIC(7) | salt(16) | iv(12) | ciphertext+tag`. The magic lets
 * importers tell encrypted payloads apart from plain ZIPs before asking for a
 * passphrase. Wrong passphrases and any tampering fail closed with an
 * [AEADBadTagException] (a [javax.crypto.AEADBadTagException]).
 *
 * The passphrase is never stored: devices sharing a sync folder must be given
 * it once by the user. Neither the transport (Syncthing) nor the storage ever
 * sees plaintext.
 */
object SyncCrypto {

    private val MAGIC = "VELLUM1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 200_000
    const val MIN_PASSPHRASE_CHARS = 8

    fun isEncrypted(blob: ByteArray): Boolean {
        if (blob.size < MAGIC.size) return false
        return blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_CHARS) {
            "Passphrase must be at least $MIN_PASSPHRASE_CHARS characters"
        }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        try {
            val key = stretch(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val cipherText = cipher.doFinal(plain)
            return MAGIC + salt + iv + cipherText
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * @throws javax.crypto.AEADBadTagException on wrong passphrase or tampering.
     * @throws IllegalArgumentException when [blob] is not a Vellum payload.
     */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(isEncrypted(blob)) { "Not a Vellum encrypted payload" }
        require(passphrase.size >= MIN_PASSPHRASE_CHARS) {
            "Passphrase must be at least $MIN_PASSPHRASE_CHARS characters"
        }
        try {
            val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
            val iv = blob.copyOfRange(
                MAGIC.size + SALT_BYTES,
                MAGIC.size + SALT_BYTES + IV_BYTES,
            )
            val cipherText = blob.copyOfRange(MAGIC.size + SALT_BYTES + IV_BYTES, blob.size)
            val key = stretch(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(cipherText)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun stretch(passphrase: CharArray, salt: ByteArray): javax.crypto.SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val raw = factory.generateSecret(spec)
            // PBKDF2 keys carry the KDF algorithm name: re-wrap as AES.
            javax.crypto.spec.SecretKeySpec(raw.encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
