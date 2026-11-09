package hnovel.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Services can return WAV despite declaring MP3; subsequent RIFF headers are not audio frames. */
internal fun onlineSpeechBytes(parts: List<ByteArray>): ByteArray {
    if (parts.size == 1) return parts.single()
    if (parts.none { it.waveHeader() }) {
        val size = parts.sumOf { it.size.toLong() }
        if (size > Int.MAX_VALUE) throw HttpSpeechException(HttpSpeechError.TooLarge)
        return ByteArray(size.toInt()).also { output ->
            var offset = 0
            for (part in parts) { part.copyInto(output, offset); offset += part.size }
        }
    }
    val waves = parts.map { it.pcmWave() }
    val format = waves.first().format
    if (waves.any { !it.format.contentEquals(format) }) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    val frameSize = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN).getShort(12).toInt() and 0xffff
    if (frameSize == 0 || waves.any { wave -> wave.data.sumOf { it.last - it.first + 1 } % frameSize != 0 })
        throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    val dataSize = waves.sumOf { wave -> wave.data.sumOf { (it.last - it.first + 1).toLong() } }
    val size = 44 + dataSize + (dataSize and 1)
    if (size > Int.MAX_VALUE) throw HttpSpeechException(HttpSpeechError.TooLarge)
    val output = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
    output.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(output.capacity() - 8)
    output.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).put(format)
    output.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize.toInt())
    for ((index, wave) in waves.withIndex()) {
        for (range in wave.data) output.put(parts[index], range.first, range.last - range.first + 1)
    }
    return output.array()
}

private class PcmWave(val format: ByteArray, val data: List<IntRange>)

private fun ByteArray.waveHeader() = size >= 12 && chunkAt(0, "RIFF") && chunkAt(8, "WAVE")

private fun ByteArray.chunkAt(offset: Int, name: String) = name.indices.all { this[offset + it] == name[it].code.toByte() }

private fun ByteArray.pcmWave(): PcmWave {
    if (!waveHeader()) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    var format: ByteArray? = null
    val data = mutableListOf<IntRange>()
    var offset = 12L
    while (offset + 8 <= size) {
        val chunk = offset.toInt()
        val length = buffer.getInt(chunk + 4).toLong() and 0xffffffffL
        val start = chunk + 8
        val end = start.toLong() + length
        if (end > size) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        when {
            chunkAt(chunk, "fmt ") -> {
                if (length < 16 || buffer.getShort(start).toInt() != 1)
                    throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                format = copyOfRange(start, start + 16)
            }
            chunkAt(chunk, "data") -> data += start until end.toInt()
        }
        offset = end + (length and 1)
    }
    if (format == null || data.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    return PcmWave(format, data)
}
