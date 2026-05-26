package com.tgwsproxy.android.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun ByteArray.reversedCopy(): ByteArray = asList().asReversed().toByteArray()

fun littleEndianShort(bytes: ByteArray, offset: Int): Short {
    return ByteBuffer.wrap(bytes, offset, 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .short
}

fun putLittleEndianShort(value: Short): ByteArray {
    return ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value)
        .array()
}
