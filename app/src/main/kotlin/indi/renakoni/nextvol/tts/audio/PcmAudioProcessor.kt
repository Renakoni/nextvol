package indi.renakoni.nextvol.tts.audio

import kotlin.math.abs

/** One file-processing coroutine owns this stream; feed decoder blocks, then finish once. */
internal class PcmAudioProcessor(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int,
    private val processor: Int = 200,
    private val speed: Float = 1f,
    private val pitch: Float = 1f,
    private val volume: Float = 1f,
    private val checkSilence: Boolean = false,
) : AutoCloseable {
    private val bypass = inputSampleRate == outputSampleRate && speed == 1f && pitch == 1f && volume == 1f
    private var sonic: Sonic? = null
    private var nativeHandle = 0L

    /** Frames before silence compression, for this call's mapping back to speech timing. */
    var lastUncompressedFrames = 0
        private set

    init {
        if (inputSampleRate <= 0 || outputSampleRate <= 0 || !speed.isFinite() || speed <= 0 ||
            !pitch.isFinite() || pitch <= 0 || !volume.isFinite() || volume < 0) {
            throw AudioProcessingException("Audio rates and pitch must be positive; volume must be nonnegative and finite")
        }
        if (!bypass) when (processor) {
            200 -> sonic = Sonic(inputSampleRate, 1).apply {
                setRate(inputSampleRate.toFloat() / outputSampleRate)
                setSpeed(this@PcmAudioProcessor.speed)
                setPitch(this@PcmAudioProcessor.pitch)
                setVolume(this@PcmAudioProcessor.volume)
            }
            201 -> try {
                nativeHandle = SoundTouchNative.create(inputSampleRate, outputSampleRate, speed, pitch)
            } catch (failure: LinkageError) {
                throw AudioProcessingException("SoundTouch is unavailable").apply { initCause(failure) }
            }
            else -> throw AudioProcessingException("Unknown audio processor: $processor")
        }
    }

    fun process(samples: ShortArray): ShortArray {
        val output = when {
            bypass -> samples
            sonic != null -> {
                sonic!!.writeShortToStream(samples, samples.size)
                readSonic()
            }
            else -> SoundTouchNative.process(nativeHandle, samples, samples.size)
        }
        return completeBlock(output)
    }

    fun finish(): ShortArray = completeBlock(flush())

    private fun flush(): ShortArray = when {
        bypass -> shortArrayOf()
        sonic != null -> { sonic!!.flushStream(); readSonic() }
        else -> SoundTouchNative.finish(nativeHandle)
    }

    private fun readSonic(): ShortArray = ShortArray(sonic!!.samplesAvailable()).also {
        sonic!!.readShortFromStream(it, it.size)
    }

    private fun completeBlock(output: ShortArray): ShortArray {
        if (!bypass && processor == 201 && volume != 1f) {
            for (index in output.indices) output[index] = (output[index] * volume).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        lastUncompressedFrames = output.size
        // MultiTTS c2.a.w shortens a wholly quiet callback block; it does not trim speech edges.
        return if (checkSilence && output.all { abs(it.toInt()) <= 512 }) ShortArray(output.size / 4) else output
    }

    override fun close() {
        sonic = null
        if (nativeHandle != 0L) {
            val handle = nativeHandle
            nativeHandle = 0L
            SoundTouchNative.destroy(handle)
        }
    }
}
