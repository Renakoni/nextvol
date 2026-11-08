package indi.renakoni.nextvol.data.backup.model

import indi.renakoni.nextvol.data.local.room.entity.BookInformationEntity
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadedChapterEntity
import indi.renakoni.nextvol.data.local.room.entity.BookRecordEntity
import indi.renakoni.nextvol.data.local.room.entity.BookshelfBookMetadataEntity
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.local.room.entity.ChapterContentEntity
import indi.renakoni.nextvol.data.local.room.entity.ChapterInformationEntity
import indi.renakoni.nextvol.data.local.room.entity.DailyCountEntity
import indi.renakoni.nextvol.data.local.room.entity.FormattingRuleEntity
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.local.room.entity.VolumeEntity
import kotlinx.serialization.Serializable

@Serializable
data class LocalData(
    val bookInformationEntities: List<BookInformationEntity>,
    val bookRecordEntities: List<BookRecordEntity>,
    val dailyCountEntities: List<DailyCountEntity>,
    val bookshelfEntities: List<BookshelfEntity>,
    val bookshelfBookMetadataEntities: List<BookshelfBookMetadataEntity>,
    val chapterContentEntities: List<ChapterContentEntity>,
    val chapterInformationEntities: List<ChapterInformationEntity>,
    val formattingRuleEntities: List<FormattingRuleEntity>,
    val userDataEntities: List<UserDataEntity>,
    val userReadingDataEntities: List<UserReadingDataEntity>,
    val volumeEntities: List<VolumeEntity>,
    val localBookFiles: List<indi.renakoni.nextvol.data.localbook.LocalBookFileManifest> = emptyList(),
    val readingBookmarks: List<indi.renakoni.nextvol.data.bookmark.ReadingBookmark> = emptyList(),
    val bookDownloadEntities: List<BookDownloadEntity> = emptyList(),
    val downloadedChapterEntities: List<DownloadedChapterEntity> = emptyList(),
    val bookAliases: List<indi.renakoni.nextvol.data.local.room.entity.BookAliasEntity> = emptyList(),
) {
    companion object {
        fun empty() = LocalData(
            bookInformationEntities = emptyList(),
            bookRecordEntities = emptyList(),
            dailyCountEntities = emptyList(),
            bookshelfEntities = emptyList(),
            bookshelfBookMetadataEntities = emptyList(),
            chapterContentEntities = emptyList(),
            chapterInformationEntities = emptyList(),
            formattingRuleEntities = emptyList(),
            userDataEntities = emptyList(),
            userReadingDataEntities = emptyList(),
            volumeEntities = emptyList()
        )
    }
}
