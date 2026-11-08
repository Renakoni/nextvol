package indi.renakoni.nextvol.data.book

import androidx.room.withTransaction
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookAliasEntity
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Durable, source-qualified aliases. Existing routes keep their IDs; persisted library rows do not. */
@Singleton
class BookAliasStore @Inject constructor(private val database: NextVolDatabase) {
    private val aliases = database.bookAliasDao()

    private fun SourceBookId.target(key: String?) = key?.let(SourceBookId::fromStorageKey)?.also {
        require(it.sourceId == sourceId) { "A book alias cannot change its source" }
    } ?: this

    suspend fun resolve(book: SourceBookId): SourceBookId = book.target(aliases.get(book.storageKey))
    suspend fun clear() = aliases.clear()

    fun observe(book: SourceBookId) = aliases.observe(book.storageKey).map { book.target(it) }.distinctUntilChanged()

    /** Resolution and a local write share the transaction so an old page cannot resurrect an alias. */
    suspend fun <T> withResolved(book: SourceBookId, block: suspend (SourceBookId) -> T): T =
        database.withTransaction { block(resolve(book)) }

    internal suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction { block() }

    suspend fun merge(from: SourceBookId, to: SourceBookId, information: BookInformation, volumes: BookVolumes) {
        require(from.sourceId == to.sourceId) { "A book alias cannot change its source" }
        require(information.id == to.storageKey && volumes.bookId == to.storageKey)
        volumes.volumes.forEach { BookIdentity.volumeRemoteId(it.volumeId, to) }
        val chapters = volumes.volumes.flatMap { it.chapters }
        require(chapters.isNotEmpty()) { "A canonical book needs a readable directory" }
        val chapterIds = chapters.map { chapter -> BookIdentity.chapter(chapter.id, to).storageKey }.toSet()
        val positions = chapters.withIndex().associate { it.value.id to it.index }
        database.withTransaction {
            val current = resolve(from)
            if (current == to) return@withTransaction
            // A contradictory response must not move an already resolved series into another book.
            check(current == from && resolve(to) == to) { "Conflicting canonical book identity" }
            val reading = database.userReadingDataDao()
            val previous = reading.getEntity(from.storageKey)?.rebind(from, to)
            check(previous == null || previous.lastReadChapterId.isEmpty() || previous.lastReadChapterId in chapterIds) {
                "The canonical directory no longer contains the saved chapter"
            }
            val existing = reading.getEntity(to.storageKey)
            check(existing == null || existing.lastReadChapterId.isEmpty() || existing.lastReadChapterId in chapterIds) {
                "The canonical directory no longer contains the series' saved chapter"
            }
            if (previous != null) {
                val newer = if (existing != null && existing.lastReadTime >= previous.lastReadTime) existing else previous
                val older = if (newer === previous) existing else previous
                val maximum = (previous.maxChapterReadingProgressMap.keys + existing?.maxChapterReadingProgressMap.orEmpty().keys)
                    .intersect(chapterIds).associateWith { id ->
                        maxOf(previous.maxChapterReadingProgressMap[id] ?: 0f, existing?.maxChapterReadingProgressMap?.get(id) ?: 0f)
                    }
                reading.insert(newer.copy(id = to.storageKey,
                    totalReadTime = previous.totalReadTime + (existing?.totalReadTime ?: 0),
                    lastReadChapterTitle = chapters.find { it.id == newer.lastReadChapterId }?.title.orEmpty(),
                    currentChapterReadingProgressMap = (older?.currentChapterReadingProgressMap.orEmpty() +
                        newer.currentChapterReadingProgressMap).filterKeys { it in chapterIds },
                    maxChapterReadingProgressMap = maximum,
                    readingProgress = bookReadingProgress(chapterIds, maximum)))
                reading.deleteByIds(listOf(from.storageKey))
            }

            val catalogs = database.bookVolumesDao()
            val oldChapterIds = catalogs.getBookVolumes(from.storageKey)?.volumes.orEmpty()
                .flatMap { it.chapters }.map { it.id }.toSet()
            val content = database.chapterContentDao()
            for (id in oldChapterIds) {
                val target = SourceChapterId(to, BookIdentity.chapter(id, from).remoteId).storageKey
                val index = positions[target] ?: continue
                content.get(id)?.let { old ->
                    if (content.getId(target) == null) content.cache(ChapterContent(target, old.title, old.content,
                        chapters.getOrNull(index - 1)?.id, chapters.getOrNull(index + 1)?.id))
                    content.deleteByIds(listOf(id))
                }
            }
            catalogs.deleteByBookIds(listOf(from.storageKey))
            catalogs.deleteChapterInformationByIds(oldChapterIds.toList())
            catalogs.insertVolume(to.storageKey, volumes)
            database.bookInformationDao().insert(information)
            database.bookInformationDao().deleteByIds(listOf(from.storageKey))

            val shelves = database.bookshelfDao()
            fun List<String>.replaceAlias() = map { if (it == from.storageKey) to.storageKey else it }.distinct()
            shelves.getAllBookshelves().filter { from.storageKey in it.allBookIds ||
                from.storageKey in it.pinnedBookIds || from.storageKey in it.updatedBookIds }.forEach { shelf ->
                shelves.insertBookshelf(shelf.copy(allBookIds = shelf.allBookIds.replaceAlias(),
                    pinnedBookIds = shelf.pinnedBookIds.replaceAlias(), updatedBookIds = shelf.updatedBookIds.replaceAlias()))
            }
            shelves.getBookshelfBookMetadataEntity(from.storageKey)?.let { old ->
                val target = shelves.getBookshelfBookMetadataEntity(to.storageKey)
                shelves.insertBookshelfBookMetadata(target?.merge(old.copy(id = to.storageKey)) ?: old.copy(id = to.storageKey))
                shelves.deleteBookshelfBookMetadata(from.storageKey)
            }
            // A later canonical URL change must not leave older routes pointing at a deleted row.
            aliases.retarget(from.storageKey, to.storageKey)
            aliases.insert(BookAliasEntity(from.storageKey, to.storageKey))
        }
    }

    private fun UserReadingDataEntity.rebind(from: SourceBookId, to: SourceBookId): UserReadingDataEntity {
        fun chapter(id: String) = SourceChapterId(to, BookIdentity.chapter(id, from).remoteId).storageKey
        return copy(id = to.storageKey,
            lastReadChapterId = lastReadChapterId.takeIf(String::isNotEmpty)?.let(::chapter).orEmpty(),
            currentChapterReadingProgressMap = currentChapterReadingProgressMap.mapKeys { chapter(it.key) },
            maxChapterReadingProgressMap = maxChapterReadingProgressMap.mapKeys { chapter(it.key) })
    }
}
