package indi.renakoni.nextvol.tts

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

interface SpeechSynthesizer : AutoCloseable {
    suspend fun open(settings: SpeechSettings): String
    /** Returns only after a complete, validated file is committed to [output]. */
    suspend fun synthesize(text: String, output: File): List<SpeechTiming>
    /** Signal cancellation on the session thread without blocking or releasing in-flight resources. */
    fun cancel()
    /** May block on teardown. Call off the main thread after synthesis has stopped. */
    override fun close()
}

/** Android's file synthesis callback writes WAVE. Reject empty/partial output before playback. */
internal fun validateSpeechWav(file: File) {
    fun invalid(): Nothing = throw SpeechException(SpeechError.InvalidAudio)
    if (file.length() !in 44..(8L * 1024 * 1024)) invalid()
    try {
        RandomAccessFile(file, "r").use { input ->
            fun uint() = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
            fun ushort() = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))
            if (input.readInt() != 0x52494646) invalid() // RIFF
            val size = uint()
            if (size + 8 != input.length() || input.readInt() != 0x57415645) invalid() // WAVE
            var blockSize = 0
            var dataFound = false
            while (input.filePointer + 8 <= input.length()) {
                val chunk = input.readInt()
                val length = uint()
                val end = input.filePointer + length
                if (end > input.length()) invalid()
                when (chunk) {
                    0x666d7420 -> { // fmt
                        if (length < 16) invalid()
                        val format = ushort()
                        val channels = ushort()
                        val sampleRate = uint()
                        val byteRate = uint()
                        blockSize = ushort()
                        val bits = ushort()
                        if (format !in setOf(1, 3) || channels !in 1..2 || sampleRate !in 8000..192000 ||
                            bits !in setOf(8, 16, 24, 32) || format == 3 && bits != 32 ||
                            blockSize != channels * bits / 8 || byteRate != sampleRate * blockSize) invalid()
                    }
                    0x64617461 -> { // data
                        if (blockSize == 0 || length == 0L || length % blockSize != 0L) invalid()
                        dataFound = true
                    }
                }
                input.seek(end + length % 2)
            }
            if (!dataFound) invalid()
        }
    } catch (_: EOFException) { invalid() }
}
