package indi.renakoni.nextvol.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.AtomicFile
import androidx.room.withTransaction
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.sourceImageCacheKey
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadedChapterEntity
import indi.renakoni.nextvol.data.local.room.entity.DownloadChapterCandidateEntity
import indi.renakoni.nextvol.data.local.room.entity.ChapterContentEntity
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Download ownership and file commits. Network requests stay outside this store's lock. */
@Singleton
class BookDownloadStore @Inject constructor(@ApplicationContext private val context: Context,
    private val database: NextVolDatabase, private val decoder: ContentJsonDecoder) {
    data class Attempt(val book: SourceBookId, val generation: Long, val id: String, val requireTask: Boolean = false)
    data class Task(val book: SourceBookId, val generation: Long, val workId: String)
    data class ChapterCheckpoint(val content: ChapterContent, val signature: String,
        val images: List<String>, val resourceVersion: String)

    private val dao = database.bookDownloadDao()
    private val lock = Mutex()
    private val bookOperations = ConcurrentHashMap<String, Mutex>()
    private var activeBookOperations = 0 // Guarded by lock, together with ordinary cache cleanup.
    private val root = File(context.filesDir, "book-downloads")
    private val imageKeys = context.getSharedPreferences("source_image_cache_keys", Context.MODE_PRIVATE)

    companion object {
        private const val GROUP = "hnovel/downloads"
        private const val EPOCH = "$GROUP/generation"
        private const val MIGRATED = "$GROUP/legacy-imported"
    }

    fun generation(): Long = database.userDataDao().getEntity(EPOCH)?.value?.toLong() ?: 0
    fun observe(book: SourceBookId) = combine(dao.observe(book.storageKey), dao.observeChapters(book.storageKey),
        dao.observeCandidates(book.storageKey)) { _, _, _ -> Unit }

    suspend fun prepare() = withContext(Dispatchers.IO) { lock.withLock { removeRetiredGenerations(); migrateLegacy() } }
    /** Normal downloads and export preparation must not replace each other's attempt. */
    suspend fun <T> withBookOperation(book: SourceBookId, block: suspend () -> T): T =
        bookOperations.getOrPut(book.storageKey) { Mutex() }.withLock {
            lock.withLock { activeBookOperations++ }
            try { block() }
            finally { withContext(kotlinx.coroutines.NonCancellable) { lock.withLock { activeBookOperations-- } } }
        }
    suspend fun entries() = withContext(Dispatchers.IO) { lock.withLock { migrateLegacy(); dao.getAll() } }
    fun observeEntries() = dao.observeAll()
    suspend fun entry(book: SourceBookId) = withContext(Dispatchers.IO) { dao.get(book.storageKey) }

    suspend fun queueTask(book: SourceBookId, generation: Long, workId: String,
        chapterIds: List<String>? = null, resumePrevious: Boolean = true) = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        if (generation != this@BookDownloadStore.generation()) throw CancellationException("Download was cleared")
        val owner = dao.get(book.storageKey) ?: BookDownloadEntity(book.storageKey, generation = generation)
        val resumeScope = resumePrevious && chapterIds == null && owner.taskStatus in setOf(
            DownloadTaskStatus.Failed.name, DownloadTaskStatus.Interrupted.name, DownloadTaskStatus.Cancelled.name,
            DownloadTaskStatus.WaitingRetry.name, DownloadTaskStatus.WaitingVerification.name)
        dao.put(owner.copy(taskWorkId = workId, taskStatus = DownloadTaskStatus.Queued.name,
            taskStage = DownloadStage.Details.name, taskChapter = "", taskError = "", taskRunAttempt = 0, taskHidden = false,
            taskRetryCount = 0, taskNextAttemptAt = 0, taskSourceRevision = "", taskAccountGeneration = -1,
            taskChapterIds = chapterIds?.let { Json.encodeToString(it) } ?: owner.taskChapterIds.takeIf { resumeScope }.orEmpty(),
            taskChapterFailures = "{}"))
    } }

    suspend fun startTask(book: SourceBookId, generation: Long, workId: String, runAttempt: Int,
        legacy: Boolean = false, nowMillis: Long = System.currentTimeMillis()): Task = withContext(Dispatchers.IO) { lock.withLock {
        currentCoroutineContext().ensureActive()
        if (generation != this@BookDownloadStore.generation()) throw CancellationException("Download was cleared")
        val owner = dao.get(book.storageKey) ?: if (legacy) BookDownloadEntity(book.storageKey, generation = generation)
            else throw CancellationException("Download task was removed")
        if (owner.taskHidden || owner.taskStatus in setOf(DownloadTaskStatus.Cancelled.name, DownloadTaskStatus.WaitingVerification.name) ||
            owner.taskWorkId != workId && !(legacy && owner.taskStatus !in
                listOf(DownloadTaskStatus.Queued.name, DownloadTaskStatus.Running.name, DownloadTaskStatus.WaitingRetry.name, DownloadTaskStatus.WaitingVerification.name)))
            throw CancellationException("Download task was replaced")
        if (owner.taskWorkId == workId && owner.taskStatus in
            setOf(DownloadTaskStatus.Complete.name, DownloadTaskStatus.Failed.name, DownloadTaskStatus.Interrupted.name))
            return@withLock Task(book, generation, workId)
        val current = if (owner.taskWorkId == workId) owner else owner.copy(
            taskRetryCount = 0, taskNextAttemptAt = 0, taskSourceRevision = "", taskAccountGeneration = -1)
        val waiting = current.taskNextAttemptAt > nowMillis
        dao.put(current.copy(taskWorkId = workId,
            taskStatus = if (waiting) DownloadTaskStatus.WaitingRetry.name else DownloadTaskStatus.Running.name,
            taskStage = if (waiting) owner.taskStage else DownloadStage.Details.name,
            taskChapter = if (waiting) owner.taskChapter else "", taskError = if (waiting) owner.taskError else "",
            taskRunAttempt = runAttempt))
        Task(book, generation, workId)
    } }

    private suspend fun updateTask(task: Task, update: (BookDownloadEntity) -> BookDownloadEntity) =
        withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive()
            val owner = dao.get(task.book.storageKey)
            if (task.generation != generation() || owner == null || owner.taskWorkId != task.workId ||
                owner.taskStatus == DownloadTaskStatus.Cancelled.name)
                throw CancellationException("Download task was cleared or replaced")
            dao.put(update(checkNotNull(owner)))
        } }

    suspend fun taskStage(task: Task, stage: DownloadStage, chapterId: String = "") = updateTask(task) {
        it.copy(taskStage = stage.name, taskChapter = chapterId, taskError = "",
            taskChapterFailures = if (stage == DownloadStage.Body && chapterId.isNotEmpty())
                Json.encodeToString(it.chapterFailures().mapValues { entry -> entry.value.name } - BookIdentity.chapter(chapterId, task.book).remoteId)
            else it.taskChapterFailures)
    }

    suspend fun chapterFailure(task: Task, chapterId: String, failure: DownloadFailure) = updateTask(task) {
        it.copy(taskChapterFailures = Json.encodeToString(it.chapterFailures().mapValues { entry -> entry.value.name } +
            (BookIdentity.chapter(chapterId, task.book).remoteId to failure.name)))
    }

    suspend fun finishTask(task: Task, failure: DownloadFailure? = null) = updateTask(task) {
        it.copy(taskStatus = when (failure) {
            null -> DownloadTaskStatus.Complete.name
            DownloadFailure.Authentication, DownloadFailure.Verification -> DownloadTaskStatus.WaitingVerification.name
            else -> DownloadTaskStatus.Failed.name
        },
            taskError = failure?.name.orEmpty(), taskNextAttemptAt = 0)
    }

    suspend fun bindTaskSource(task: Task, revision: String, accountGeneration: Long) = updateTask(task) {
        it.copy(taskSourceRevision = revision, taskAccountGeneration = accountGeneration)
    }

    suspend fun interruptTask(task: Task, failure: DownloadFailure = DownloadFailure.SystemRestricted) = updateTask(task) {
        it.copy(taskStatus = DownloadTaskStatus.Interrupted.name,
            taskError = failure.name, taskNextAttemptAt = 0)
    }

    /** A verification result can replace only the captured task, never a cancelled or newer one. */
    suspend fun queueVerifiedTask(expected: BookDownloadEntity, workId: String): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val owner = dao.get(expected.bookId) ?: return@withLock false
        if (owner.taskHidden || owner.generation != generation() || owner.generation != expected.generation ||
            owner.taskWorkId != expected.taskWorkId || owner.taskSourceRevision != expected.taskSourceRevision ||
            owner.taskAccountGeneration != expected.taskAccountGeneration || owner.taskStatus !in
                setOf(DownloadTaskStatus.WaitingVerification.name, DownloadTaskStatus.Running.name)) return@withLock false
        dao.put(owner.copy(taskWorkId = workId, taskStatus = DownloadTaskStatus.Queued.name, attempt = "",
            taskError = "", taskRunAttempt = 0, taskRetryCount = 0, taskNextAttemptAt = 0))
        true
    } }

    /** Reserve the next recovery before returning control to WorkManager; restart never refunds it. */
    suspend fun deferTaskRetry(task: Task, expectedRetries: Int, nextAttemptAt: Long, failure: DownloadFailure) = updateTask(task) {
        check(it.taskRetryCount == expectedRetries)
        it.copy(taskStatus = DownloadTaskStatus.WaitingRetry.name, taskError = failure.name,
            taskRetryCount = expectedRetries + 1, taskNextAttemptAt = nextAttemptAt)
    }

    /** Removing/disabling/replacing a source must also retire queued recovery before it can be re-added. */
    suspend fun revokeSourceTasks(source: io.nightfish.lightnovelreader.api.identifier.Identifier) =
        withContext(Dispatchers.IO) { lock.withLock {
            for (owner in dao.getAll()) {
                if (BookIdentity.book(owner.bookId).sourceId == source && owner.taskStatus in
                    setOf(DownloadTaskStatus.Queued.name, DownloadTaskStatus.Running.name, DownloadTaskStatus.WaitingRetry.name,
                        DownloadTaskStatus.Interrupted.name, DownloadTaskStatus.WaitingVerification.name)) {
                    dao.put(owner.copy(taskWorkId = "", attempt = "", taskStatus = DownloadTaskStatus.Cancelled.name,
                        taskError = DownloadFailure.SourceUnavailable.name, taskNextAttemptAt = 0))
                }
            }
        } }

    /** Hiding/cancelling a task does not delete its downloaded chapters or images. */
    suspend fun dismissTask(book: SourceBookId, cancel: Boolean = true) = withContext(Dispatchers.IO) { lock.withLock {
        val owner = dao.get(book.storageKey) ?: return@withLock
        if (!cancel && owner.taskStatus in listOf(DownloadTaskStatus.Queued.name, DownloadTaskStatus.Running.name, DownloadTaskStatus.WaitingRetry.name, DownloadTaskStatus.WaitingVerification.name)) return@withLock
        dao.put(owner.copy(taskHidden = true,
            taskStatus = if (cancel) DownloadTaskStatus.Cancelled.name else owner.taskStatus,
            attempt = if (cancel && owner.attempt == owner.taskWorkId) "" else owner.attempt))
    } }

    suspend fun revision(book: SourceBookId): String? = withContext(Dispatchers.IO) {
        lock.withLock { dao.get(book.storageKey)?.revision }
    }

    /** Keep files until the identity transaction commits; old download attempts lose ownership. */
    internal suspend fun mergeIdentity(from: SourceBookId, to: SourceBookId, volumes: BookVolumes,
        commit: suspend () -> Unit) = withContext(Dispatchers.IO) { lock.withLock {
        require(from.sourceId == to.sourceId && volumes.bookId == to.storageKey)
        migrateLegacy()
        val previous = dao.get(from.storageKey)
        val existing = dao.get(to.storageKey)
        val generation = existing?.generation ?: generation()
        val chapterIds = volumes.volumes.flatMap { it.chapters }.map { it.id }.toSet()
        val retained = dao.chapters(from.storageKey).mapNotNull { chapter ->
            val id = SourceChapterId(to, BookIdentity.chapter(chapter.id, from).remoteId).storageKey
            if (id in chapterIds) chapter.copy(id = id, bookId = to.storageKey) else null
        }
        if (previous != null) {
            for ((uri, version) in retained.flatMap { chapter ->
                Json.decodeFromString<List<String>>(chapter.images).map { it to chapter.resourceVersion }
            }.distinct()) {
                currentCoroutineContext().ensureActive()
                val source = imageFile(from, previous.generation, uri, false, version)
                val destination = imageFile(to, generation, uri, false, version)
                if (!source.isFile || destination.isFile) continue
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                val atomic = AtomicFile(destination)
                val output = atomic.startWrite()
                try {
                    source.inputStream().use { it.copyTo(output) }
                    atomic.finishWrite(output)
                } catch (failure: Exception) {
                    atomic.failWrite(output)
                    throw failure
                }
                // A migration preserves offline bytes, not proof of the new directory's version.
                check(staleMarker(destination).isFile || staleMarker(destination).createNewFile())
            }
        }
        database.withTransaction {
            commit()
            if (previous != null) {
                val destination = existing ?: BookDownloadEntity(to.storageKey, generation = generation)
                if (previous.taskWorkId.isNotEmpty() && destination.taskStatus !in
                    listOf(DownloadTaskStatus.Queued.name, DownloadTaskStatus.Running.name, DownloadTaskStatus.WaitingRetry.name)) {
                    dao.put(destination.copy(taskWorkId = previous.taskWorkId, taskStatus = previous.taskStatus,
                        taskStage = previous.taskStage, taskChapter = "", taskError = previous.taskError,
                        taskRunAttempt = previous.taskRunAttempt, taskHidden = previous.taskHidden,
                        taskRetryCount = previous.taskRetryCount, taskNextAttemptAt = previous.taskNextAttemptAt,
                        taskSourceRevision = previous.taskSourceRevision, taskAccountGeneration = previous.taskAccountGeneration,
                        taskChapterIds = previous.taskChapterIds,
                        taskChapterFailures = previous.taskChapterFailures))
                } else if (existing == null) dao.put(destination)
                retained.forEach { chapter ->
                    if (dao.chapter(chapter.id) == null) dao.put(chapter)
                }
                dao.deleteChapters(listOf(from.storageKey))
                dao.deleteCandidates(listOf(from.storageKey))
                dao.deleteBooks(listOf(from.storageKey))
            }
        }
    } }

    internal fun chapterImages(chapter: ChapterContent): List<String> = buildList {
        decoder.getDataFromJsonObject(chapter.content) { if (it is ImageComponentData) add(it.uri.toString()) }
    }.distinct()

    internal fun isReusableReadingContent(chapter: ChapterContent): Boolean = try {
        var count = 0
        decoder.decodeForExport(chapter.content) { count++ }
        count > 0
    } catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { false }

    private fun imageFile(book: SourceBookId, generation: Long, uri: String, cover: Boolean, version: String = ""): File {
        val directory = if (version.isEmpty()) "" else "resources/${downloadHash(version)}/"
        return File(root, "$generation/${book.fileKey}/$directory${downloadHash(BookIdentity.encode("download-image", listOf(cover.toString(), uri)))}")
    }

    suspend fun image(image: SourceImage): File? = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        val canonical = database.bookAliasDao().get(image.book.storageKey)?.let(SourceBookId::fromStorageKey) ?: image.book
        val owner = dao.get(canonical.storageKey) ?: return@withLock null
        val chapter = if (image.cover) null else if (image.chapterId != null) dao.chapter(
            SourceChapterId(canonical, BookIdentity.chapter(image.chapterId, image.book).remoteId).storageKey)
            ?.takeIf { it.bookId == canonical.storageKey } else dao.chapters(canonical.storageKey).firstOrNull {
                image.uri in Json.decodeFromString<List<String>>(it.images)
            }
        imageFile(canonical, owner.generation, image.uri, image.cover, chapter?.resourceVersion.orEmpty()).takeIf { it.isFile }
    } }

    suspend fun begin(book: SourceBookId, generation: Long, id: String, requireTask: Boolean = false): Attempt = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        currentCoroutineContext().ensureActive()
        if (database.bookAliasDao().get(book.storageKey) != null) throw CancellationException("Book identity changed")
        if (generation != this@BookDownloadStore.generation()) throw CancellationException("Download was cleared")
        val previous = dao.get(book.storageKey) ?: BookDownloadEntity(book.storageKey, generation = generation)
        if (requireTask && (previous.taskWorkId != id || previous.taskStatus != DownloadTaskStatus.Running.name))
            throw CancellationException("Download task was cancelled or replaced")
        if (previous.taskWorkId == id && previous.taskStatus == DownloadTaskStatus.Cancelled.name)
            throw CancellationException("Download task was cancelled")
        dao.put(previous.copy(phase = "updating", generation = generation, attempt = id))
        Attempt(book, generation, id, requireTask)
    } }

    private suspend fun <T> current(attempt: Attempt, block: suspend (BookDownloadEntity) -> T): T =
        withContext(Dispatchers.IO) { lock.withLock {
            currentCoroutineContext().ensureActive()
            val owner = dao.get(attempt.book.storageKey)
            if (attempt.generation != generation() || owner == null || owner.attempt != attempt.id ||
                attempt.requireTask && owner.taskWorkId != attempt.id ||
                owner.taskWorkId == attempt.id && owner.taskStatus == DownloadTaskStatus.Cancelled.name)
                throw CancellationException("Download was cleared or replaced")
            block(checkNotNull(owner))
        } }

    suspend fun target(attempt: Attempt, volumes: BookVolumes, revision: String, coverUri: String) = current(attempt) {
        require(volumes.bookId == attempt.book.storageKey)
        val hash = downloadDirectoryHash(volumes)
        val unchanged = it.directoryHash == hash && it.revision == revision && it.coverUri == coverUri
        if (it.revision != revision) {
            // Keep old bytes readable, but a failed/text-only update must not label them current.
            val directory = imageFile(attempt.book, attempt.generation, "", false).parentFile!!
            directory.listFiles()?.filter { file -> file.isFile && file.name.matches(Regex("[0-9a-f]{64}")) }
                ?.forEach { file -> check(staleMarker(file).isFile || staleMarker(file).createNewFile()) }
        }
        dao.put(it.copy(directoryHash = hash, revision = revision, coverUri = coverUri))
        val chapters = volumes.volumes.flatMap { it.chapters }.distinctBy { it.id }
        val signatures = chapters.mapIndexed { index, chapter -> chapter.id to downloadChapterSignature(chapters, index, revision) }.toMap()
        dao.candidates(attempt.book.storageKey).filter { candidate -> signatures[candidate.id] != candidate.signature }
            .forEach { candidate -> dao.deleteCandidate(candidate.id) }
        pruneResources(attempt.book, attempt.generation)
        unchanged
    }

    private fun ChapterContentEntity.toContent() =
        ChapterContent(id, title, content, prevChapter.ifEmpty { null }, nextChapter.ifEmpty { null })

    suspend fun checkpoint(attempt: Attempt, chapterId: String, signature: String): ChapterCheckpoint? = current(attempt) {
        dao.candidate(chapterId)?.takeIf { it.bookId == attempt.book.storageKey && it.signature == signature }?.let { candidate ->
            return@current ChapterCheckpoint(Json.decodeFromString<ChapterContentEntity>(candidate.body).toContent(),
                signature, Json.decodeFromString(candidate.images), candidate.resourceVersion)
        }
        val saved = dao.chapter(chapterId)?.takeIf { it.bookId == attempt.book.storageKey && it.signature == signature }
            ?: return@current null
        database.chapterContentDao().get(chapterId)?.let { body ->
            ChapterCheckpoint(body.toContent(), signature, Json.decodeFromString(saved.images), saved.resourceVersion)
        }
    }

    suspend fun readingContent(attempt: Attempt, chapterId: String, signature: String, revision: String): ChapterContent? = current(attempt) {
        database.chapterContentDao().get(chapterId)?.takeIf { revision.isNotEmpty() && it.sourceRevision == revision }
            ?.toContent()?.takeIf { downloadChapterSignature(it, revision) == signature && isReusableReadingContent(it) }
    }

    suspend fun saveCandidate(attempt: Attempt, chapter: ChapterContent, signature: String): ChapterCheckpoint = current(attempt) {
        require(SourceChapterId.fromStorageKey(chapter.id).book == attempt.book)
        listOfNotNull(chapter.prevChapter, chapter.nextChapter).forEach { id ->
            require(SourceChapterId.fromStorageKey(id).book == attempt.book)
        }
        val images = chapterImages(chapter)
        val version = java.util.UUID.randomUUID().toString()
        val body = ChapterContentEntity(chapter.id, chapter.title, chapter.content,
            chapter.prevChapter.orEmpty(), chapter.nextChapter.orEmpty())
        dao.put(DownloadChapterCandidateEntity(chapter.id, attempt.book.storageKey, signature,
            Json.encodeToString(body), Json.encodeToString(images), version))
        ChapterCheckpoint(chapter, signature, images, version)
    }

    suspend fun checkpointImage(attempt: Attempt, checkpoint: ChapterCheckpoint, uri: String): File? = current(attempt) {
        val file = imageFile(attempt.book, attempt.generation, uri, false, checkpoint.resourceVersion)
        file.takeIf { it.isFile && it.length() > 0 && !staleMarker(it).exists() }
    }

    suspend fun hasCheckpointImage(attempt: Attempt, checkpoint: ChapterCheckpoint, uri: String): Boolean =
        checkpointImage(attempt, checkpoint, uri) != null

    suspend fun publish(attempt: Attempt, checkpoint: ChapterCheckpoint) = saveChapter(attempt, checkpoint.content,
        checkpoint.signature, checkpoint.images, resourceVersion = checkpoint.resourceVersion)

    private suspend fun pruneResources(book: SourceBookId, generation: Long) {
        val retained = (dao.chapters(book.storageKey).map { it.resourceVersion } +
            dao.candidates(book.storageKey).map { it.resourceVersion }).map(::downloadHash).toSet()
        File(root, "$generation/${book.fileKey}/resources").listFiles()?.filter { it.name !in retained }
            ?.forEach { it.deleteRecursively() }
    }

    suspend fun reusable(attempt: Attempt, chapterId: String, signature: String): ChapterContent? = current(attempt) {
        val saved = dao.chapter(chapterId)?.takeIf { it.bookId == attempt.book.storageKey && it.signature == signature }
            ?: return@current null
        database.chapterContentDao().get(saved.id)?.let { body ->
            ChapterContent(body.id, body.title, body.content, body.prevChapter.ifEmpty { null }, body.nextChapter.ifEmpty { null })
        }
    }

    suspend fun hasVersionedChapter(attempt: Attempt, chapterId: String): Boolean = current(attempt) {
        // Unversioned pre-upgrade downloads remain usable offline, like ordinary reading cache.
        !dao.chapter(chapterId)?.signature.isNullOrEmpty()
    }

    suspend fun hasImage(attempt: Attempt, uri: String, cover: Boolean = false): Boolean = current(attempt) {
        val version = if (cover) "" else dao.chapters(attempt.book.storageKey).firstOrNull {
            uri in Json.decodeFromString<List<String>>(it.images)
        }?.resourceVersion.orEmpty()
        val file = imageFile(attempt.book, attempt.generation, uri, cover, version)
        file.isFile && !staleMarker(file).exists()
    }

    private fun staleMarker(file: File) = File(file.parentFile, "${file.name}.stale")

    suspend fun isImageStale(attempt: Attempt, uri: String, cover: Boolean): Boolean = current(attempt) {
        staleMarker(imageFile(attempt.book, attempt.generation, uri, cover)).exists()
    }

    suspend fun saveImage(attempt: Attempt, uri: String, cover: Boolean, bytes: ByteArray, resourceVersion: String = "") = current(attempt) {
        writeImage(imageFile(attempt.book, attempt.generation, uri, cover, resourceVersion), bytes)
    }

    /** Copy the successfully decoded source bytes, not a resized/re-encoded bitmap. */
    suspend fun retainImage(attempt: Attempt, image: SourceImage, cacheKey: String?, resourceVersion: String = "") = current(attempt) {
        require(image.book == attempt.book)
        val bytes = cachedImageBytes(image, cacheKey) ?: error("Downloaded image bytes are unavailable")
        writeImage(imageFile(image.book, attempt.generation, image.uri, image.cover, resourceVersion), bytes)
    }

    private fun writeImage(target: File, bytes: ByteArray) {
        check(bytes.isNotEmpty()) { "Downloaded image is empty" }
        val previousTime = target.lastModified()
        val file = AtomicFile(target)
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        check(file.openRead().use { it.readBytes().contentEquals(bytes) }) { "Download image was not committed" }
        // File mtime participates in Coil's key; two same-sized updates in one tick must differ.
        if (target.lastModified() <= previousTime)
            check(target.setLastModified(previousTime + 1)) { "Could not update downloaded image version" }
        val stale = staleMarker(target)
        check(!stale.exists() || stale.delete()) { "Could not commit downloaded image version" }
    }

    suspend fun saveChapter(attempt: Attempt, chapter: ChapterContent, signature: String, images: List<String>,
        requireImages: Boolean = true, resourceVersion: String = "") = current(attempt) {
        val version = resourceVersion.ifEmpty {
            dao.chapter(chapter.id)?.takeIf { it.signature == signature }?.resourceVersion.orEmpty()
        }
        require(SourceChapterId.fromStorageKey(chapter.id).book == attempt.book)
        listOfNotNull(chapter.prevChapter, chapter.nextChapter).forEach {
            require(SourceChapterId.fromStorageKey(it).book == attempt.book)
        }
        if (requireImages) check(images.all { uri ->
            val file = imageFile(attempt.book, attempt.generation, uri, false, version)
            file.isFile && !staleMarker(file).exists()
        })
        database.withTransaction {
            database.chapterContentDao().update(chapter)
            dao.put(DownloadedChapterEntity(chapter.id, attempt.book.storageKey, signature, Json.encodeToString(images), version))
            dao.deleteCandidate(chapter.id)
        }
    }

    suspend fun finish(attempt: Attempt, success: Boolean) = current(attempt) {
        dao.put(it.copy(phase = if (success) "complete" else "failed", attempt = ""))
        pruneResources(attempt.book, attempt.generation)
    }

    /** Downloaded chapters are final: source revisions and directory changes never make them outdated. */
    suspend fun state(book: SourceBookId, volumes: BookVolumes?, active: Boolean,
        contentOnly: Boolean = false): BookDownloadState =
        withContext(Dispatchers.IO) { lock.withLock {
            migrateLegacy()
            val owner = dao.get(book.storageKey) ?: return@withLock BookDownloadState(
                if (active) BookDownloadPhase.Updating else BookDownloadPhase.None)
            val chapters = volumes?.volumes.orEmpty().flatMap { it.chapters }.distinctBy { it.id }
            val records = dao.chapters(book.storageKey).associateBy { it.id }
            val candidates = dao.candidates(book.storageKey).associateBy { it.id }
            val savedIds = dao.savedContentIds(book.storageKey).toSet()
            val bodies = chapters.count { it.id in candidates || it.id in savedIds }
            val missingImages = chapters.sumOf { chapter ->
                val saved = records[chapter.id]
                val candidate = candidates[chapter.id]
                Json.decodeFromString<List<String>>(saved?.images ?: candidate?.images ?: "[]").count { uri ->
                    val file = imageFile(book, owner.generation, uri, false,
                        saved?.resourceVersion ?: candidate?.resourceVersion.orEmpty())
                    !file.isFile || file.length() == 0L
                }
            }
            val savedChapters = chapters.filter { chapter -> chapterSaved(book, owner, records[chapter.id], savedIds) }.map { it.id }.toSet()
            val count = savedChapters.size
            val selection = owner.selectedChapterIds()
            val taskCount = chapters.count { chapter ->
                (selection == null || BookIdentity.chapter(chapter.id, book).remoteId in selection) && chapter.id in savedChapters
            }
            val coverSaved = owner.coverUri.isEmpty() || imageFile(book, owner.generation, owner.coverUri, true).isFile
            val phase = when {
                active -> BookDownloadPhase.Updating
                !contentOnly && owner.phase == "failed" -> BookDownloadPhase.Failed
                !contentOnly && owner.phase == "updating" -> BookDownloadPhase.Partial // interrupted process
                chapters.isNotEmpty() && count == chapters.size && coverSaved -> BookDownloadPhase.Complete
                else -> BookDownloadPhase.Partial
            }
            BookDownloadState(phase, count, chapters.size, bodies, missingImages, !coverSaved,
                selection?.size, taskCount, selection?.size ?: chapters.size)
        } }

    /** Per-chapter results are read only by the single-book page, not every global task card. */
    suspend fun selectionState(book: SourceBookId, volumes: BookVolumes): DownloadSelectionState =
        withContext(Dispatchers.IO) { lock.withLock {
            migrateLegacy()
            val owner = dao.get(book.storageKey) ?: return@withLock DownloadSelectionState()
            val chapters = volumes.volumes.flatMap { it.chapters }.distinctBy { it.id }
            val records = dao.chapters(book.storageKey).associateBy { it.id }
            val savedIds = dao.savedContentIds(book.storageKey).toSet()
            val failures = owner.chapterFailures()
            DownloadSelectionState(chapters.associate { chapter ->
                chapter.id to DownloadChapterState(chapterSaved(book, owner, records[chapter.id], savedIds),
                    failures[BookIdentity.chapter(chapter.id, book).remoteId])
            }, owner.selectedChapterIds())
        } }

    /** A later run never fetches these again, whatever changed at the source. */
    suspend fun downloadedChapterIds(attempt: Attempt): Set<String> = current(attempt) { owner ->
        val savedIds = dao.savedContentIds(attempt.book.storageKey).toSet()
        dao.chapters(attempt.book.storageKey).filter { chapterSaved(attempt.book, owner, it, savedIds) }.map { it.id }.toSet()
    }

    /** A chapter is downloaded once its body and every image file it references are on disk. */
    private fun chapterSaved(book: SourceBookId, owner: BookDownloadEntity, saved: DownloadedChapterEntity?,
        savedIds: Set<String>): Boolean = saved != null && saved.id in savedIds &&
        Json.decodeFromString<List<String>>(saved.images).all {
            val file = imageFile(book, owner.generation, it, false, saved.resourceVersion)
            file.isFile && file.length() > 0
        }

    suspend fun clearReadingCache(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        // Image decoding and retaining its original bytes span separate store calls.
        if (activeBookOperations > 0) return@withLock false
        migrateLegacy()
        dao.clearReadingContent()
        SingletonImageLoader.get(context).apply { memoryCache?.clear(); diskCache?.clear() }
        true
    } }

    /** Return the retired generation so WorkManager cancels only requests submitted before this clear. */
    suspend fun clearDownloads(): Long = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        val retired = generation()
        val owners = dao.getAll()
        val images = imagesFor(owners)
        database.withTransaction {
            database.userDataDao().insert(EPOCH, GROUP, "Long", Math.addExact(retired, 1).toString())
            dao.clearDownloadedContent(); dao.clearChapters(); dao.clearCandidates(); dao.clearBooks()
            clearHistory(null)
        }
        clearCachedImages(images)
        check(!root.exists() || root.deleteRecursively()) { "Could not remove downloaded images" }
        retired
    } }

    /** Explicit per-book removal; the caller cancels that book's unique WorkManager request first. */
    suspend fun removeBooks(books: List<SourceBookId>) = withContext(Dispatchers.IO) { lock.withLock {
        migrateLegacy()
        val keys = books.map { it.storageKey }
        val owners = keys.mapNotNull { dao.get(it) }
        val images = imagesFor(owners)
        database.withTransaction {
            dao.deleteContent(keys); dao.deleteChapters(keys); dao.deleteCandidates(keys); dao.deleteBooks(keys)
            clearHistory(keys.toSet())
        }
        clearCachedImages(images)
        for (book in books) {
            val generation = owners.find { it.bookId == book.storageKey }?.generation ?: generation()
            val directory = File(root, "$generation/${book.fileKey}")
            check(!directory.exists() || directory.deleteRecursively()) { "Could not remove downloaded images" }
        }
    } }

    private suspend fun imagesFor(owners: List<BookDownloadEntity>): List<SourceImage> = owners.flatMap { owner ->
        val book = SourceBookId.fromStorageKey(owner.bookId)
        dao.chapters(owner.bookId).flatMap { Json.decodeFromString<List<String>>(it.images) }
            .map { SourceImage(book, it) } +
            listOfNotNull(owner.coverUri.takeIf(String::isNotEmpty)?.let { SourceImage(book, it, true) })
    }

    private suspend fun clearHistory(bookIds: Set<String>?) {
        database.userDataDao().getEntity(UserDataPath.CompletedDownloadBookList.path)?.let { history ->
            database.userDataDao().insert(history.copy(value = history.value.split(',').filterNot { entry ->
                val fields = entry.trim().split('|')
                fields.size == 2 && fields[0] == DownloadType.CACHE.name && (bookIds == null || fields[1] in bookIds)
            }.joinToString(",")))
        }
    }

    private fun clearCachedImages(images: List<SourceImage>) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        images.forEach { image ->
            imageKeys.getString(sourceImageCacheKey(image, ""), null)?.let { loader.diskCache?.remove(it) }
        }
    }

    /**
     * The caller holds the statistics lock. The library and download ownership commit together.
     * Overwrite uses a new file generation: rollback/process death can never erase the old files.
     * Backups contain bodies/ownership, not running jobs or image files; missing images stay partial.
     */
    suspend fun restore(
        books: List<BookDownloadEntity>, chapters: List<DownloadedChapterEntity>, legacy: Boolean,
        overwrite: Boolean = false, beforeCommit: () -> Unit = {}, writeLibrary: suspend () -> Unit = {},
    ) = withContext(Dispatchers.IO) { lock.withLock {
        val images = if (overwrite) imagesFor(dao.getAll()) else emptyList()
        removeRetiredGenerations()
        database.withTransaction {
            if (overwrite) {
                val next = Math.addExact(generation(), 1)
                check(!File(root, next.toString()).exists()) { "Download restore directory is unavailable" }
                database.userDataDao().insert(EPOCH, GROUP, "Long", next.toString())
                dao.clearDownloadedContent(); dao.clearChapters(); dao.clearCandidates(); dao.clearBooks()
                clearHistory(null)
            }
            writeLibrary()
            for (book in books) {
                if (dao.get(book.bookId) != null) continue
                val status = when (book.taskStatus) {
                    DownloadTaskStatus.Running.name, DownloadTaskStatus.Queued.name, DownloadTaskStatus.WaitingRetry.name -> DownloadTaskStatus.Interrupted.name
                    DownloadTaskStatus.None.name -> when (book.phase) {
                        "complete" -> DownloadTaskStatus.Complete.name
                        "failed" -> DownloadTaskStatus.Failed.name
                        else -> DownloadTaskStatus.Interrupted.name
                    }
                    else -> book.taskStatus
                }
                dao.put(book.copy(generation = generation(), attempt = "", phase = "partial", taskWorkId = "", taskStatus = status,
                    taskNextAttemptAt = 0))
                chapters.filter { it.bookId == book.bookId }.forEach { chapter ->
                    if (database.chapterContentDao().getId(chapter.id) != null) dao.put(chapter)
                }
            }
            if (legacy) database.userDataDao().remove(MIGRATED)
            migrateLegacy()
            beforeCommit()
        }
        // Cleanup is recoverable maintenance after the commit, never a failed restore.
        if (overwrite) runCatching { clearCachedImages(images) }.onFailure {
            Log.w("BookDownloadStore", "Could not evict retired image cache", it)
        }
        runCatching { removeRetiredGenerations() }.onFailure {
            Log.w("BookDownloadStore", "Could not clean retired downloads after restore", it)
        }
    } }

    /** Retry abandoned staging/retired file cleanup after process recreation. Keep every live owner. */
    private suspend fun removeRetiredGenerations() {
        val keep = dao.getAll().map { it.generation }.toSet() + generation()
        root.listFiles()?.forEach { directory ->
            val generation = directory.name.toLongOrNull() ?: return@forEach
            if (generation !in keep) runCatching {
                check(directory.deleteRecursively()) { "Could not remove retired download generation" }
            }.onFailure { Log.w("BookDownloadStore", "Could not clean retired downloads", it) }
        }
    }

    /** Old completed CACHE records establish intent; ordinary reading hits do not become downloads. */
    private suspend fun migrateLegacy() {
        if (database.userDataDao().get(MIGRATED) != null) return
        val entries = database.userDataDao().get(UserDataPath.CompletedDownloadBookList.path).orEmpty().split(',')
        for (entry in entries) {
            val fields = entry.trim().split('|')
            if (fields.size != 2 || fields[0] != DownloadType.CACHE.name) continue
            val book = runCatching { BookIdentity.book(fields[1]) }.getOrNull() ?: continue
            if (dao.get(book.storageKey) != null) continue
            val volumes = database.bookVolumesDao().getBookVolumes(book.storageKey) ?: continue
            val cover = database.bookInformationDao().get(book.storageKey)?.coverUri?.toString().orEmpty()
            val owner = BookDownloadEntity(book.storageKey, directoryHash = downloadDirectoryHash(volumes),
                generation = generation(), coverUri = cover, taskStatus = DownloadTaskStatus.Complete.name)
            val savedChapters = mutableListOf<DownloadedChapterEntity>()
            for (chapter in volumes.volumes.flatMap { it.chapters }) {
                val body = database.chapterContentDao().get(chapter.id) ?: continue
                val images = mutableListOf<String>()
                decoder.getDataFromJsonObject(body.content) { if (it is ImageComponentData) images += it.uri.toString() }
                images.distinct().forEach { retainLegacyImage(owner, SourceImage(book, it)) }
                savedChapters += DownloadedChapterEntity(chapter.id, book.storageKey, "", Json.encodeToString(images.distinct()))
            }
            if (cover.isNotEmpty()) retainLegacyImage(owner, SourceImage(book, cover, true))
            // If an image copy fails, leave this book eligible for migration on the next attempt.
            database.withTransaction {
                dao.put(owner)
                savedChapters.forEach { dao.put(it) }
            }
        }
        database.userDataDao().insert(MIGRATED, GROUP, "Boolean", "true")
    }

    private fun retainLegacyImage(owner: BookDownloadEntity, image: SourceImage) {
        val bytes = cachedImageBytes(image, null) ?: return
        writeImage(imageFile(image.book, owner.generation, image.uri, image.cover), bytes)
    }

    private fun cachedImageBytes(image: SourceImage, cacheKey: String?): ByteArray? {
        val uri = Uri.parse(image.uri)
        if (uri.scheme in setOf("file", "content", "android.resource"))
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val key = cacheKey ?: imageKeys.getString(sourceImageCacheKey(image, ""), null) ?: return null
        val cache = SingletonImageLoader.get(context).diskCache ?: return null
        return cache.openSnapshot(key)?.use { snapshot -> cache.fileSystem.read(snapshot.data) { readByteArray() } }
    }
}
