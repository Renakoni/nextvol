package indi.renakoni.nextvol.tts.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class SpeechAudioFrameAnchor(val beforeSilence: Long, val afterSilence: Long)

internal class ProcessedSpeechAudio(
    val sampleRate: Int,
    private val inputSampleRate: Int,
    private val inputFrames: Long,
    private val anchors: List<SpeechAudioFrameAnchor>,
) {
    val durationMs: Long get() = anchors.last().afterSilence * 1000 / sampleRate

    /** Map engine timing through tempo/resampling, then through each compressed silence block. */
    fun mapTimeMs(timeMs: Long): Long {
        val end = anchors.last()
        val frame = (timeMs.toDouble() * inputSampleRate / 1000 / inputFrames * end.beforeSilence)
            .toLong().coerceIn(0, end.beforeSilence)
        val found = anchors.binarySearchBy(frame) { it.beforeSilence }
        if (found >= 0) return anchors[found].afterSilence * 1000 / sampleRate
        val index = -found - 1
        val before = anchors[index - 1]
        val after = anchors[index]
        val outputFrame = before.afterSilence + (frame - before.beforeSilence) *
            (after.afterSilence - before.afterSilence) / (after.beforeSilence - before.beforeSilence)
        return outputFrame * 1000 / sampleRate
    }
}

