package indi.renakoni.nextvol.data.book

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent

/** The last successful directory owns order; cached bodies do not. */
class ChapterNavigation(volumes: BookVolumes) {
    val bookId = volumes.bookId
    private val adjacent = volumes.volumes.flatMap { it.chapters }.map { it.id }
        .filter { it.isNotEmpty() }.distinct().let { ids ->
            ids.mapIndexed { index, id -> id to (ids.getOrNull(index - 1) to ids.getOrNull(index + 1)) }.toMap()
        }

    fun adjacent(chapterId: String): Pair<String?, String?> = adjacent[chapterId] ?: (null to null)

    fun applyTo(content: ChapterContent): ChapterContent {
        val (previous, next) = adjacent(content.id)
        return content.copy(prevChapter = previous, nextChapter = next)
    }
}
