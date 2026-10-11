package indi.renakoni.nextvol.data.local.room.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "book_download")
data class BookDownloadEntity(
    @PrimaryKey val bookId: String,
    val revision: String = "",
    val directoryHash: String = "",
    val phase: String = "partial",
    val generation: Long = 0,
    val attempt: String = "",
    val coverUri: String = "",
    @ColumnInfo(defaultValue = "''") val taskWorkId: String = "",
    @ColumnInfo(defaultValue = "'None'") val taskStatus: String = "None",
    @ColumnInfo(defaultValue = "'Unknown'") val taskStage: String = "Unknown",
    @ColumnInfo(defaultValue = "''") val taskChapter: String = "",
    @ColumnInfo(defaultValue = "''") val taskError: String = "",
    @ColumnInfo(defaultValue = "0") val taskRunAttempt: Int = 0,
    @ColumnInfo(defaultValue = "0") val taskHidden: Boolean = false,
    @ColumnInfo(defaultValue = "0") val taskRetryCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val taskNextAttemptAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val taskSourceRevision: String = "",
    @ColumnInfo(defaultValue = "-1") val taskAccountGeneration: Long = -1,
    // Retired with offline-content updates. Unused; kept so existing tables need no migration.
    @ColumnInfo(defaultValue = "''") val taskRefreshId: String = "",
    @ColumnInfo(defaultValue = "''") val taskChapterIds: String = "",
    @ColumnInfo(defaultValue = "'{}'") val taskChapterFailures: String = "{}",
)

/** Ownership and the source-visible version of a successfully saved chapter. Body stays in Room. */
@Serializable
@Entity(tableName = "downloaded_chapter", indices = [Index("bookId")])
data class DownloadedChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val signature: String,
    val images: String = "[]",
    @ColumnInfo(defaultValue = "''") val resourceVersion: String = "",
)

/** One complete body awaiting its required images; never replaces readable content prematurely. */
@Entity(tableName = "download_chapter_candidate", indices = [Index("bookId")])
data class DownloadChapterCandidateEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val signature: String,
    val body: String,
    val images: String,
    val resourceVersion: String,
)
