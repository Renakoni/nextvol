package indi.renakoni.nextvol.data.book

/** Equal-weight completion of a known catalog; historical chapters outside it do not contribute. */
internal fun bookReadingProgress(chapterIds: Set<String>, maximum: Map<String, Float>): Float {
    if (chapterIds.isEmpty()) return 0f
    return chapterIds.sumOf { id ->
        (maximum[id] ?: 0f).takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.toDouble() ?: 0.0
    }.div(chapterIds.size).toFloat()
}
