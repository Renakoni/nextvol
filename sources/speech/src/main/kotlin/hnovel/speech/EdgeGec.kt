package hnovel.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sec-MS-GEC v2. Adapted from rany2/edge-tts drm.py, LGPL-3.0.
 * Source: bd44c1d1aaebdc1db7f47456ee12431b42ec7cac (2026-10-14).
 * Int overflow intentionally implements unsigned arithmetic modulo 2^32. */
internal object EdgeGec {
    const val VERSION = "2-143.0.3650.75"
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    fun token(nowMillis: Long): String {
        val seconds = nowMillis / 1000 + 11644473600L
        val ticks = (seconds - seconds % 300) * 10000000L
        val data = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        return hex(checksum(data, digest)) + "-" + hex(digest)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun sigma0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun sigma1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)
    private fun expand(words: IntArray, start: Int = 16) {
        for (i in start until 64) words[i] = sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]
    }

    private fun rounds(initial: IntArray, words: IntArray, start: Int = 0): IntArray {
        var a = initial[0]; var b = initial[1]; var c = initial[2]; var d = initial[3]
        var e = initial[4]; var f = initial[5]; var g = initial[6]; var h = initial[7]
        for (i in start until 64) {
            val k = if (start == 14 && i == 14) 0xC9DC06A7.toInt() else ROUND_CONSTANTS[i]
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) +
                (g xor (e and (f xor g))) + k + words[i]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        // Recover the first compression block from the service's fixed prefix state.
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
        words[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16 until 30) {
            var value = SCHEDULE[i - 16]
            if (i - 2 >= 14) value += sigma1(words[i - 2]) xor (if (i == 16) 0x000B92C0 else 0)
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) value += sigma0(words[i - 15]) xor (if (i == 29) 0x059C0B80 else 0)
            words[i] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            0xC9DC06A7.toInt() - ROUND_CONSTANTS[14]
        expand(words, 31)
        var state = rounds(PREFIX_STATE, words, 14).mapIndexed { i, value -> value + INITIAL[i] }.toIntArray()
        val length = 57 + data.size
        val padding = Math.floorMod(55 - length, 64)
        val remaining = ByteBuffer.allocate(data.size - 7 + 1 + padding + 8)
        remaining.put(data, 7, data.size - 7).put(0x80.toByte())
        remaining.position(remaining.capacity() - 8)
        remaining.putLong(length.toLong() * 8).flip()
        while (remaining.hasRemaining()) {
            for (i in 0 until 16) words[i] = remaining.int
            expand(words)
            state = rounds(state, words).mapIndexed { i, value -> value + state[i] }.toIntArray()
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private fun checksum(data: ByteArray, digest: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = keys.int or 1
        val keyB = keys.int or 1
        val factors = intArrayOf(keyA * 0x82893933.toInt(), keyB * 0x82893933.toInt())
        val terms = COEFFICIENTS.mapIndexed { i, value -> value * factors[i % 2] }.toMutableList()
        val offsets = OFFSETS.toMutableList()
        val padded = ByteBuffer.allocate((data.size + 1 + 7) / 8 * 8).order(ByteOrder.LITTLE_ENDIAN)
        padded.put(0).put(data).rewind()
        var index = 0
        while (padded.hasRemaining()) {
            val word = padded.int
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = Integer.rotateLeft(word, 16) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) { term += 0xF2F90B1A.toInt() * factors[0]; offset += 0x0B1A0000 }
            terms.add(term); offsets.add(offset); index++
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        for (i in terms.indices step 2) {
            val q0 = Integer.rotateLeft(offsets[i] + terms[i], 16) * 0x76F50F34
            swapAcc += Integer.rotateLeft(q0, 16) * 0xC3EFEA97.toInt() + q0
            val q1 = Integer.rotateLeft(offsets[i + 1] + terms[i + 1], 16) * 0x1881CE3C
            swapLast = Integer.rotateLeft(q1, 16) * 0x35BD1EC9 + q1
            swapAcc += swapLast
            state = state * keyA + terms[i]
            state = Integer.rotateLeft(state, 16) * 0xCF98B111.toInt()
            state = Integer.rotateLeft(state, 16) * 0x87085B9F.toInt()
            state = Integer.rotateLeft(state, 16) * 0x12CEB96D
            state = Integer.rotateLeft(state, 16) * 0x257E1D83
            acc += state
            var t = state * keyB + terms[i + 1]
            t = Integer.rotateLeft(t, 16) * 0xA27416F5.toInt()
            t = Integer.rotateLeft(t, 16) * 0xD38396FF.toInt()
            state = Integer.rotateLeft(Integer.rotateLeft(t, 16) * 0x7C932B89, 16) * 0xBFA49F69.toInt()
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }

    private val INITIAL = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )
    private val PREFIX_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2,
    )
    private val SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA,
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564,
    )
    private val ROUND_CONSTANTS = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )
    private val COEFFICIENTS = intArrayOf(
        0xE82E91B1.toInt(), 0x44DE24C6, 0x3BEB65BC, 0xC89C5933.toInt(),
        0x8D1EC211.toInt(), 0x314EF5D5, 0xE92491B1.toInt(), 0xF0A5B9D5.toInt(),
        0x06064CF3, 0xDA79FA60.toInt(), 0xBED20560.toInt(), 0x89F9C9F8.toInt(),
        0x35FA38C6, 0xCB49E3C6.toInt(),
    )
    private val OFFSETS = intArrayOf(
        0xB32700D0.toInt(), 0x88DFF51D.toInt(), 0x29514038, 0xB328CBDF.toInt(),
        0xD405B4D5.toInt(), 0x26E55F3C, 0x607801C6, 0x82B2C1FB.toInt(),
        0xFA51A3DA.toInt(), 0x207CC464, 0x3C3C74F3, 0xD2D14B61.toInt(),
        0xBB04A2C1.toInt(), 0xC5626167.toInt(),
    )
}
