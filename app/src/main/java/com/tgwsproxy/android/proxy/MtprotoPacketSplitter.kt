package com.tgwsproxy.android.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MtprotoPacketSplitter(relayInit: ByteArray, private val proto: ProtoTransport) {
    private val decrypt = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(relayInit.copyOfRange(8, 40), "AES"),
            IvParameterSpec(relayInit.copyOfRange(40, 56)),
        )
        update(ByteArray(64))
    }
    private var cipherBuffer = ByteArray(0)
    private var plainBuffer = ByteArray(0)
    private var disabled = false

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuffer += chunk
        plainBuffer += decrypt.update(chunk)

        val parts = mutableListOf<ByteArray>()
        while (cipherBuffer.isNotEmpty()) {
            val packetLength = nextPacketLength() ?: break
            if (packetLength <= 0) {
                parts += cipherBuffer
                cipherBuffer = ByteArray(0)
                plainBuffer = ByteArray(0)
                disabled = true
                break
            }

            parts += cipherBuffer.copyOfRange(0, packetLength)
            cipherBuffer = cipherBuffer.copyOfRange(packetLength, cipherBuffer.size)
            plainBuffer = plainBuffer.copyOfRange(packetLength, plainBuffer.size)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuffer.isEmpty()) return emptyList()
        val tail = cipherBuffer
        cipherBuffer = ByteArray(0)
        plainBuffer = ByteArray(0)
        return listOf(tail)
    }

    private fun nextPacketLength(): Int? {
        return when (proto) {
            ProtoTransport.Abridged -> nextAbridgedLength()
            ProtoTransport.Intermediate,
            ProtoTransport.PaddedIntermediate -> nextIntermediateLength()
        }
    }

    private fun nextAbridgedLength(): Int? {
        val first = plainBuffer.firstOrNull()?.toInt()?.and(0xff) ?: return null
        val headerLength: Int
        val payloadLength: Int
        if (first == 0x7f || first == 0xff) {
            if (plainBuffer.size < 4) return null
            payloadLength = (
                (plainBuffer[1].toInt() and 0xff) or
                    ((plainBuffer[2].toInt() and 0xff) shl 8) or
                    ((plainBuffer[3].toInt() and 0xff) shl 16)
                ) * 4
            headerLength = 4
        } else {
            payloadLength = (first and 0x7f) * 4
            headerLength = 1
        }
        if (payloadLength <= 0) return 0
        val packetLength = headerLength + payloadLength
        return if (plainBuffer.size >= packetLength) packetLength else null
    }

    private fun nextIntermediateLength(): Int? {
        if (plainBuffer.size < 4) return null
        val payloadLength = ByteBuffer.wrap(plainBuffer, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int and 0x7fffffff
        if (payloadLength <= 0) return 0
        val packetLength = 4 + payloadLength
        return if (plainBuffer.size >= packetLength) packetLength else null
    }
}

enum class ProtoTransport {
    Abridged,
    Intermediate,
    PaddedIntermediate,
}

fun ByteArray.toProtoTransport(): ProtoTransport {
    return when {
        contentEquals(MtprotoCrypto.PROTO_TAG_ABRIDGED) -> ProtoTransport.Abridged
        contentEquals(MtprotoCrypto.PROTO_TAG_INTERMEDIATE) -> ProtoTransport.Intermediate
        else -> ProtoTransport.PaddedIntermediate
    }
}
