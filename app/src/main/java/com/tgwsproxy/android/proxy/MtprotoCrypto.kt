package com.tgwsproxy.android.proxy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ClientHandshake(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val clientDecPrekeyIv: ByteArray,
)

class CryptoContext(
    val clientDecrypt: Cipher,
    val clientEncrypt: Cipher,
    val telegramEncrypt: Cipher,
    val telegramDecrypt: Cipher,
)

object MtprotoCrypto {
    val PROTO_TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    private val secureRandom = SecureRandom()
    private val zero64 = ByteArray(HANDSHAKE_LEN)
    private val reservedFirstBytes = setOf(0xef)
    private val reservedStarts = setOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44),
        byteArrayOf(0x50, 0x4f, 0x53, 0x54),
        byteArrayOf(0x47, 0x45, 0x54, 0x20),
        PROTO_TAG_ABRIDGED,
        PROTO_TAG_INTERMEDIATE,
        PROTO_TAG_SECURE,
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): ClientHandshake? {
        if (handshake.size != HANDSHAKE_LEN) return null

        val decPrekeyIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val decPrekey = decPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val decIv = decPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val decKey = sha256(decPrekey + secret)

        val decrypted = aesCtr(decKey, decIv).update(handshake)
        val protoTag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        if (!protoTag.contentEquals(PROTO_TAG_ABRIDGED) &&
            !protoTag.contentEquals(PROTO_TAG_INTERMEDIATE) &&
            !protoTag.contentEquals(PROTO_TAG_SECURE)
        ) {
            return null
        }

        val dcIdx = littleEndianShort(decrypted, DC_IDX_POS).toInt()
        return ClientHandshake(
            dcId = kotlin.math.abs(dcIdx),
            isMedia = dcIdx < 0,
            protoTag = protoTag,
            clientDecPrekeyIv = decPrekeyIv,
        )
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val random = ByteArray(HANDSHAKE_LEN)
        while (true) {
            secureRandom.nextBytes(random)
            if ((random[0].toInt() and 0xff) in reservedFirstBytes) continue
            if (reservedStarts.any { random.copyOfRange(0, 4).contentEquals(it) }) continue
            if (random.copyOfRange(4, 8).all { it == 0.toByte() }) continue
            break
        }

        val encKey = random.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val encIv = random.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val encryptedFull = aesCtr(encKey, encIv).update(random)
        val tailPlain = protoTag + putLittleEndianShort(dcIdx.toShort()) + randomBytes(2)
        val result = random.copyOf()

        for (i in 0 until 8) {
            val pos = PROTO_TAG_POS + i
            val keystream = encryptedFull[pos].toInt() xor random[pos].toInt()
            result[pos] = (tailPlain[i].toInt() xor keystream).toByte()
        }
        return result
    }

    fun buildCryptoContext(
        clientDecPrekeyIv: ByteArray,
        secret: ByteArray,
        relayInit: ByteArray,
    ): CryptoContext {
        val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val clientDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val clientDecKey = sha256(clientDecPrekey + secret)

        val clientEncPrekeyIv = clientDecPrekeyIv.reversedCopy()
        val clientEncKey = sha256(clientEncPrekeyIv.copyOfRange(0, PREKEY_LEN) + secret)
        val clientEncIv = clientEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val clientDecrypt = aesCtr(clientDecKey, clientDecIv)
        val clientEncrypt = aesCtr(clientEncKey, clientEncIv)
        clientDecrypt.update(zero64)

        val relayEncKey = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val relayDecPrekeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
            .reversedCopy()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)

        val telegramEncrypt = aesCtr(relayEncKey, relayEncIv)
        val telegramDecrypt = aesCtr(relayDecKey, relayDecIv)
        telegramEncrypt.update(zero64)

        return CryptoContext(
            clientDecrypt = clientDecrypt,
            clientEncrypt = clientEncrypt,
            telegramEncrypt = telegramEncrypt,
            telegramDecrypt = telegramDecrypt,
        )
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun aesCtr(key: ByteArray, iv: ByteArray): Cipher {
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    private fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    private const val HANDSHAKE_LEN = 64
    private const val SKIP_LEN = 8
    private const val PREKEY_LEN = 32
    private const val KEY_LEN = 32
    private const val IV_LEN = 16
    private const val PROTO_TAG_POS = 56
    private const val DC_IDX_POS = 60

}
