package com.charles.livecaptionn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AssemblyAiKeyStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("assemblyai_secure", Context.MODE_PRIVATE)

    private val alias = "livecaption_assemblyai_aes"

    @Synchronized
    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key cannot be empty" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        check(
            preferences.edit()
                .putString("iv", iv)
                .putString("data", data)
                .commit()
        ) { "Could not save encrypted API key" }
    }

    @Synchronized
    fun read(): String? {
        val ivText = preferences.getString("iv", null) ?: return null
        val dataText = preferences.getString("data", null) ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(
                128,
                Base64.decode(ivText, Base64.NO_WRAP)
            )
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

            val decrypted = cipher.doFinal(
                Base64.decode(dataText, Base64.NO_WRAP)
            )
            decrypted.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun hasKey(): Boolean {
        return preferences.contains("data") && preferences.contains("iv")
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }
}
