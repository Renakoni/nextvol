package indi.renakoni.nextvol.tts.sovits

import androidx.annotation.Keep

@Keep
internal object SovitsNative {
    init { System.loadLibrary("nextvol-matcha") }
    external fun create(model: ByteArray, runtime: ByteArray, speaker: ByteArray): Long
    external fun prepare(handle: Long, phones: LongArray, tokens: LongArray, repeats: LongArray,
        pcm32: FloatArray, pcm16: FloatArray)
    external fun semantics(handle: Long, phones: LongArray, tokens: LongArray, repeats: LongArray): LongArray
    external fun condition(handle: Long, advanced: Boolean): FloatArray
    external fun vocode(handle: Long, phones: LongArray, tokens: LongArray): ByteArray
    external fun pcm(audio: FloatArray): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
