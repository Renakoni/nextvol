package indi.renakoni.nextvol.tts.names

internal data class NgrInput(val features: FloatArray, val supported: Boolean)

/** MultiTTS's surname removal and three character/two bigram scores, indexed as UTF-16 units. */
internal class NgrEncoder(private val vocabulary: Map<String, Float>, private val families: Set<String>) {
    fun encode(name: String): NgrInput {
        var given = name
        for (length in 3 downTo 1) {
            if (name.length > length && name.take(length) in families) {
                given = name.substring(length)
                break
            }
        }
        given = given.takeLast(3)
        val features = FloatArray(5)
        var supported = false
        for (index in given.indices) {
            vocabulary[given.substring(index, index + 1)]?.let {
                features[index] = it
                supported = true
            }
        }
        for (index in 0 until given.length - 1) {
            vocabulary[given.substring(index, index + 2)]?.let {
                features[index + 3] = it
                supported = true
            }
        }
        return NgrInput(features, supported)
    }

    companion object {
        fun vocabulary(lines: Sequence<String>): Map<String, Float> = lines.associate { line ->
            val split = line.indexOf(',')
            line.substring(0, split) to line.substring(split + 1).toFloat()
        }
    }
}
