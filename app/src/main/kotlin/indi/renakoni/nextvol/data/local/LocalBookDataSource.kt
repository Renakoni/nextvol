package indi.renakoni.nextvol.data.local

import indi.renakoni.nextvol.data.local.room.dao.BookInformationDao
import indi.renakoni.nextvol.data.local.room.dao.BookVolumesDao
import indi.renakoni.nextvol.data.local.room.dao.ChapterContentDao
import indi.renakoni.nextvol.data.local.room.dao.UserReadingDataDao
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.ChapterNavigation
import indi.renakoni.nextvol.data.book.BookAliasStore
import indi.renakoni.nextvol.data.book.rebind
import indi.renakoni.nextvol.data.book.bindReadingData
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.LocalBookDataSourceApi
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBookDataSource @Inject constructor(
    private val bookInformationDao: BookInformationDao,
    private val bookVolumesDao: BookVolumesDao,
    private val chapterContentDao: ChapterContentDao,
    private val userReadingDataDao: UserReadingDataDao,
    internal val aliases: BookAliasStore,
): LocalBookDataSourceApi {
    override suspend fun getBookInformation(id: String): BookInformation? = aliases.withResolved(BookIdentity.book(id)) { canonical ->
        bookInformationDao.get(canonical.storageKey)?.copy(id = BookIdentity.bookKey(id))
    }
    override suspend fun updateBookInformation(info: BookInformation) = aliases.withResolved(BookIdentity.book(info.id)) { canonical ->
        // A request started before the merge must not replace validated series metadata.
        if (BookIdentity.book(info.id) == canonical) bookInformationDao.insert(info.copy(id = canonical.storageKey))
    }
    override suspend fun getBookVolumes(id: String): BookVolumes? = aliases.withResolved(BookIdentity.book(id)) { canonical ->
        bookVolumesDao.getBookVolumes(canonical.storageKey)?.rebind(canonical, BookIdentity.book(id))
    }
    override suspend fun updateBookVolumes(bookVolumes: BookVolumes) {
        val book = SourceBookId.fromStorageKey(bookVolumes.bookId)
        bookVolumes.volumes.forEach { volume ->
            BookIdentity.volumeRemoteId(volume.volumeId, book)
            volume.chapters.forEach { require(SourceChapterId.fromStorageKey(it.id).book == book) }
        }
        aliases.withResolved(book) { canonical ->
            if (book == canonical) bookVolumesDao.insertVolume(canonical.storageKey, bookVolumes)
        }
    }

    override suspend fun getChapterContent(id: String): ChapterContent? {
        val chapter = SourceChapterId.fromStorageKey(id)
        return aliases.withResolved(chapter.book) { canonical ->
            chapterContentDao.get(SourceChapterId(canonical, chapter.remoteId).storageKey)?.let {
                ChapterContent(it.id, it.title, it.content, it.prevChapter.ifEmpty { null }, it.nextChapter.ifEmpty { null })
                    .let { content -> bookVolumesDao.getBookVolumes(canonical.storageKey)?.let { volumes ->
                        ChapterNavigation(volumes).applyTo(content)
                    } ?: content }
                    .rebind(canonical, chapter.book)
            }
        }
    }
    suspend fun getReusableChapterContent(id: String, revision: String): ChapterContent? {
        val chapter = SourceChapterId.fromStorageKey(id)
        return aliases.withResolved(chapter.book) { canonical ->
            chapterContentDao.reusable(SourceChapterId(canonical, chapter.remoteId).storageKey, revision)?.let {
                ChapterContent(it.id, it.title, it.content, it.prevChapter.ifEmpty { null }, it.nextChapter.ifEmpty { null })
                    .let { content -> bookVolumesDao.getBookVolumes(canonical.storageKey)?.let { volumes ->
                        ChapterNavigation(volumes).applyTo(content)
                    } ?: content }
                    .rebind(canonical, chapter.book)
            }
        }
    }

    override suspend fun updateChapterContent(chapterContent: ChapterContent) = updateChapterContent(chapterContent, "")

    internal suspend fun updateChapterContent(chapterContent: ChapterContent, sourceRevision: String) {
        val chapter = SourceChapterId.fromStorageKey(chapterContent.id)
        listOfNotNull(chapterContent.prevChapter, chapterContent.nextChapter).forEach {
            require(SourceChapterId.fromStorageKey(it).book == chapter.book)
        }
        aliases.withResolved(chapter.book) { canonical ->
            val rebound = chapterContent.rebind(chapter.book, canonical)
            if (chapter.book == canonical) chapterContentDao.cache(rebound, sourceRevision)
            else {
                val chapters = bookVolumesDao.getBookVolumes(canonical.storageKey)?.volumes.orEmpty().flatMap { it.chapters }
                val index = chapters.indexOfFirst { it.id == rebound.id }
                if (index >= 0) chapterContentDao.cache(ChapterContent(rebound.id, rebound.title, rebound.content,
                    chapters.getOrNull(index - 1)?.id, chapters.getOrNull(index + 1)?.id), sourceRevision)
            }
        }
    }

    override suspend fun getUserReadingData(id: String) = aliases.withResolved(BookIdentity.book(id)) { canonical ->
        userReadingDataDao.getEntity(canonical.storageKey).data(canonical).rebind(canonical, BookIdentity.book(id))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getUserReadingDataFlow(id: String) = aliases.observe(BookIdentity.book(id)).flatMapLatest { canonical ->
        userReadingDataDao.getEntityFlow(canonical.storageKey).map {
            it.data(canonical).rebind(canonical, BookIdentity.book(id))
        }
    }

    private fun UserReadingDataEntity?.data(book: SourceBookId): UserReadingData = this?.let {
        UserReadingData(it.id, it.lastReadTime.takeUnless { time -> time == LocalDateTime.MIN },
            it.totalReadTime, it.readingProgress, it.lastReadChapterId.ifEmpty { null },
            it.lastReadChapterTitle.ifEmpty { null }, it.currentChapterReadingProgressMap, it.maxChapterReadingProgressMap)
    } ?: UserReadingData(book.storageKey)

    override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
        val requested = BookIdentity.book(id)
        aliases.withResolved(requested) { canonical ->
            userReadingDataDao.update(canonical.storageKey) { entity ->
                val userReadingData = entity.data(canonical).rebind(canonical, requested)
                val new = requested.bindReadingData(update(userReadingData)).rebind(requested, canonical)
                UserReadingDataEntity(
                    id = new.id,
                    lastReadTime = new.lastReadTime ?: LocalDateTime.MIN,
                    totalReadTime = new.totalReadTime,
                    readingProgress = new.readingProgress,
                    lastReadChapterId = new.lastReadChapterId ?: "",
                    lastReadChapterTitle = new.lastReadChapterTitle ?: "",
                    currentChapterReadingProgressMap = new.currentChapterReadingProgressMap,
                    maxChapterReadingProgressMap = new.maxChapterReadingProgressMap
                )
            }
        }
    }

    override suspend fun getAllUserReadingData(): List<UserReadingData> =
        userReadingDataDao.getAll().map {
            UserReadingData(
                it.id,
                it.lastReadTime,
                it.totalReadTime,
                it.readingProgress,
                it.lastReadChapterId,
                it.lastReadChapterTitle,
                it.currentChapterReadingProgressMap,
                it.maxChapterReadingProgressMap
            )
        }

    override suspend fun isChapterContentExists(id: String): Boolean {
        val chapter = SourceChapterId.fromStorageKey(id)
        return aliases.withResolved(chapter.book) { canonical ->
            chapterContentDao.getId(SourceChapterId(canonical, chapter.remoteId).storageKey) != null
        }
    }

    override suspend fun clear() {
        aliases.clear()
        userReadingDataDao.clear()
        bookInformationDao.clear()
        bookVolumesDao.clear()
        chapterContentDao.clear()
    }
}
