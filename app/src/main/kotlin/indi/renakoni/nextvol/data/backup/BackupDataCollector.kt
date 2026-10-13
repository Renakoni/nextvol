package indi.renakoni.nextvol.data.backup

import indi.renakoni.nextvol.data.local.room.dao.BookInformationDao
import indi.renakoni.nextvol.data.local.room.dao.BookDownloadDao
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadedChapterEntity
import indi.renakoni.nextvol.data.local.room.dao.BookRecordDao
import indi.renakoni.nextvol.data.local.room.dao.BookVolumesDao
import indi.renakoni.nextvol.data.local.room.dao.BookshelfDao
import indi.renakoni.nextvol.data.local.room.dao.ChapterContentDao
import indi.renakoni.nextvol.data.local.room.dao.DailyCountDao
import indi.renakoni.nextvol.data.local.room.dao.FormattingRuleDao
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.local.room.dao.UserReadingDataDao
import indi.renakoni.nextvol.data.local.room.entity.BookInformationEntity
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

class BackupDataCollector(
    private val bookBookInformationDao: BookInformationDao,
    private val bookDownloadDao: BookDownloadDao,
    private val bookRecordDao: BookRecordDao,
    private val dailyCountDao: DailyCountDao,
    private val bookshelfDao: BookshelfDao,
    private val chapterContentDao: ChapterContentDao,
    private val bookVolumesDao: BookVolumesDao,
    private val formattingRuleDao: FormattingRuleDao,
    private val userReadingDataDao: UserReadingDataDao,
    private val userDataDao: UserDataDao,
    libraryUserDataPaths: Set<String>
) {
    abstract class OptionSolver {
        var enable: Boolean = false
        abstract suspend fun solve()
    }
    
    val bookInformationEntities = mutableListOf<BookInformationEntity>()
    val bookDownloadEntities = mutableListOf<BookDownloadEntity>()
    val downloadedChapterEntities = mutableListOf<DownloadedChapterEntity>()
    val bookRecordEntities = mutableListOf<BookRecordEntity>()
    val dailyCountEntities = mutableListOf<DailyCountEntity>()
    val bookshelfEntities = mutableListOf<BookshelfEntity>()
    val bookshelfBookMetadataEntities = mutableListOf<BookshelfBookMetadataEntity>()
    val chapterContentEntities = mutableListOf<ChapterContentEntity>()
    val chapterInformationEntities = mutableListOf<ChapterInformationEntity>()
    val formattingRuleEntities = mutableListOf<FormattingRuleEntity>()
    val userDataEntities = mutableListOf<UserDataEntity>()
    val userReadingDataEntities = mutableListOf<UserReadingDataEntity>()
    val volumeEntities = mutableListOf<VolumeEntity>()

    private val options = mutableListOf<OptionSolver>()

    val localBookCache = object : OptionSolver() {
        override suspend fun solve() {
            bookInformationEntities.addAll(bookBookInformationDao.getAllEntities())
            volumeEntities.addAll(bookVolumesDao.getAllVolumeEntities())
            chapterContentEntities.addAll(chapterContentDao.getAllEntities())
            chapterInformationEntities.addAll(bookVolumesDao.getAllChapterInformationEntities())
            bookDownloadEntities.addAll(bookDownloadDao.getAll().map { it.copy(generation = 0, attempt = "", phase = "partial") })
            downloadedChapterEntities.addAll(bookDownloadDao.allChapters())
        }
    }.also(options::add)

    val bookshelf = object : OptionSolver() {
        override suspend fun solve() {
            bookshelfEntities.addAll(bookshelfDao.getAllBookshelves())
            bookshelfBookMetadataEntities.addAll(bookshelfDao.getAllBookshelfBookEntities())
        }
    }.also(options::add)

    val readingRecord = object : OptionSolver() {
        override suspend fun solve() {
            bookRecordEntities.addAll(bookRecordDao.getAllBookRecords())
            dailyCountEntities.addAll(dailyCountDao.getAll())
            userReadingDataEntities.addAll(userReadingDataDao.getAll())
            userDataEntities.addAll(userDataDao.getAllEntities().filter {
                libraryUserDataPaths.contains(it.path)
            }
            )
        }
    }.also(options::add)

    val settings = object : OptionSolver() {
        override suspend fun solve() {
            formattingRuleEntities.addAll(formattingRuleDao.getAllBookRuleEntity())
        }
    }.also(options::add)

    suspend fun solve() {
        for (solver in options) {
            if (solver.enable)
                solver.solve()
        }
    }
}
