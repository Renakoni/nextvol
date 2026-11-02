package indi.renakoni.nextvol.tts.audio

import kotlin.math.abs

/** One mono PCM16 stream. Feed bounded decoder blocks, then finish once at end of input. */
class PcmAudioProcessor(
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
    private var closed = false
    private var finished = false

    /** Frames before silence compression, for this call's mapping back to speech timing. */
    var lastUncompressedFrames = 0
        private set

    init {
        if (inputSampleRate <= 0 || outputSampleRate <= 0 || !speed.isFinite() || speed <= 0 ||
            !pitch.isFinite() || pitch <= 0 || !volume.isFinite() || volume < 0) {
            throw AudioProcessingException("Audio rates and pitch must be positive; volume must be nonnegative and finite")
        }
        if (!bypass) when (processor) {
            200 -> sonic = newSonic()
            201 -> try {
                nativeHandle = SoundTouchNative.create(inputSampleRate, outputSampleRate, speed, pitch)
            } catch (failure: LinkageError) {
                throw AudioProcessingException("SoundTouch is unavailable").apply { initCause(failure) }
            }
            else -> throw AudioProcessingException("Unknown audio processor: $processor")
        }
    }

    private fun newSonic() = Sonic(inputSampleRate, 1).apply {
        setRate(inputSampleRate.toFloat() / outputSampleRate)
        setSpeed(this@PcmAudioProcessor.speed)
        setPitch(this@PcmAudioProcessor.pitch)
        setVolume(this@PcmAudioProcessor.volume)
    }

    @Synchronized
    fun process(samples: ShortArray, count: Int = samples.size, intentionalPause: Boolean = false): ShortArray {
        check(!closed && !finished)
        require(count in 0..samples.size)
        if (intentionalPause) {
            // Finish buffered speech before inserting a pause in the output sample rate.
            val tail = completeBlock(flush())
            val uncompressed = lastUncompressedFrames
            if (sonic != null) sonic = newSonic()
            lastUncompressedFrames = uncompressed + count
            return tail + samples.copyOf(count)
        }
        val output = when {
            bypass -> samples.copyOf(count)
            sonic != null -> {
                sonic!!.writeShortToStream(samples, count)
                readSonic()
            }
            else -> SoundTouchNative.process(nativeHandle, samples, count)
        }
        return completeBlock(output)
    }

    @Synchronized
    fun finish(): ShortArray {
        check(!closed && !finished)
        finished = true
        return completeBlock(flush())
    }

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

    @Synchronized
    override fun close() {
        closed = true
        sonic = null
        if (nativeHandle != 0L) {
            val handle = nativeHandle
            nativeHandle = 0L
            SoundTouchNative.destroy(handle)
        }
    }
}
