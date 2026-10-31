package indi.renakoni.nextvol.tts.offline

import java.io.File
import java.io.RandomAccessFile

/** Streams the engines' PCM16 mono callbacks into the existing complete-WAV playback contract. */
internal class OfflinePcmFile(file: File, private val sampleRate: Int) : AutoCloseable {
    private val output: RandomAccessFile
    private var bytes = 0

    init {
        require(sampleRate in 8000..192000)
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(0)
            output.write(ByteArray(44))
        } catch (failure: Throwable) {
            output.close()
            throw failure
        }
    }

    @Synchronized
    fun append(buffer: ByteArray, count: Int) {
        require(count in 0..buffer.size)
        check(count <= 8 * 1024 * 1024 - 44 - bytes) { "Offline audio exceeds the playback file limit" }
        output.write(buffer, 0, count)
        bytes += count
    }

    @Synchronized
    fun finish(finalSampleRate: Int = sampleRate) {
        check(bytes > 0 && bytes % 2 == 0) { "Offline engine did not produce PCM16 audio" }
        require(finalSampleRate in 8000..192000)
        output.seek(0)
        fun u16(value: Int) { output.write(value); output.write(value ushr 8) }
        fun u32(value: Int) { u16(value); u16(value ushr 16) }
        output.writeBytes("RIFF"); u32(36 + bytes); output.writeBytes("WAVEfmt ")
        u32(16); u16(1); u16(1); u32(finalSampleRate); u32(finalSampleRate * 2); u16(2); u16(16)
        output.writeBytes("data"); u32(bytes)
        output.fd.sync()
    }

    override fun close() = output.close()
}
