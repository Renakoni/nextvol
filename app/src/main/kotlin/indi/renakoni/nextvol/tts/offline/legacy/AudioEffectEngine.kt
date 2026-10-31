package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An audio-package voice inserts its selected recording; it does not narrate the supplied text. */
internal class AudioEffectEngine(@Suppress("UNUSED_PARAMETER") context: Context, directory: File, voice: OfflineVoiceConfig) : OfflineEngine {
    private val recording = File(directory, voice.parameters)
    private val track: Int
    private var outputRate: Int
    private var closed = false
    @Volatile private var cancelled = false
    override val sampleRate: Int get() = outputRate

    init {
        require(voice.parameters.isNotBlank() && recording.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && recording.isFile) {
            "Missing or escaped audio-package recording: ${voice.parameters}"
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(recording.absolutePath)
            track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Audio-package recording has no audio track")
            outputRate = extractor.getTrackFormat(track).getInteger(MediaFormat.KEY_SAMPLE_RATE)
            require(outputRate > 0) { "Audio-package recording has an invalid sample rate" }
        } finally { extractor.release() }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(!closed) { "Audio-package engine is closed" }
        cancelled = false
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(recording.absolutePath)
            extractor.selectTrack(track)
            var format = extractor.getTrackFormat(track)
            outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            if (mime == "audio/raw") {
                val input = ByteBuffer.allocate(64 * 1024)
                while (!cancelled) {
                    input.clear()
                    val count = extractor.readSampleData(input, 0)
                    if (count < 0) break
                    input.position(0)
                    input.limit(count)
                    val pcm = monoPcm(input, format)
                    emit(pcm, pcm.size)
                    extractor.advance()
                }
            } else {
                val codec = MediaCodec.createDecoderByType(mime)
                decoder = codec
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                codec.configure(format, null, null, 0)
                codec.start()
                started = true
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var emitted = false
                var progressAt = SystemClock.elapsedRealtime()
                while (!cancelled && !outputEnded) {
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = checkNotNull(codec.getInputBuffer(index))
                            input.clear()
                            val count = extractor.readSampleData(input, 0)
                            if (count < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(index, 0, count, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                            progressAt = SystemClock.elapsedRealtime()
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            format = codec.outputFormat
                            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            check(rate > 0 && (!emitted || rate == outputRate)) { "Audio sample rate changed during the recording" }
                            outputRate = rate
                            progressAt = SystemClock.elapsedRealtime()
                        }
                        else -> if (index >= 0) {
                            try {
                                if (!cancelled && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val output = checkNotNull(codec.getOutputBuffer(index))
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    val pcm = monoPcm(output, format)
                                    emit(pcm, pcm.size)
                                    emitted = true
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                progressAt = SystemClock.elapsedRealtime()
                            } finally { codec.releaseOutputBuffer(index, false) }
                        }
                    }
                    check(SystemClock.elapsedRealtime() - progressAt < 30_000) { "Audio decoder stopped producing data" }
                }
            }
            if (cancelled) throw CancellationException("Audio insertion cancelled")
        } finally {
            try { if (started) decoder?.stop() }
            finally { try { decoder?.release() } finally { extractor.release() } }
        }
    }

    override fun cancel() { cancelled = true }
    @Synchronized override fun close() { closed = true }

    private fun monoPcm(input: ByteBuffer, format: MediaFormat): ByteArray {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(channels > 0) { "Audio recording has no channels" }
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("Audio decoder returned unsupported PCM encoding: $encoding")
        }
        require(input.remaining() % (channels * sampleBytes) == 0) { "Audio decoder returned an incomplete PCM frame" }
        input.order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteArray(input.remaining() / (channels * sampleBytes) * 2)
        for (offset in output.indices step 2) {
            var total = 0.0
            repeat(channels) {
                total += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) * 256.0
                    AudioFormat.ENCODING_PCM_FLOAT -> input.float.let { require(it.isFinite()) { "Non-finite PCM sample" }; it.toDouble() * 32768.0 }
                    else -> input.short.toDouble()
                }
            }
            val sample = (total / channels).toInt().coerceIn(-32768, 32767)
            output[offset] = sample.toByte()
            output[offset + 1] = (sample shr 8).toByte()
        }
        return output
    }
}
