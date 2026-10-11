package indi.renakoni.nextvol.data.download

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import kotlinx.serialization.json.Json

data class DownloadChapterState(val downloaded: Boolean, val failure: DownloadFailure? = null)

data class DownloadSelectionState(
    val chapters: Map<String, DownloadChapterState> = emptyMap(),
    val selectedChapterIds: Set<String>? = null,
)

/** Empty storage is the legacy whole-book request; explicit selections store source-visible IDs. */
internal fun BookDownloadEntity.selectedChapterIds(): Set<String>? =
    taskChapterIds.takeIf { it.isNotEmpty() }?.let { Json.decodeFromString<List<String>>(it).toSet() }

internal fun BookDownloadEntity.chapterFailures(): Map<String, DownloadFailure> =
    Json.decodeFromString<Map<String, String>>(taskChapterFailures).mapNotNull { (id, value) ->
        DownloadFailure.entries.firstOrNull { it.name == value }?.let { id to it }
    }.toMap()

internal class DownloadSelectionChangedException : IllegalStateException("Selected chapters are no longer in the directory")

/** Keep full-directory indices: signatures depend on the original neighbours, not selected neighbours. */
internal fun selectedDownloadChapters(book: SourceBookId, chapters: List<ChapterInformation>,
    selected: Set<String>?): List<IndexedValue<ChapterInformation>> {
    val targets = chapters.withIndex().filter { selected == null || BookIdentity.chapter(it.value.id, book).remoteId in selected }
    if (targets.isEmpty() || (selected != null && targets.size != selected.size)) throw DownloadSelectionChangedException()
    return targets
}
