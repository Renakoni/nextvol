package indi.renakoni.nextvol.tts

/** Reorder only the supplied voices, leaving filtered-out voices in their existing slots. */
internal fun <T> List<T>.orderSpeechVoices(ids: List<String>, key: (T) -> String): List<T> {
    val voices = associateBy(key)
    val order = ids.distinct().mapNotNull(voices::get)
    val selected = order.map(key).toSet()
    val iterator = order.iterator()
    return map { if (key(it) in selected) iterator.next() else it }
}
