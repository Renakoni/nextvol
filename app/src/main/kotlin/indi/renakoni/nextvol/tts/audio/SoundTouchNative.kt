package indi.renakoni.nextvol.tts.audio

import androidx.annotation.Keep

@Keep
class AudioProcessingException(message: String) : Exception(message)

@Keep
internal object SoundTouchNative {
    init { System.loadLibrary("nextvol-audio") }

    external fun create(inputSampleRate: Int, outputSampleRate: Int, speed: Float, pitch: Float): Long
    external fun process(handle: Long, samples: ShortArray, count: Int): ShortArray
    external fun finish(handle: Long): ShortArray
    external fun destroy(handle: Long)
}
