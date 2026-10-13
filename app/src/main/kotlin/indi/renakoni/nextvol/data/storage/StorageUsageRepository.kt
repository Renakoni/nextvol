package indi.renakoni.nextvol.data.storage

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.converter.ListConverter
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageUsageRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: NextVolDatabase,
    private val userDataRepository: UserDataRepository
) {
    companion object {
        private const val DB_NAME = NextVolDatabase.NAME
    }

    private val snapshotUserData = userDataRepository.stringUserData(UserDataPath.Settings.Data.StorageUsageSnapshot.path)
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun getCachedSnapshot(): StorageUsageSnapshot? = withContext(Dispatchers.IO) {
        snapshotUserData.get()?.let {
            runCatching { json.decodeFromString<StorageUsageSnapshot>(it) }.getOrNull()?.takeIf { it.schemaVersion == 1 }
        }
    }

    suspend fun refreshSnapshot(): StorageUsageSnapshot = withContext(Dispatchers.IO) {
        val storageStatsDao = database.storageStatsDao()
        val downloadedChapters = database.bookDownloadDao().allChapters()
        val downloadedIds = downloadedChapters.map { it.id }.toSet()
        val owners = database.bookDownloadDao().getAll()
        val preparation = storageStatsDao.getDownloadPreparationBytes().associateBy { it.bookId }
        val directoryBytes = scanDirectories(context.dataDir.canonicalFile)
        fun size(file: File) = directoryBytes[file.canonicalPath] ?: 0L
        val downloadRoot = File(context.filesDir, "book-downloads")
        val bookImageBytes = owners.associate { owner ->
            owner.bookId to size(File(downloadRoot, "${owner.generation}/${BookIdentity.book(owner.bookId).fileKey}"))
        }

        val bookInfoBytesMap = storageStatsDao.getBookInformationBytes()
            .associate { it.id to it.bytes }

        val volumeRows = storageStatsDao.getVolumeStorageRows()
        val bookVolumeBytesMap = mutableMapOf<String, Long>()
        volumeRows.forEach { row ->
            bookVolumeBytesMap[row.bookId] =
                (bookVolumeBytesMap[row.bookId] ?: 0L) + row.bytes
        }
        val chapterToBookMap = chapterOwners(
            catalogChapters = volumeRows.asSequence().flatMap { row ->
                ListConverter.stringToStringList(row.chapterIdList).asSequence()
                    .filter { it.isNotBlank() }.map { it to row.bookId }
            },
            downloadedChapters = downloadedChapters.asSequence().map { it.id to it.bookId },
        )

        var orphanChapterInformationBytes = 0L
        val bookChapterInfoBytesMap = mutableMapOf<String, Long>()
        storageStatsDao.getChapterInformationBytes().forEach { row ->
            val bookId = chapterToBookMap[row.id]
            if (bookId == null) {
                orphanChapterInformationBytes += row.bytes
            } else {
                bookChapterInfoBytesMap[bookId] =
                    (bookChapterInfoBytesMap[bookId] ?: 0L) + row.bytes
            }
        }

        var orphanChapterContentBytes = 0L
        var readingContentBytes = 0L
        var downloadedContentBytes = 0L
        val bookChapterContentBytesMap = mutableMapOf<String, Long>()
        storageStatsDao.getChapterContentBytes().forEach { row ->
            if (row.id in downloadedIds) downloadedContentBytes += row.bytes else readingContentBytes += row.bytes
            val bookId = chapterToBookMap[row.id]
            if (bookId == null) {
                orphanChapterContentBytes += row.bytes
            } else {
                bookChapterContentBytesMap[bookId] =
                    (bookChapterContentBytesMap[bookId] ?: 0L) + row.bytes
            }
        }

        val bookIds = linkedSetOf<String>().apply {
            addAll(bookInfoBytesMap.keys)
            addAll(bookVolumeBytesMap.keys)
            addAll(bookChapterInfoBytesMap.keys)
            addAll(bookChapterContentBytesMap.keys)
            addAll(preparation.keys)
            addAll(bookImageBytes.filterValues { it > 0L }.keys)
        }

        val books = bookIds.map { bookId ->
            BookStorageUsage(
                bookId = bookId,
                bookInformationBytes = bookInfoBytesMap[bookId] ?: 0L,
                volumeBytes = bookVolumeBytesMap[bookId] ?: 0L,
                chapterInformationBytes = bookChapterInfoBytesMap[bookId] ?: 0L,
                chapterContentBytes = bookChapterContentBytesMap[bookId] ?: 0L,
                downloadImageBytes = bookImageBytes[bookId] ?: 0L,
                preparationBytes = preparation[bookId]?.bytes ?: 0L,
            )
        }.sortedByDescending { it.totalBytes }

        val appBytes = getAppFileBytes(context)
        val databaseDiskBytes = getRoomFileBytes(context, DB_NAME)
        val cacheBytes = size(context.cacheDir)
        val downloadImageBytes = size(downloadRoot)
        val importedFileBytes = size(File(context.filesDir, "local-books"))
        val dataBytes = size(context.dataDir)
        val otherFileBytes = (dataBytes - databaseDiskBytes - cacheBytes - downloadImageBytes - importedFileBytes).coerceAtLeast(0L)

        val allBookMetadataBytes =
            bookInfoBytesMap.values.sum() +
                    bookVolumeBytesMap.values.sum() +
                    bookChapterInfoBytesMap.values.sum() +
                    orphanChapterInformationBytes

        val snapshot = StorageUsageSnapshot(
            totalBytes = appBytes + dataBytes,
            appBytes = appBytes,
            databaseDiskBytes = databaseDiskBytes,
            cacheBytes = cacheBytes,
            otherFileBytes = otherFileBytes,
            allBookMetadataBytes = allBookMetadataBytes,
            orphanChapterInfoBytes = orphanChapterInformationBytes,
            orphanChapterContentBytes = orphanChapterContentBytes,
            books = books,
            calculatedAt = System.currentTimeMillis(),
            downloadImageBytes = downloadImageBytes,
            importedFileBytes = importedFileBytes,
            readingContentBytes = readingContentBytes,
            imageCacheBytes = SingletonImageLoader.get(context).diskCache?.size ?: 0L,
            downloadedContentBytes = downloadedContentBytes,
            preparationBytes = preparation.values.sumOf { it.bytes },
            downloadedBookCount = (downloadedChapters.map { it.bookId } + preparation.keys + bookImageBytes.filterValues { it > 0L }.keys).toSet().size,
            downloadedChapterCount = downloadedChapters.size,
            preparingChapterCount = preparation.values.sumOf { it.chapters },
            unfinishedDownloadCount = owners.count { it.taskStatus !in setOf(DownloadTaskStatus.None.name, DownloadTaskStatus.Complete.name) },
            schemaVersion = 1,
            searchCacheBytes = size(File(context.cacheDir, indi.renakoni.nextvol.defaultplugin.wenku8.search.Wenku8SearchCatalog.DIRECTORY)),
        )
        snapshotUserData.set(json.encodeToString(StorageUsageSnapshot.serializer(), snapshot))
        snapshot
    }

    suspend fun invalidateSnapshot() = withContext(Dispatchers.IO) {
        userDataRepository.remove(UserDataPath.Settings.Data.StorageUsageSnapshot.path)
    }

    /** One scan per refresh; links outside private app data are not counted again as app files. */
    private fun scanDirectories(root: File): Map<String, Long> {
        val sizes = mutableMapOf<String, Long>()
        val visited = mutableSetOf<String>()
        fun scan(file: File): Long {
            if (!file.exists()) return 0L
            val path = file.canonicalPath
            if (path != root.path && !path.startsWith(root.path + File.separator)) return 0L
            if (file.isFile) return file.length()
            if (!visited.add(path)) return 0L
            val children = file.listFiles() ?: throw IOException("Cannot measure app storage")
            return children.sumOf(::scan).also { sizes[path] = it }
        }
        scan(root)
        return sizes
    }
}

fun getRoomFileBytes(context: Context, dbName: String): Long {
    val db = context.getDatabasePath(dbName)
    val wal = File(db.path + "-wal")
    val shm = File(db.path + "-shm")
    return listOf(db, wal, shm)
        .filter { it.exists() }
        .sumOf { it.length() }
}

fun getAppFileBytes(context: Context): Long {
    val appInfo = context.applicationInfo
    return buildSet {
        appInfo.sourceDir?.let(::add)
        appInfo.publicSourceDir?.let(::add)
        appInfo.splitSourceDirs?.forEach(::add)
        appInfo.splitPublicSourceDirs?.forEach(::add)
    }.sumOf { path ->
        val file = File(path)
        if (file.exists()) file.length() else 0L
    }
}
