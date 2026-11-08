package indi.renakoni.nextvol.data.download

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import io.nightfish.lightnovelreader.api.userdata.UserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadProgressRepository internal constructor(
    userDataDao: UserDataDao,
    val bookRepository: BookRepository,
    private val downloads: BookDownloadStore,
    private val coroutineScope: CoroutineScope,
) {
    @Inject constructor(userDataDao: UserDataDao, bookRepository: BookRepository, downloads: BookDownloadStore) :
        this(userDataDao, bookRepository, downloads, CoroutineScope(Dispatchers.IO))

    class DownItemListUserData (
        override val path: String,
        private val userDataDao: UserDataDao,
        private val bookRepository: BookRepository
    ) : UserData<List<DownloadItem>>(path) {
        override suspend fun set(value: List<DownloadItem>) {
            userDataDao.insert(path, group, "CompletedDownloadItemList", value.joinToString {
                "${it.type.name}|${it.bookId}"
            })
        }

        suspend fun addCompleted(item: DownloadItem) {
            userDataDao.update(path, group, "CompletedDownloadItemList") { value ->
                (decode(value).orEmpty().filterNot { it == item } + item).joinToString {
                    "${it.type.name}|${it.bookId}"
                }
            }
        }

        override suspend fun get(): List<DownloadItem>? = decode(userDataDao.get(path))

        private fun decode(value: String?): List<DownloadItem>? {
            return value?.split(",")?.mapNotNull {
                val values = it.split("|")
                try {
                    return@mapNotNull MutableDownloadItem(
                        DownloadType.valueOf(values[0].trim()),
                        values[1],
                        bookRepository.getBookInformationFlow(values[1])
                    ).apply { progress = 1f }
                } catch (e: Exception) {
                    Log.e("CompletedDownloadItemList", "Invalid completed download record")
                }
                return@mapNotNull null
            }
        }

        override fun getFlow(): Flow<List<DownloadItem>?> {
            return userDataDao.getFlow(path).map { value ->
                value?.split(",")?.map {
                    val values = it.split("|")
                    MutableDownloadItem(
                        DownloadType.valueOf(values[0].trim()),
                        values[1],
                        bookRepository.getBookInformationFlow(values[1])
                    ).apply { progress = 1f }
                }
            }
        }
    }

    private val completedBookListUserData = DownItemListUserData(UserDataPath.CompletedDownloadBookList.path, userDataDao, bookRepository)
    private val stateLock = Any()
    private val _downloadItemList = mutableStateListOf<DownloadItem>()
    val downloadItemIdList: List<DownloadItem> get() = synchronized(stateLock) { _downloadItemList.toList() }
    private val cacheObservers = mutableMapOf<String, Job>()
    private val exportObservers = mutableMapOf<DownloadItem, Job>()
    private var historyCleared = false
    private var historyUpdate: Job? = null

    init {
        val loadHistory = coroutineScope.launch {
            val completedBookList = completedBookListUserData.getOrDefault(emptyList())
            val saved = completedBookList.filter { it.type != DownloadType.CACHE }
            synchronized(stateLock) {
                if (!historyCleared) _downloadItemList.addAll(saved.filterNot { it in _downloadItemList })
            }
        }
        historyUpdate = loadHistory
        coroutineScope.launch {
            loadHistory.join()
            downloads.prepare()
            downloads.observeEntries().map { owners -> owners.filter { !it.taskHidden && it.taskStatus != DownloadTaskStatus.None.name }
                .map { it.bookId }.toSet() }
                .distinctUntilChanged().collect { ids ->
                    synchronized(stateLock) {
                        _downloadItemList.removeIf { it.type == DownloadType.CACHE && it.bookId !in ids }
                    }
                    cacheObservers.keys.filterNot { it in ids }.forEach { cacheObservers.remove(it)?.cancel() }
                    for (id in ids.filterNot { it in cacheObservers }) {
                        val item = MutableDownloadItem(DownloadType.CACHE, id, bookRepository.downloadInformationFlow(id))
                        synchronized(stateLock) { _downloadItemList.add(item) }
                        cacheObservers[id] = coroutineScope.launch {
                            bookRepository.downloadStatusFlow(id).distinctUntilChanged().collect { status ->
                                item.progress = when {
                                    status.task.active -> (status.content.taskSavedChapters.toFloat() /
                                        status.content.taskTotalChapters.coerceAtLeast(1)).coerceIn(0f, 0.99f)
                                    status.task.status == DownloadTaskStatus.Complete && status.content.selectedChapters != null -> 1f
                                    status.displayPhase == BookDownloadPhase.Complete && !status.task.canResume -> 1f
                                    else -> -1f
                                }
                                item.status = status
                            }
                        }
                    }
                }
        }
    }

    /** Finish background database work before the owning host closes its database. */
    suspend fun close() {
        coroutineScope.coroutineContext.job.cancelAndJoin()
    }

    fun addExportItem(downloadItem: DownloadItem) {
        // Cache items are rebuilt from persistent task facts, not a worker-owned object.
        if (downloadItem.type == DownloadType.CACHE) return
        synchronized(stateLock) {
            exportObservers.remove(downloadItem)?.cancel()
            _downloadItemList.removeIf { it == downloadItem }
            _downloadItemList.add(downloadItem)
            val observer = coroutineScope.launch(start = CoroutineStart.LAZY) {
                val owner = coroutineContext.job
                try {
                    val progress = snapshotFlow { downloadItem.progress }.first { it >= 1f || it < 0f }
                    if (progress >= 1f) {
                        val write = synchronized(stateLock) {
                            if (exportObservers[downloadItem] === owner) {
                                // Once accepted, the write belongs to the repository; a later clear follows it.
                                updateHistory { completedBookListUserData.addCompleted(downloadItem) }
                            } else null
                        }
                        write?.join()
                    }
                } finally {
                    synchronized(stateLock) {
                        if (exportObservers[downloadItem] === owner) exportObservers.remove(downloadItem)
                    }
                }
            }
            exportObservers[downloadItem] = observer
            observer.start()
        }
    }

    fun removeExportItem(downloadItem: DownloadItem) {
        synchronized(stateLock) {
            if (_downloadItemList.none { it === downloadItem }) return
            exportObservers.remove(downloadItem)?.cancel()
            _downloadItemList.remove(downloadItem)
        }
    }

    fun clearCachedItems(bookIds: Set<String>? = null) {
        synchronized(stateLock) {
            _downloadItemList.removeIf { it.type == DownloadType.CACHE && (bookIds == null || it.bookId in bookIds) }
        }
    }

    fun clearCompleted() {
        synchronized(stateLock) {
            historyCleared = true
            val completed = _downloadItemList.filter { it.progress >= 1f }
            val cached = completed.filter { it.type == DownloadType.CACHE }.map { it.bookId }
            completed.filter { it.type != DownloadType.CACHE }.forEach {
                exportObservers.remove(it)?.cancel()
                _downloadItemList.remove(it)
            }
            updateHistory {
                // Persistent state decides whether a cache task can be hidden; the UI may lag a new retry.
                cached.forEach { downloads.dismissTask(indi.renakoni.nextvol.data.book.BookIdentity.book(it), cancel = false) }
                completedBookListUserData.set(emptyList())
            }
        }
    }

    /** Called under stateLock: accepted completions and clears keep business order across IO dispatch. */
    private fun updateHistory(update: suspend () -> Unit): Job {
        val previous = historyUpdate
        return coroutineScope.launch {
            previous?.join()
            update()
        }.also { historyUpdate = it }
    }
}
