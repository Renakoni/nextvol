package indi.renakoni.nextvol.tts.names

import androidx.annotation.Keep
import indi.renakoni.nextvol.tts.matcha.matchaAvailable

@Keep
internal object NameModelNative {
    val available get() = matchaAvailable()

    external fun create(model: ByteArray): Long
    external fun transitions(handle: Long): DoubleArray
    external fun emissions(handle: Long, input: IntArray): FloatArray
    external fun predict(handle: Long, input: FloatArray): Int
}
