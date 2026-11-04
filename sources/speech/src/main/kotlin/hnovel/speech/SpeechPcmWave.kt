package hnovel.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Container for SDK/HTTP mono 16-bit PCM entering the common encoded-audio pipeline. */
fun speechPcmWave(pcm: ByteArray, sampleRate: Int): ByteArray =
    ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
    }.array()

internal fun microsoftFormatAudio(audio: ByteArray, format: String): ByteArray {
    val sampleRate = Regex("raw-(\\d+)khz-16bit-mono-pcm").matchEntire(format)?.groupValues?.get(1)?.toInt()
    return if (sampleRate == null) audio else speechPcmWave(audio, sampleRate * 1000)
}
