package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import indi.renakoni.nextvol.data.local.room.converter.JsonObjectConverter
import indi.renakoni.nextvol.data.local.room.entity.ChapterContentEntity
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.serialization.json.JsonObject

@Dao
interface ChapterContentDao {
    @TypeConverters(JsonObjectConverter::class)
    @Query("replace into chapter_content (id, title, content, lastChapter, nextChapter, sourceRevision) " +
            "values (:id, :title, :content, :prevChapter, :nextChapter, :sourceRevision)"
    )
    suspend fun update(id: String, title: String, content: JsonObject, prevChapter: String, nextChapter: String, sourceRevision: String = "")

    /** Reading refreshes cannot replace a download before its new images have been saved. */
    @TypeConverters(JsonObjectConverter::class)
    @Query("replace into chapter_content (id, title, content, lastChapter, nextChapter, sourceRevision) " +
            "select :id, :title, :content, :prevChapter, :nextChapter, :sourceRevision " +
            "where not exists (select 1 from downloaded_chapter where id = :id)")
    suspend fun cache(id: String, title: String, content: JsonObject, prevChapter: String, nextChapter: String, sourceRevision: String = "")

    suspend fun cache(chapter: ChapterContent, sourceRevision: String = "") = cache(chapter.id, chapter.title, chapter.content,
        chapter.prevChapter.orEmpty(), chapter.nextChapter.orEmpty(), sourceRevision)

    suspend fun cache(chapter: ChapterContentEntity) = cache(chapter.id, chapter.title, chapter.content,
        chapter.prevChapter, chapter.nextChapter, chapter.sourceRevision)

    @Transaction
    suspend fun update(chapterContent: ChapterContent) {
        update(
            chapterContent.id,
            chapterContent.title,
            chapterContent.content,
            chapterContent.prevChapter ?: "",
            chapterContent.nextChapter ?: ""
        )
    }

    @Transaction
    suspend fun update(chapterContent: ChapterContentEntity) {
        update(
            chapterContent.id,
            chapterContent.title,
            chapterContent.content,
            chapterContent.prevChapter,
            chapterContent.nextChapter,
            chapterContent.sourceRevision
        )
    }

    @Query("select * from chapter_content where id = :id")
    suspend fun get(id: String): ChapterContentEntity?

    /** Downloads are offline snapshots; reading cache is reusable only after rule-version validation. */
    @Query("select * from chapter_content where id = :id and (exists " +
        "(select 1 from downloaded_chapter where id = :id) or (:revision != '' and sourceRevision = :revision))")
    suspend fun reusable(id: String, revision: String): ChapterContentEntity?

    @Query("select id from chapter_content where id = :id")
    suspend fun getId(id: String): String?

    @Query("delete from chapter_content")
    suspend fun clear()

    @Query("delete from chapter_content where id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("select * from chapter_content")
    suspend fun getAllEntities(): List<ChapterContentEntity>
}
