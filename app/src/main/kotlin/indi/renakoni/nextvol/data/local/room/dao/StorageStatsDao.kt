package indi.renakoni.nextvol.data.local.room.dao

import androidx.room.Dao
import androidx.room.Query
import indi.renakoni.nextvol.data.local.room.entity.StorageBytes
import indi.renakoni.nextvol.data.local.room.entity.VolumeStorageRow
import indi.renakoni.nextvol.data.local.room.entity.DownloadPreparationBytes

@Dao
interface StorageStatsDao {
    @Query("select bookId, sum(length(cast(body as blob)) + length(cast(images as blob))) as bytes, " +
        "count(*) as chapters from download_chapter_candidate group by bookId")
    suspend fun getDownloadPreparationBytes(): List<DownloadPreparationBytes>

    @Query(
        """
        select
            id as id,
            (
                ifnull(length(cast(id as blob)), 0) +
                ifnull(length(cast(title as blob)), 0) +
                ifnull(length(cast(subtitle as blob)), 0) +
                ifnull(length(cast(cover_uri as blob)), 0) +
                ifnull(length(cast(author as blob)), 0) +
                ifnull(length(cast(description as blob)), 0) +
                ifnull(length(cast(tags as blob)), 0) +
                ifnull(length(cast(publishing_house as blob)), 0) +
                ifnull(length(cast(word_count as blob)), 0) +
                ifnull(length(cast(last_update as blob)), 0) +
                1
            ) as bytes
        from book_information
        """
    )
    suspend fun getBookInformationBytes(): List<StorageBytes>

    @Query(
        """
        select
            book_id as bookId,
            chapter_id_list as chapterIdList,
            (
                ifnull(length(cast(book_id as blob)), 0) +
                ifnull(length(cast(volume_id as blob)), 0) +
                ifnull(length(cast(volume_title as blob)), 0) +
                ifnull(length(cast(chapter_id_list as blob)), 0) +
                4
            ) as bytes
        from volume
        """
    )
    suspend fun getVolumeStorageRows(): List<VolumeStorageRow>

    @Query(
        """
        select
            id as id,
            (
                ifnull(length(cast(id as blob)), 0) +
                ifnull(length(cast(title as blob)), 0)
            ) as bytes
        from chapter_information
        """
    )
    suspend fun getChapterInformationBytes(): List<StorageBytes>

    @Query(
        """
        select
            id as id,
            (
                ifnull(length(cast(id as blob)), 0) +
                ifnull(length(cast(title as blob)), 0) +
                ifnull(length(cast(content as blob)), 0) +
                ifnull(length(cast(lastChapter as blob)), 0) +
                ifnull(length(cast(nextChapter as blob)), 0)
            ) as bytes
        from chapter_content
        """
    )
    suspend fun getChapterContentBytes(): List<StorageBytes>

    @Query(
        """
        select
            id as id,
            (
                ifnull(length(cast(id as blob)), 0) +
                ifnull(length(cast(last_read_time as blob)), 0) +
                ifnull(length(cast(total_read_time as blob)), 0) +
                ifnull(length(cast(reading_progress as blob)), 0) +
                ifnull(length(cast(last_read_chapter_id as blob)), 0) +
                ifnull(length(cast(last_read_chapter_title as blob)), 0) +
                ifnull(length(cast(current_chapter_reading_progress_map as blob)), 0) +
                ifnull(length(cast(max_chapter_reading_progress_map as blob)), 0)
            ) as bytes
        from user_reading_data
        """
    )
    suspend fun getUserReadingBytes(): List<StorageBytes>
}
