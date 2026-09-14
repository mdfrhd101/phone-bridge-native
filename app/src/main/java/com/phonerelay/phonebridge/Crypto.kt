package com.phonerelay.phonebridge

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end payload encryption for PhoneBridge.
 *
 * Both phones derive the SAME AES-256 key from the shared pair code (PBKDF2), so anything
 * relayed through public ntfy topics — or served over the LAN — is unreadable to anyone who
 * does not know the pair code. The relay servers only ever see opaque ciphertext.
 *
 * Wire format:  "ENC1:" + Base64( iv[12] || ciphertext+GCMtag )
 *
 * decrypt() is tolerant: a string that is not in ENC1 format is returned unchanged, so
 * legacy plaintext messages still cached on the relay during an upgrade window keep working.
 */
object Crypto {
    private const val PREFIX = "ENC1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 12000
    // Static salt is fine here: the secret is the pair code, the salt only domain-separates.
    private val SALT = "PhoneBridge::v2::pairkey".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    @Volatile private var cachedCode: String? = null
    @Volatile private var cachedKey: SecretKeySpec? = null

    private fun keyFor(pairCode: String): SecretKeySpec {
        val code = pairCode.ifBlank { "realme-xperia" }
        cachedKey?.let { if (cachedCode == code) return it }
        synchronized(this) {
            cachedKey?.let { if (cachedCode == code) return it }
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(code.toCharArray(), SALT, PBKDF2_ITERATIONS, 256)
            val keyBytes = factory.generateSecret(spec).encoded
            val key = SecretKeySpec(keyBytes, "AES")
            cachedCode = code
            cachedKey = key
            return key
        }
    }

    fun isEncrypted(text: String?): Boolean = text != null && text.startsWith(PREFIX)

    /** Encrypt [plain] with the key derived from [pairCode]. Never throws; returns plaintext on failure. */
    fun encrypt(pairCode: String, plain: String): String {
        return try {
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(pairCode), GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ct, 0, combined, iv.size, ct.size)
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    /**
     * Decrypt an ENC1 string with the key derived from [pairCode].
     * If [text] is not ENC1-formatted it is returned unchanged (legacy plaintext).
     * Returns null only when it IS ENC1 but cannot be decrypted (wrong code / corrupt).
     */
    fun decrypt(pairCode: String, text: String?): String? {
        if (text == null) return null
        if (!text.startsWith(PREFIX)) return text
        return try {
            val combined = Base64.decode(text.substring(PREFIX.length), Base64.NO_WRAP)
            if (combined.size <= IV_LEN) return null
            val iv = combined.copyOfRange(0, IV_LEN)
            val ct = combined.copyOfRange(IV_LEN, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyFor(pairCode), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
