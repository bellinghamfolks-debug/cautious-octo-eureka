package com.abdullah.visionbridge.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only AES-GCM ciphertext and IV in private preferences. The AES key is
 * generated inside Android Keystore and cannot be exported by the application.
 */
class AndroidKeystoreApiKeyStore(context: Context) : ApiKeyStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun save(apiKey: String) = withContext(Dispatchers.IO) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "مفتاح API فارغ" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))

        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun get(): String? = withContext(Dispatchers.IO) {
        val ivText = preferences.getString(KEY_IV, null) ?: return@withContext null
        val encryptedText = preferences.getString(KEY_CIPHERTEXT, null) ?: return@withContext null
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse {
            // Corrupt or invalidated material must never be returned as a key.
            preferences.edit().clear().apply()
            null
        }
    }

    override suspend fun hasKey(): Boolean = get()?.isNotBlank() == true

    override suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "secure_api_key"
        const val KEY_ALIAS = "vision_bridge_gemini_key_v1"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
