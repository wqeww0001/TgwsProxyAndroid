package com.tgwsproxy.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.tgwsproxy.android.proxy.ProxyLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores MTProto secret encrypted with a non-exportable Android Keystore key. */
object SecureSecretStore {
    private val lock = Any()
    private const val KEY_ALIAS = "tgwsproxy.secret.v1"
    private const val SECURE_PREFS = "secure_proxy"
    private const val ENCRYPTED_SECRET = "secret_aes_gcm"
    private const val LEGACY_PREFS = "proxy"
    private const val LEGACY_SECRET = "secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile private var cachedSecret: String? = null

    fun getOrCreate(context: Context): String {
        cachedSecret?.takeIf(ProxyConfig::isValidSecret)?.let { return it }
        return synchronized(lock) {
            cachedSecret?.takeIf(ProxyConfig::isValidSecret)?.let { return@synchronized it }
            loadLocked(context)?.let {
                cachedSecret = it
                return@synchronized it
            }

            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_SECRET, null)
                ?.takeIf(ProxyConfig::isValidSecret)
            val secret = legacy ?: ProxyConfig.generateSecret()
            if (save(context, secret)) {
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                    .edit { remove(LEGACY_SECRET) }
            } else {
                ProxyLogger.w("Secret is available for this process but could not be persisted securely")
            }
            cachedSecret = secret
            secret
        }
    }

    fun load(context: Context): String? {
        cachedSecret?.takeIf(ProxyConfig::isValidSecret)?.let { return it }
        return synchronized(lock) {
            loadLocked(context)?.also { cachedSecret = it }
        }
    }

    private fun loadLocked(context: Context): String? {
        return decryptString(context, ENCRYPTED_SECRET)?.takeIf(ProxyConfig::isValidSecret)
    }

    fun save(context: Context, secret: String): Boolean {
        val clean = secret.trim()
        if (!ProxyConfig.isValidSecret(clean)) return false
        return synchronized(lock) {
            cachedSecret = clean
            persistEncrypted(context, ENCRYPTED_SECRET, clean)
        }
    }

    private fun decryptString(context: Context, keyName: String): String? {
        val encoded = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .getString(keyName, null)
            ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_SIZE)
            val iv = payload.copyOfRange(0, IV_SIZE)
            val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.onFailure {
            ProxyLogger.w("Encrypted value ($keyName) could not be read; repairing storage", it)
            context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
                .edit { remove(keyName) }
        }.getOrNull()
    }

    private fun persistEncrypted(context: Context, keyName: String, plaintext: String): Boolean {
        return runCatching { encryptAndPersist(context, keyName, plaintext) }
            .recoverCatching {
                ProxyLogger.w("Secure secret write failed ($keyName); recreating Android Keystore entry", it)
                resetSecureStorage(context)
                encryptAndPersist(context, keyName, plaintext)
            }
            .onFailure { ProxyLogger.e("Secure secret write failed after recovery ($keyName)", it) }
            .isSuccess
    }

    private fun encryptAndPersist(context: Context, keyName: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .edit { putString(keyName, Base64.encodeToString(payload, Base64.NO_WRAP)) }
    }

    private fun resetSecureStorage(context: Context) {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            .edit {
                remove(ENCRYPTED_SECRET)
            }
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
