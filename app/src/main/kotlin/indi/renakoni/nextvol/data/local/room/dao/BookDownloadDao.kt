package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadedChapterEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadChapterCandidateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDownloadDao {
    @Query("select * from download_chapter_candidate where id = :id")
    suspend fun candidate(id: String): DownloadChapterCandidateEntity?

    @Query("select * from download_chapter_candidate where bookId = :bookId")
    suspend fun candidates(bookId: String): List<DownloadChapterCandidateEntity>

    @Query("select exists(select 1 from download_chapter_candidate where bookId = :bookId and resourceVersion = :version) " +
        "or exists(select 1 from downloaded_chapter where bookId = :bookId and resourceVersion = :version)")
    suspend fun hasResourceVersion(bookId: String, version: String): Boolean

    @Query("select * from download_chapter_candidate where bookId = :bookId")
    fun observeCandidates(bookId: String): Flow<List<DownloadChapterCandidateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(candidate: DownloadChapterCandidateEntity)

    @Query("delete from download_chapter_candidate where id = :id")
    suspend fun deleteCandidate(id: String)

    @Query("delete from download_chapter_candidate where bookId in (:bookIds)")
    suspend fun deleteCandidates(bookIds: List<String>)

    @Query("delete from download_chapter_candidate")
    suspend fun clearCandidates()

    @Query("select * from book_download where bookId = :bookId")
    suspend fun get(bookId: String): BookDownloadEntity?

    @Query("select * from book_download where bookId = :bookId")
    fun observe(bookId: String): Flow<BookDownloadEntity?>

    @Query("select * from book_download")
    suspend fun getAll(): List<BookDownloadEntity>

    @Query("select * from book_download")
    fun observeAll(): Flow<List<BookDownloadEntity>>

    @Query("select * from downloaded_chapter where id = :id")
    suspend fun chapter(id: String): DownloadedChapterEntity?

    @Query("select * from downloaded_chapter where bookId = :bookId")
    suspend fun chapters(bookId: String): List<DownloadedChapterEntity>

    @Query("select * from downloaded_chapter where bookId = :bookId")
    fun observeChapters(bookId: String): Flow<List<DownloadedChapterEntity>>

    @Query("select * from downloaded_chapter")
    suspend fun allChapters(): List<DownloadedChapterEntity>

    @Query("select chapter_content.id from chapter_content inner join downloaded_chapter on chapter_content.id = downloaded_chapter.id where downloaded_chapter.bookId = :bookId")
    suspend fun savedContentIds(bookId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(book: BookDownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(chapter: DownloadedChapterEntity)

    @Query("delete from chapter_content where id not in (select id from downloaded_chapter)")
    suspend fun clearReadingContent()

    @Query("delete from chapter_content where id in (select id from downloaded_chapter)")
    suspend fun clearDownloadedContent()

    @Query("delete from downloaded_chapter")
    suspend fun clearChapters()

    @Query("delete from book_download")
    suspend fun clearBooks()

    @Query("delete from chapter_content where id in (select id from downloaded_chapter where bookId in (:bookIds))")
    suspend fun deleteContent(bookIds: List<String>)

    @Query("delete from downloaded_chapter where bookId in (:bookIds)")
    suspend fun deleteChapters(bookIds: List<String>)

    @Query("delete from book_download where bookId in (:bookIds)")
    suspend fun deleteBooks(bookIds: List<String>)
}
