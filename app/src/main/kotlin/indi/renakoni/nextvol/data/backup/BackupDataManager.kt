package indi.renakoni.nextvol.data.backup

import androidx.room.withTransaction
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.backup.model.validateIdentities
import indi.renakoni.nextvol.data.download.BookDownloadStore
import android.util.Log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.asErr
import com.github.michaelbull.result.runCatching
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import indi.renakoni.nextvol.data.local.room.dao.BookInformationDao
import indi.renakoni.nextvol.data.local.room.dao.BookRecordDao
import indi.renakoni.nextvol.data.local.room.dao.BookVolumesDao
import indi.renakoni.nextvol.data.local.room.dao.BookshelfDao
import indi.renakoni.nextvol.data.local.room.dao.ChapterContentDao
import indi.renakoni.nextvol.data.local.room.dao.DailyCountDao
import indi.renakoni.nextvol.data.local.room.dao.FormattingRuleDao
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.local.room.dao.UserReadingDataDao
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("OPT_IN_USAGE")
@Singleton
class BackupDataManager @Inject constructor(
    private val database: NextVolDatabase,
    private val bookBookInformationDao: BookInformationDao,
    private val bookRecordDao: BookRecordDao,
    private val dailyCountDao: DailyCountDao,
    private val bookshelfDao: BookshelfDao,
    private val chapterContentDao: ChapterContentDao,
    private val bookVolumesDao: BookVolumesDao,
    private val formattingRuleDao: FormattingRuleDao,
    private val userReadingDataDao: UserReadingDataDao,
    private val userDataDao: UserDataDao,
    private val storageUsageRepository: StorageUsageRepository,
    private val statisticsWriteCoordinator: StatisticsWriteCoordinator,
    private val statsRepository: StatsRepository,
    private val downloads: BookDownloadStore,
) {
    companion object {
        const val TAG = "BackupDataManager"
    }

    val currentAppDataVersion = 1
    // Library-wide lists travel with a backup; they are not a selected source's settings.
    private val libraryUserDataPaths = setOf(UserDataPath.ReadingBooks.path,
        UserDataPath.CompletedDownloadBookList.path, UserDataPath.Search.History.path)

    suspend fun exportAppLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true,
        bookmark: Boolean = true,
    ): Result<AppLocalData, Throwable> {
        val localDataList = mutableListOf<LocalData>()
        exportCurrentLocalData(
            localBookCache, bookshelf, readingRecord, settings, bookmark
        ).let {
            it.component1() ?: return it.asErr()
        }.let(localDataList::add)
        val globalLocalData = LocalData.empty()
            .copy(userDataEntities = if (settings) userDataDao.getAllEntities().filter {
                !libraryUserDataPaths.contains(it.path) &&
                        !it.path.startsWith("hnovel/downloads/") &&
                        it.path != UserDataPath.Settings.Data.StorageUsageSnapshot.path
            }
            else emptyList())
        return Ok(
            AppLocalData(
                version = currentAppDataVersion,
                localDataList = localDataList,
                globalLocalData = globalLocalData
            )
        )
    }

    suspend fun exportCurrentLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true,
        bookmark: Boolean = true,
    ): Result<LocalData, Throwable> {
        downloads.prepare()
        val backupDataCollector = BackupDataCollector(
            bookBookInformationDao = bookBookInformationDao,
            bookDownloadDao = database.bookDownloadDao(),
            bookRecordDao = bookRecordDao,
            dailyCountDao = dailyCountDao,
            bookshelfDao = bookshelfDao,
            chapterContentDao = chapterContentDao,
            bookVolumesDao = bookVolumesDao,
            formattingRuleDao = formattingRuleDao,
            userReadingDataDao = userReadingDataDao,
            userDataDao = userDataDao,
            libraryUserDataPaths = libraryUserDataPaths
        ).apply {
            this.localBookCache.enable = localBookCache
            this.bookshelf.enable = bookshelf
            this.readingRecord.enable = readingRecord
            this.settings.enable = settings
        }

        var localBookFiles = emptyList<indi.renakoni.nextvol.data.localbook.LocalBookFileManifest>()
        var readingBookmarks = emptyList<indi.renakoni.nextvol.data.bookmark.ReadingBookmark>()
        var bookAliases = emptyList<indi.renakoni.nextvol.data.local.room.entity.BookAliasEntity>()
        return runCatching {
            statisticsWriteCoordinator.withLock { database.withTransaction {
                backupDataCollector.solve()
                if (bookmark) readingBookmarks = database.readingBookmarkDao().all()
                val localIds = buildSet {
                    addAll(backupDataCollector.bookInformationEntities.map { it.id })
                    addAll(backupDataCollector.bookshelfEntities.flatMap { it.allBookIds })
                    addAll(backupDataCollector.userReadingDataEntities.map { it.id })
                    addAll(backupDataCollector.bookRecordEntities.map { it.bookId })
                    addAll(readingBookmarks.map { it.bookId })
                }
                val references = localFileReferences(localIds, includeLegacyDirectory = !localBookCache)
                bookAliases = database.bookAliasDao().all().filter { it.canonicalId in localIds || it.id in localIds }
                localBookFiles = references.localBookFiles
                backupDataCollector.bookInformationEntities.addAll(references.bookInformationEntities.filter { info ->
                    backupDataCollector.bookInformationEntities.none { it.id == info.id }
                })
                if (!localBookCache) {
                    backupDataCollector.volumeEntities.addAll(references.volumeEntities)
                    backupDataCollector.chapterInformationEntities.addAll(references.chapterInformationEntities)
                }
            } }
        }.andThen {
            Ok(
                LocalData(
                    bookAliases = bookAliases,
                    localBookFiles = localBookFiles,
                    readingBookmarks = readingBookmarks,
                    bookInformationEntities = backupDataCollector.bookInformationEntities,
                    bookRecordEntities = backupDataCollector.bookRecordEntities,
                    dailyCountEntities = backupDataCollector.dailyCountEntities,
                    bookshelfEntities = backupDataCollector.bookshelfEntities,
                    bookshelfBookMetadataEntities = backupDataCollector.bookshelfBookMetadataEntities,
                    chapterContentEntities = backupDataCollector.chapterContentEntities,
                    chapterInformationEntities = backupDataCollector.chapterInformationEntities,
                    formattingRuleEntities = backupDataCollector.formattingRuleEntities,
                    userReadingDataEntities = backupDataCollector.userReadingDataEntities,
                    volumeEntities = backupDataCollector.volumeEntities,
                    userDataEntities = backupDataCollector.userDataEntities,
                    bookDownloadEntities = backupDataCollector.bookDownloadEntities,
                    downloadedChapterEntities = backupDataCollector.downloadedChapterEntities,
                )
            )
        }
    }

    /** Both backup and bookshelf sharing need portable relink evidence, never originals or reading data. */
    internal suspend fun localFileReferences(bookIds: Set<String>, includeLegacyDirectory: Boolean = true): LocalData = database.withTransaction {
        val localIds = bookIds.filter {
            indi.renakoni.nextvol.data.localbook.LocalBookStore.isLocal(indi.renakoni.nextvol.data.book.BookIdentity.book(it))
        }.toSet()
        val files = database.localBookFileManifestDao().all().filter { it.bookId in localIds }
        // Old imported copies have no fingerprint; their full directory is the minimum evidence.
        val volumes = if (includeLegacyDirectory) localIds.filter { key -> files.none { it.bookId == key } }
            .flatMap { database.bookVolumesDao().getVolumeEntitiesByBookId(it) } else emptyList()
        LocalData.empty().copy(
            localBookFiles = files,
            bookInformationEntities = localIds.mapNotNull { database.bookInformationDao().getEntity(it) },
            volumeEntities = volumes,
            chapterInformationEntities = volumes.flatMap { it.chapterIds }.distinct()
                .mapNotNull { database.bookVolumesDao().getChapterInformationEntity(it) },
        )
    }

    suspend fun importAppLocalData(appLocalData: AppLocalData, overwrite: Boolean = false): Result<Unit, Throwable> {
        if (currentAppDataVersion != appLocalData.version) {
            Log.e(TAG, "Unsupported data versions")
            return Err(Error("Unsupported data versions"))
        }
        validateBackup(appLocalData)
        val parts = listOf(appLocalData.globalLocalData) + appLocalData.localDataList
        val caller = currentCoroutineContext()
        val restore: suspend () -> Unit = {
            // Check the caller before committing, but finish the commit and buffer reset together.
            // Cancellation after that point leaves a complete restored library.
            withContext(NonCancellable) {
                downloads.restore(
                    parts.flatMap { it.bookDownloadEntities },
                    parts.flatMap { it.downloadedChapterEntities },
                    legacy = parts.any { part -> part.userDataEntities.any {
                        it.path == UserDataPath.CompletedDownloadBookList.path
                    } },
                    overwrite = overwrite,
                    beforeCommit = { caller.ensureActive() },
                ) {
                    caller.ensureActive()
                    if (overwrite) clearLibraryRows()
                    restoreAliases(parts)
                    for (part in parts) {
                        caller.ensureActive()
                        importRows(part)
                    }
                    storageUsageRepository.invalidateSnapshot()
                }
            }
        }
        // Lock order: statistics buffer -> statistics writer -> download store -> Room.
        if (overwrite) statsRepository.withStatisticsResetLock { restore() }
        else statisticsWriteCoordinator.withLock { restore() }
        return Ok(Unit)
    }

    suspend fun importLocalData(localData: LocalData): Result<Unit, Throwable> =
        importLocalDataToDatabase(localData)

    fun validateBackup(appLocalData: AppLocalData) {
        require(appLocalData.version == currentAppDataVersion) { "Unsupported data version" }
        appLocalData.globalLocalData.validateIdentities()
        appLocalData.localDataList.forEach { it.validateIdentities() }
    }

    suspend fun importLocalDataToDatabase(localData: LocalData): Result<Unit, Throwable> =
        importAppLocalData(AppLocalData(localDataList = listOf(localData), globalLocalData = LocalData.empty()))

    /** The caller holds the statistics/download locks and the entire restore transaction. */
    private suspend fun restoreAliases(parts: List<LocalData>) {
        val dao = database.bookAliasDao()
        val existing = dao.all().associateBy { it.id }
        val incoming = parts.flatMap { it.bookAliases }
        val combined = (existing.values + incoming).groupBy { it.id }
        require(combined.values.all { rows -> rows.map { it.canonicalId }.distinct().size == 1 }) { "Conflicting book aliases" }
        require(combined.values.all { it.first().canonicalId !in combined }) { "Chained book aliases are not supported" }
        val importedRows = parts.flatMap { part ->
            part.bookInformationEntities.map { it.id } + part.userReadingDataEntities.map { it.id } +
                part.volumeEntities.map { it.bookId } + part.bookDownloadEntities.map { it.bookId } +
                part.bookshelfBookMetadataEntities.map { it.id } + part.bookshelfEntities.flatMap { it.allBookIds + it.pinnedBookIds + it.updatedBookIds }
        }
        require(importedRows.none { it in combined }) { "Backup contains obsolete book identities; refresh them before merging" }
        for (alias in incoming.distinctBy { it.id }) {
            if (alias.id in existing) continue
            require(bookBookInformationDao.getEntity(alias.id) == null && userReadingDataDao.getEntity(alias.id) == null &&
                database.bookDownloadDao().get(alias.id) == null && bookshelfDao.getBookshelfBookMetadata(alias.id) == null) {
                "Existing standalone data must be reconciled before merging an alias backup"
            }
            dao.insert(alias)
        }
    }

    /** The caller holds the statistics/download locks and the entire restore transaction. */
    private suspend fun importRows(localData: LocalData) {
          for (file in localData.localBookFiles) database.localBookFileManifestDao().restore(file)
          for (bookmark in localData.readingBookmarks) database.readingBookmarkDao().insert(bookmark)
          for (entity in localData.bookInformationEntities) {
            bookBookInformationDao.insert(
                bookBookInformationDao.getEntity(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.bookRecordEntities) {
            val merged = bookRecordDao
                .getBookRecordByIdAndDate(entity.bookId, entity.date)
                ?.merge(entity)
                ?: entity
            bookRecordDao.insertBookRecord(merged)
          }
          for (entity in localData.dailyCountEntities) {
            val merged = dailyCountDao.getEntity(entity.date)?.merge(entity) ?: entity
            dailyCountDao.insert(merged)
          }
          for (entity in localData.bookshelfEntities) {
            bookshelfDao.insertBookshelf(
                bookshelfDao.getBookshelf(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.bookshelfBookMetadataEntities) {
            bookshelfDao.insertBookshelfBookMetadata(
                bookshelfDao.getBookshelfBookMetadataEntity(
                    entity.id
                )?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.chapterContentEntities) {
            chapterContentDao.cache(chapterContentDao.get(entity.id)?.let(entity::merge) ?: entity)
          }
          for (entity in localData.chapterInformationEntities) {
            bookVolumesDao.insertChapterInformationEntities(
                bookVolumesDao.getChapterInformationEntity(
                    entity.id
                )?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.volumeEntities) {
            bookVolumesDao.insertVolume(
                bookVolumesDao.getVolumeEntity(entity.volumeId)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.formattingRuleEntities) {
            formattingRuleDao.update(
                formattingRuleDao.getBookRuleEntity(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.userReadingDataEntities) {
            userReadingDataDao.insert(
                userReadingDataDao.getEntity(entity.id)?.let(entity::merge) ?: entity
            )
          }
          for (entity in localData.userDataEntities) {
            if (entity.path.startsWith("hnovel/downloads/")) continue
            userDataDao.insert(userDataDao.getEntity(entity.path)?.let(entity::merge) ?: entity)
          }
    }

    /** Explicit overwrite restore only. Source registration and browsing never call this. */
    suspend fun cleanDatabaseWithoutGlobalUserData() {
        importAppLocalData(AppLocalData(localDataList = emptyList(), globalLocalData = LocalData.empty()), overwrite = true)
    }

    private suspend fun clearLibraryRows() {
        database.bookAliasDao().clear()
        database.localBookFileManifestDao().clearUnowned()
        database.readingBookmarkDao().clear()
        bookBookInformationDao.clear()
        bookRecordDao.clear()
        dailyCountDao.clear()
        bookshelfDao.clear()
        bookVolumesDao.clear()
        chapterContentDao.clear()
        formattingRuleDao.clear()
        userReadingDataDao.clear()
        for (entity in userDataDao.getAllEntities()) {
            if (libraryUserDataPaths.contains(entity.path)) userDataDao.remove(entity.path)
        }
    }
}