/** Decode supported speech containers once, process bounded PCM blocks, then commit a complete WAV. */
internal suspend fun processSpeechAudio(
    input: File,
    output: File,
    processor: Int = 200,
    minimumSampleRate: Int = 16000,
    speed: Float = 1f,
    pitch: Float = 1f,
    volume: Float = 1f,
    checkSilence: Boolean = false,
    intentionalPause: Boolean = false,
): ProcessedSpeechAudio {
    val partial = File(output.parentFile, output.name + ".part")
    var ownsPartial = false
    var committed = false
    try {
        return withContext(Dispatchers.IO) {
            val task = currentCoroutineContext()
            require(!output.exists() && !partial.exists())
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var started = false
            var dsp: PcmAudioProcessor? = null
            var wave: SpeechWaveFile? = null
            var sourceRate = 0
            var outputRate = 0
            var sourceFrames = 0L
            var beforeSilence = 0L
            var afterSilence = 0L
            val anchors = mutableListOf(SpeechAudioFrameAnchor(0, 0))
            fun write(samples: ShortArray) {
                task.ensureActive()
                val bytes = ByteArray(samples.size * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
                checkNotNull(wave).append(bytes)
                val count = checkNotNull(dsp).lastUncompressedFrames
                if (count > 0) {
                    beforeSilence += count
                    afterSilence += samples.size
                    anchors += SpeechAudioFrameAnchor(beforeSilence, afterSilence)
                }
            }
            fun process(buffer: ByteBuffer, format: MediaFormat) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                if (dsp == null) {
                    sourceRate = rate
                    outputRate = maxOf(rate, minimumSampleRate)
                    dsp = PcmAudioProcessor(rate, outputRate, processor,
                        if (intentionalPause) 1f else speed, if (intentionalPause) 1f else pitch,
                        if (intentionalPause) 1f else volume,
                        checkSilence && !intentionalPause)
                    ownsPartial = true
                    wave = SpeechWaveFile(partial, outputRate)
                }
                check(rate == sourceRate) { "Audio sample rate changed during the recording" }
                val frameBytes = channels * speechPcmSampleBytes(encoding)
                while (buffer.hasRemaining()) {
                    task.ensureActive()
                    val size = minOf(buffer.remaining(), 4096 * frameBytes)
                    val block = buffer.slice().apply { limit(size) }
                    val samples = monoSpeechPcm(block, channels, encoding)
                    buffer.position(buffer.position() + size)
                    sourceFrames += samples.size
                    write(checkNotNull(dsp).process(samples, samples.size))
                }
            }
            try {
                extractor.setDataSource(input.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw SpeechException(SpeechError.InvalidAudio)
                extractor.selectTrack(track)
                var format = extractor.getTrackFormat(track)
                val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
                if (mime == "audio/raw") {
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        task.ensureActive()
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) break
                        buffer.position(0)
                        buffer.limit(count)
                        process(buffer, format)
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
                    var progressAt = SystemClock.elapsedRealtime()
                    while (!outputEnded) {
                        task.ensureActive()
                        if (!inputEnded) {
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = checkNotNull(codec.getInputBuffer(index))
                                buffer.clear()
                                val count = extractor.readSampleData(buffer, 0)
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
                                progressAt = SystemClock.elapsedRealtime()
                            }
                            else -> if (index >= 0) {
                                try {
                                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        process(buffer, format)
                                    }
                                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    progressAt = SystemClock.elapsedRealtime()
                                } finally { codec.releaseOutputBuffer(index, false) }
                            }
                        }
                        check(SystemClock.elapsedRealtime() - progressAt < 30_000) { "Audio decoder stopped producing data" }
                    }
                }
                val active = dsp ?: throw SpeechException(SpeechError.InvalidAudio)
                write(active.finish())
                checkNotNull(wave).finish()
                wave?.close()
                wave = null
                task.ensureActive()
                if (!partial.renameTo(output)) throw SpeechException(SpeechError.Storage)
                committed = true
                ProcessedSpeechAudio(outputRate, sourceRate, sourceFrames, anchors)
            } finally {
                try { if (started) decoder?.stop() }
                finally {
                    try { decoder?.release() }
                    finally {
                        try { extractor.release() }
                        finally { try { dsp?.close() } finally { wave?.close() } }
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        if (committed) output.delete()
        if (failure is CancellationException || failure is SpeechException) throw failure
        if (failure is Exception || failure is LinkageError)
            throw SpeechException(SpeechError.AudioProcessing).apply { initCause(failure) }
        throw failure
    } finally { if (ownsPartial) partial.delete() }
}

/** Input limits belong to each synthesizer; resampling a valid input may produce a larger WAV. */
private class SpeechWaveFile(file: File, private val sampleRate: Int) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var bytes = 0L
    init {
        try { output.write(ByteArray(44)) }
        catch (failure: Throwable) { output.close(); throw failure }
    }

    fun append(pcm: ByteArray) { output.write(pcm); bytes += pcm.size }

    fun finish() {
        if (bytes == 0L) throw SpeechException(SpeechError.InvalidAudio)
        output.seek(0)
        fun u16(value: Int) { output.write(value); output.write(value ushr 8) }
        fun u32(value: Long) { u16(value.toInt()); u16((value ushr 16).toInt()) }
        output.writeBytes("RIFF"); u32(36 + bytes); output.writeBytes("WAVEfmt ")
        u32(16); u16(1); u16(1); u32(sampleRate.toLong()); u32(sampleRate * 2L); u16(2); u16(16)
        output.writeBytes("data"); u32(bytes)
        output.fd.sync()
    }

    override fun close() = output.close()
}

private fun speechPcmSampleBytes(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_16BIT -> 2
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
    AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
    else -> error("Audio decoder returned unsupported PCM encoding: $encoding")
}

/** Same unsigned PCM8, float clipping and channel averaging as AudioEffectEngine.monoPcm. */
internal fun monoSpeechPcm(input: ByteBuffer, channels: Int, encoding: Int): ShortArray {
    val sampleBytes = speechPcmSampleBytes(encoding)
    require(channels > 0 && input.remaining() % (channels * sampleBytes) == 0) { "Incomplete PCM frame" }
    input.order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(input.remaining() / (channels * sampleBytes)) {
        var total = 0.0
        repeat(channels) {
            total += when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) * 256.0
                AudioFormat.ENCODING_PCM_16BIT -> input.short.toDouble()
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val sample = (input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or
                        (input.get().toInt() shl 16)
                    sample / 256.0
                }
                AudioFormat.ENCODING_PCM_32BIT -> input.int / 65536.0
                else -> input.float.let { require(it.isFinite()) { "Non-finite PCM sample" }; it.toDouble() * 32768.0 }
            }
        }
        (total / channels).toInt().coerceIn(-32768, 32767).toShort()
    }
}
