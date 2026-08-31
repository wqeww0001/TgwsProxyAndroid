package com.tgwsproxy.android.util

/**
 * Compact, zero-dependency QR Code matrix generator in pure Kotlin.
 * Supports Byte mode (UTF-8 / ASCII) for encoding proxy URLs.
 */
object QrGenerator {

    class QrMatrix(val size: Int, val modules: Array<BooleanArray>) {
        fun isDark(row: Int, col: Int): Boolean = modules[row][col]
    }

    fun encode(text: String): QrMatrix {
        val data = text.toByteArray(Charsets.UTF_8)
        // Choose minimum version that fits data (Version 1-6)
        val version = when {
            data.size <= 17 -> 1
            data.size <= 32 -> 2
            data.size <= 53 -> 3
            data.size <= 78 -> 4
            data.size <= 106 -> 5
            else -> 6
        }
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        fun setModule(r: Int, c: Int, dark: Boolean, isReserved: Boolean = true) {
            if (r in 0 until size && c in 0 until size) {
                modules[r][c] = dark
                if (isReserved) reserved[r][c] = true
            }
        }

        fun drawFinder(top: Int, left: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val rAbs = top + r
                    val cAbs = left + c
                    if (r in 0..6 && c in 0..6) {
                        val isDark = r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4)
                        setModule(rAbs, cAbs, isDark)
                    } else {
                        setModule(rAbs, cAbs, false)
                    }
                }
            }
        }

        // Draw 3 finders
        drawFinder(0, 0)
        drawFinder(0, size - 7)
        drawFinder(size - 7, 0)

        // Alignment patterns for version >= 2
        if (version >= 2) {
            val alignPos = when (version) {
                2 -> intArrayOf(6, 18)
                3 -> intArrayOf(6, 22)
                4 -> intArrayOf(6, 26)
                5 -> intArrayOf(6, 30)
                6 -> intArrayOf(6, 34)
                else -> intArrayOf(6, 34)
            }
            for (r in alignPos) {
                for (c in alignPos) {
                    if (reserved[r][c]) continue
                    for (dr in -2..2) {
                        for (dc in -2..2) {
                            val isDark = dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0)
                            setModule(r + dr, c + dc, isDark)
                        }
                    }
                }
            }
        }

        // Timing patterns
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            if (!reserved[6][i]) setModule(6, i, dark)
            if (!reserved[i][6]) setModule(i, 6, dark)
        }

        // Dark module
        setModule(4 * version + 9, 8, true)

        // Reserve format info areas
        for (i in 0..8) {
            if (!reserved[8][i]) reserved[8][i] = true
            if (!reserved[i][8]) reserved[i][8] = true
        }
        for (i in 0..7) {
            val c = size - 1 - i
            if (!reserved[8][c]) reserved[8][c] = true
        }
        for (i in 0..6) {
            val r = size - 1 - i
            if (!reserved[r][8]) reserved[r][8] = true
        }

        // Bit stream encoding (Byte mode: mode=0100, length, data, padding)
        val bitBuffer = mutableListOf<Boolean>()
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bitBuffer.add(((value shr i) and 1) == 1)
            }
        }

        appendBits(0b0100, 4) // Byte mode
        appendBits(data.size, 8) // Character count
        for (b in data) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        val totalDataBits = totalDataBitsFor(version)
        // Terminator
        val termLen = (totalDataBits - bitBuffer.size).coerceIn(0, 4)
        appendBits(0, termLen)

        // Pad to byte
        while (bitBuffer.size % 8 != 0) {
            bitBuffer.add(false)
        }

        // Pad bytes (0xEC, 0x11)
        var padByte = 0xEC
        while (bitBuffer.size < totalDataBits) {
            appendBits(padByte, 8)
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        // Simple Reed-Solomon style error correction filling
        val allCodewords = generateCodewords(bitBuffer, version)

        // Place data & EC bits in zigzag order
        var bitIndex = 0
        var right = size - 1
        while (right > 0) {
            if (right == 6) right-- // Skip vertical timing column
            for (vertical in 0 until size) {
                for (j in 0..1) {
                    val col = right - j
                    val row = if (((right + 1) / 2) % 2 == 1) size - 1 - vertical else vertical
                    if (!reserved[row][col]) {
                        var bit = false
                        if (bitIndex < allCodewords.size) {
                            bit = allCodewords[bitIndex++]
                        }
                        // Apply Mask 0: (row + col) % 2 == 0
                        if ((row + col) % 2 == 0) {
                            bit = !bit
                        }
                        modules[row][col] = bit
                    }
                }
            }
            right -= 2
        }

        // Write Format Information (Mask 0, Error Correction L = 01)
        val formatBits = 0b111011111000100 // Precalculated format string for L-0
        for (i in 0..14) {
            val bit = ((formatBits shr (14 - i)) and 1) == 1
            if (i < 6) setModule(8, i, bit)
            else if (i < 8) setModule(8, i + 1, bit)
            else if (i == 8) setModule(7, 8, bit)
            else setModule(14 - i, 8, bit)

            if (i < 8) setModule(size - 1 - i, 8, bit)
            else setModule(8, size - 15 + i, bit)
        }

        return QrMatrix(size, modules)
    }

    private fun totalDataBitsFor(v: Int): Int = when (v) {
        1 -> 152
        2 -> 272
        3 -> 440
        4 -> 640
        5 -> 864
        else -> 1088
    }

    private fun generateCodewords(dataBits: List<Boolean>, version: Int): List<Boolean> {
        val numDataBytes = dataBits.size / 8
        val dataBytes = IntArray(numDataBytes)
        for (i in 0 until numDataBytes) {
            var v = 0
            for (b in 0 until 8) {
                if (dataBits[i * 8 + b]) v = v or (1 shl (7 - b))
            }
            dataBytes[i] = v
        }

        val ecBytesCount = when (version) {
            1 -> 7
            2 -> 10
            3 -> 15
            4 -> 20
            5 -> 26
            else -> 36
        }

        val ecBytes = computeRsEc(dataBytes, ecBytesCount)
        val result = mutableListOf<Boolean>()
        for (b in dataBytes) {
            for (i in 7 downTo 0) result.add(((b shr i) and 1) == 1)
        }
        for (b in ecBytes) {
            for (i in 7 downTo 0) result.add(((b shr i) and 1) == 1)
        }
        return result
    }

    // Galois Field arithmetic for standard QR Reed-Solomon
    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            expTable[i + 255] = x
            logTable[x] = i
            x = (x shl 1)
            if (x >= 256) x = x xor 0x11D
        }
    }

    private fun gmul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a] + logTable[b]]
    }

    private fun computeRsEc(data: IntArray, ecCount: Int): IntArray {
        // Build generator polynomial
        var gen = intArrayOf(1)
        for (i in 0 until ecCount) {
            val nextGen = IntArray(gen.size + 1)
            for (j in gen.indices) {
                nextGen[j] = nextGen[j] xor gmul(gen[j], expTable[i])
                nextGen[j + 1] = nextGen[j + 1] xor gen[j]
            }
            gen = nextGen
        }

        val res = IntArray(ecCount)
        for (b in data) {
            val factor = b xor res[0]
            System.arraycopy(res, 1, res, 0, ecCount - 1)
            res[ecCount - 1] = 0
            for (i in 0 until ecCount) {
                res[i] = res[i] xor gmul(gen[i], factor)
            }
        }
        return res
    }
}
