package com.thaweekarn.slipsync.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoManager {

    private const val KEY_ALIAS = "slipsync_auth_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    // Detect JVM unit test runner (Robolectric)
    val isTestEnvironment: Boolean by lazy {
        try {
            Class.forName("org.robolectric.Robolectric")
            true
        } catch (e: Throwable) {
            false
        }
    }

    // In unit test runner ONLY, cached key facilitates execution without Android hardware daemon
    @Volatile
    private var testCachedSecretKey: SecretKey? = null

    private val keyStore: KeyStore by lazy {
        if (isTestEnvironment) {
            try {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            } catch (e: Exception) {
                KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            }
        } else {
            // Production Android: STRICTLY require hardware-backed AndroidKeyStore
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        }
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        if (isTestEnvironment) {
            testCachedSecretKey?.let { return it }
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val generated = keyGen.generateKey()
            testCachedSecretKey = generated
            return generated
        }

        // Production path: Pure AndroidKeyStore without ephemeral fallback
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw SecurityException("Failed to retrieve persistent key from AndroidKeyStore")
        return entry.secretKey
    }

    /**
     * Encrypts plain text using Keystore AES-256-GCM.
     * Returns Base64-encoded string containing [IV (12 bytes) + Ciphertext].
     */
    @Synchronized
    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts Base64-encoded string containing [IV (12 bytes) + Ciphertext].
     */
    @Synchronized
    fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isBlank()) return ""
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH) return ""

        val iv = ByteArray(IV_LENGTH)
        val cipherText = ByteArray(combined.size - IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH)
        System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.size)

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
