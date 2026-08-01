package com.tgwsproxy.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the MTProto secret encrypted with a non-exportable Android Keystore key. */
object SecureSecretStore {
    private const val KEY_ALIAS = "tgwsproxy.secret.v1"
    private const val SECURE_PREFS = "secure_proxy"
    private const val ENCRYPTED_SECRET = "secret_aes_gcm"
    private const val LEGACY_PREFS = "proxy"
    private const val LEGACY_SECRET = "secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun getOrCreate(context: Context): String {
        load(context)?.let { return it }

        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_SECRET, null)
            ?.takeIf(ProxyConfig::isValidSecret)
        val secret = legacy ?: ProxyConfig.generateSecret()
        save(context, secret)
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit { remove(LEGACY_SECRET) }
        return secret
    }

    fun load(context: Context): String? {
        val encoded = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .getString(ENCRYPTED_SECRET, null)
            ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_SIZE)
            val iv = payload.copyOfRange(0, IV_SIZE)
            val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8).takeIf(ProxyConfig::isValidSecret)
        }.getOrNull()
    }

    fun save(context: Context, secret: String) {
        require(ProxyConfig.isValidSecret(secret)) { "Invalid proxy secret" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .edit { putString(ENCRYPTED_SECRET, Base64.encodeToString(payload, Base64.NO_WRAP)) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private const val IV_SIZE = 12
}
