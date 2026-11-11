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

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    private val keyStore: KeyStore by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        } catch (e: Exception) {
            // JVM / Robolectric fallback
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
            }
        }
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            try {
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
                val generated = keyGenerator.generateKey()
                cachedSecretKey = generated
                return generated
            } catch (e: Exception) {
                // JVM test runner fallback
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                val key = keyGen.generateKey()
                cachedSecretKey = key
                return key
            }
        }

        val loadedKey = try {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
        } catch (e: Exception) {
            null
        } ?: run {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            keyGen.generateKey()
        }

        cachedSecretKey = loadedKey
        return loadedKey
    }

    /**
     * Encrypts plain text using Keystore AES-256-GCM.
     * Returns Base64-encoded string containing [IV (12 bytes) + Ciphertext].
     */
    @Synchronized
    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * Decrypts Base64-encoded string containing [IV (12 bytes) + Ciphertext].
     */
    @Synchronized
    fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isBlank()) return ""
        return try {
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

            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}
