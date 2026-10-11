package indi.renakoni.nextvol.data.bangumi

import androidx.room.withTransaction
import androidx.room.InvalidationTracker
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BangumiRepository @Inject constructor(
    val accounts: BangumiAccountStore,
    private val api: BangumiApi,
    private val database: NextVolDatabase,
) {
    private val dao get() = database.bangumiBindingDao()
    val bindings = dao.observeAll()
    val records = dao.observeRecords()
    private val mutex = Mutex()
    @Volatile private var activeSync: Pair<String, Job>? = null
    private val catalogRevision = AtomicLong()
    private data class ReconciledCatalog(
        val entity: BangumiBindingEntity,
        val generation: String,
        val catalogRevision: Long,
        val completedChapters: Set<String>,
        val editions: Set<String>?,
    )
    private val reconciled = mutableMapOf<String, ReconciledCatalog>()

    init {
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            "book_information", "volume", "chapter_information",
        ) {
            override fun onInvalidated(tables: Set<String>) { catalogRevision.incrementAndGet() }
        })
    }

    suspend fun refreshProfile() {
        val session = accounts.session() ?: return
        accounts.updateProfile(session, api.me(session))
    }

    suspend fun connect(token: String) {
        val user = api.me(token)
        activeSync?.second?.cancel()
        mutex.withLock {
            val previousAccount = accounts.session()?.user?.id
            val session = accounts.connect(user, token)
            for (entity in dao.getAll(user.id)) {
                session.checkActive()
                val value = entity.binding()
                if (previousAccount != user.id && entity.subjectId != null) {
                    dao.save(entity.withBinding(value.copy(status = BangumiSyncStatus.REMOTE_CHANGED)))
                } else if (value.status == BangumiSyncStatus.AUTH_REQUIRED) {
                    dao.save(entity.withBinding(value.copy(status = BangumiSyncStatus.PENDING, retryAt = 0)))
                }
            }
        }
    }

    suspend fun search(bookId: String, query: String, offset: Int): Pair<List<BangumiCandidate>, Boolean> {
        val book = localBook(bookId)
        val directId = BangumiApi.subjectId(query)
        if (query.contains("://") && directId == null) throw BangumiLinkException()
        val page = if (directId != null) BangumiSearchPage(listOf(api.subject(directId)), 1) else api.search(query, offset)
        val candidates = mutableListOf<BangumiCandidate>()
        for (result in page.data) {
            if (result.type != 1) continue
            val subject = api.subject(result.id)
            if (!subject.isNovel && subject.platform.isNotBlank()) continue
            val people = if (subject.values("作者").isEmpty() || subject.values("书系").isEmpty()) api.people(subject.id) else emptyList()
            candidates += BangumiMatching.candidate(book, subject, people)
        }
        return candidates.sortedWith(compareByDescending<BangumiCandidate> { it.subject.isNovel && it.subject.series }
            .thenBy { it.authorConflicts }.thenByDescending { it.titleMatches && it.authorMatches }
            .thenByDescending { it.titleMatches }.thenByDescending { it.publisherMatches }) to
            (directId == null && offset + page.data.size < page.total && page.data.isNotEmpty())
    }

    suspend fun localBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = BookIdentity.book(bookId)
        require(BangumiMatching.supports(book))
        requireNotNull(database.bookInformationDao().get(book.storageKey))
    }

    suspend fun preview(bookId: String, subjectId: Int): BangumiBookPreview {
        val session = requireNotNull(accounts.session())
        val book = localBook(bookId)
        val volumes = requireNotNull(database.bookVolumesDao().getBookVolumes(book.id)).volumes
        require(volumes.isNotEmpty() && volumes.all { it.volumeId.isNotBlank() } && volumes.map { it.volumeId }.distinct().size == volumes.size)
        require(volumes.all { volume -> volume.chapters.all { it.id.isNotBlank() } })
        val subject = api.subject(subjectId)
        require(subject.type == 1 && (subject.isNovel || subject.platform.isBlank()))
        val related = api.related(subjectId)
        val remote = api.collection(session, subjectId)
        session.checkActive()
        val previous = dao.get(session.user.id, book.id)?.takeIf { it.subjectId == subjectId }?.binding()
        val proposed = BangumiMatching.propose(book, volumes, subject, related).map { row ->
            previous?.mapping?.find { it.volumeId == row.volumeId }?.let { old ->
                row.copy(editionKey = old.editionKey, complete = old.complete && old.chapterIds == row.chapterIds)
            } ?: row
        }
        return BangumiBookPreview(book, subject, proposed, remote, session.user.id, session.generation)
    }

    suspend fun bind(preview: BangumiBookPreview, mapping: List<BangumiVolumeMapping>, baseline: Set<String>, private: Boolean) {
        interrupt(preview.book.id)
        mutex.withLock { saveBinding(preview, mapping, baseline, private) }
    }

    private suspend fun saveBinding(preview: BangumiBookPreview, mapping: List<BangumiVolumeMapping>, baseline: Set<String>, private: Boolean,
        automatic: Boolean = false) {
        val session = requireNotNull(accounts.session())
        require(session.user.id == preview.accountId && session.generation == preview.accountGeneration)
        require(BangumiMatching.supports(BookIdentity.book(preview.book.id)))
        val volumes = requireNotNull(database.bookVolumesDao().getBookVolumes(preview.book.id)).volumes
        require(BangumiMatching.catalogMatches(mapping, volumes))
        require(mapping.any { it.editionKey != null })
        val keys = mapping.mapNotNull { it.editionKey }.toSet()
        require(preview.subject.series || keys.size == 1)
        require(baseline.all { it in keys } && baseline.size <= (preview.remote?.volumes ?: 0))
        require(dao.getAll(session.user.id).none { it.subjectId == preview.subject.id && it.bookId != preview.book.id })
        val old = dao.get(session.user.id, preview.book.id)?.takeIf { it.subjectId == preview.subject.id }?.binding()
        // A remap cannot silently forget editions this client already published.
        require((old?.acknowledged.orEmpty() + old?.pendingEditions.orEmpty()).all { it in keys || it in baseline })
        val value = BangumiBinding(preview.book.title, preview.subject.title, UUID.randomUUID().toString(), mapping,
            baseline, old?.acknowledged.orEmpty(), pendingEditions = old?.pendingEditions.orEmpty(), remote = preview.remote?.volumes ?: 0,
            privateCollection = preview.remote?.private ?: private, automatic = automatic)
        session.checkActive()
        dao.save(BangumiBindingEntity(session.user.id, preview.book.id, preview.subject.id, bangumiJson.encodeToString(value)))
    }

    private fun interrupt(bookId: String) { activeSync?.takeIf { it.first == bookId }?.second?.cancel() }

    /** Runs after commits and at startup, without HTTP. Only the continuous observer reuses
     * unchanged catalogs; manual/periodic recovery and the send path always recheck them. */
    suspend fun reconcile(reuseCatalog: Boolean = false): Set<String> = mutex.withLock {
        val session = accounts.session() ?: return@withLock emptySet()
        val reading = dao.getReadingInputs().associateBy { it.id }
        val bindingsByBook = dao.getAll(session.user.id).associateBy { it.bookId }
        val revision = catalogRevision.get()
        val pending = mutableSetOf<String>()
        for (book in dao.getReadingBooks()) {
            session.checkActive()
            if (BangumiMatching.supports(BookIdentity.book(book.id)) && reading[book.id]?.hasReading == true && book.id !in bindingsByBook) {
                val value = BangumiBinding(book.title, "", UUID.randomUUID().toString(), emptyList(), automatic = true)
                dao.save(BangumiBindingEntity(session.user.id, book.id, null, bangumiJson.encodeToString(value)))
            }
        }
        val entities = dao.getAll(session.user.id)
        reconciled.keys.retainAll(entities.map { it.bookId }.toSet())
        for (entity in entities) {
            session.checkActive()
            val old = entity.binding()
            if (entity.subjectId == null && reading[entity.bookId]?.hasReading != true) {
                dao.delete(entity.accountId, entity.bookId)
                reconciled.remove(entity.bookId)
                continue
            }
            if (old.status in pausedStatuses) continue
            val completed = reading[entity.bookId]?.completed.orEmpty()
            val previous = reconciled[entity.bookId]
            val local = if (reuseCatalog && previous != null && previous.entity == entity &&
                previous.generation == session.generation && previous.catalogRevision == revision &&
                previous.completedChapters == completed) previous.editions else {
                snapshot(entity, old, completed.associateWith { 1f }).also {
                    reconciled[entity.bookId] = ReconciledCatalog(entity, session.generation, revision, completed, it)
                }
            }
            val status = if (local == null) BangumiSyncStatus.PENDING
                else if (old.initializeCollection || old.forceSync || old.pendingEditions.isNotEmpty() || local.size > old.remote) BangumiSyncStatus.PENDING
                else if (old.remote > local.size) BangumiSyncStatus.REMOTE_AHEAD
                else if (local.isEmpty()) BangumiSyncStatus.READY else BangumiSyncStatus.SYNCED
            val value = old.copy(target = local?.size ?: old.target,
                status = if (status == BangumiSyncStatus.PENDING && old.status == BangumiSyncStatus.OFFLINE && old.target == (local?.size ?: old.target))
                    BangumiSyncStatus.OFFLINE else status)
            if (value != old) {
                val updated = entity.withBinding(value)
                dao.save(updated)
                reconciled[entity.bookId]?.let { reconciled[entity.bookId] = it.copy(entity = updated) }
            }
            if (status == BangumiSyncStatus.PENDING) pending += "${session.generation}:${entity.bookId}:${value.revision}:${value.target}"
        }
        pending
    }

    suspend fun retry(bookId: String) = mutex.withLock {
        val session = accounts.session() ?: return@withLock
        val entity = dao.get(session.user.id, bookId) ?: return@withLock
        val value = entity.binding()
        // Remote-state/identity conflicts require a fresh preview and explicit association confirmation.
        if (value.status !in setOf(BangumiSyncStatus.READY, BangumiSyncStatus.OFFLINE, BangumiSyncStatus.REQUEST_REJECTED,
                BangumiSyncStatus.PENDING, BangumiSyncStatus.SYNCED, BangumiSyncStatus.REMOTE_AHEAD)) return@withLock
        dao.save(entity.withBinding(value.copy(status = BangumiSyncStatus.PENDING, retryAt = 0,
            forceSync = true, revision = UUID.randomUUID().toString())))
    }

    suspend fun requestSync() {
        reconcile()
        mutex.withLock {
            val session = accounts.session() ?: return@withLock
            for (entity in dao.getAll(session.user.id)) {
                session.checkActive()
                val value = entity.binding()
                // A global retry cannot replace token validation or binding confirmation.
                if (!value.status.successful && value.status !in confirmationRequiredStatuses) {
                    dao.save(entity.withBinding(value.copy(status = BangumiSyncStatus.PENDING,
                        forceSync = true, revision = UUID.randomUUID().toString())))
                }
            }
        }
    }

    /** Returns true only when a transport/rate-limit failure needs WorkManager backoff. */
    suspend fun syncAll(): Boolean {
        val session = accounts.session() ?: return false
        var retry = false
        for (entity in dao.getAll(session.user.id)) {
            session.checkActive()
            try { coroutineScope {
                mutex.withLock {
                    activeSync = entity.bookId to currentCoroutineContext().job
                    try { if (sync(entity, session)) retry = true }
                    finally { activeSync = null }
                }
            } } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                session.checkActive()
            }
        }
        return retry
    }

    private suspend fun sync(initial: BangumiBindingEntity, session: BangumiSession): Boolean {
        var entity = dao.get(initial.accountId, initial.bookId) ?: return false
        var value = entity.binding()
        if (value.status in pausedStatuses) return false
        if (value.retryAt > System.currentTimeMillis()) return true
        suspend fun save(next: BangumiBinding) {
            session.checkActive()
            value = next
            entity = entity.withBinding(next)
            dao.save(entity)
        }
        suspend fun finish(next: BangumiBinding, httpStatus: Int? = null) = database.withTransaction {
            save(next)
            dao.insertRecord(BangumiSyncRecord(accountId = entity.accountId, bookId = entity.bookId,
                bookTitle = next.bookTitle, target = next.target, remote = next.remote,
                status = next.status, timestamp = System.currentTimeMillis(), httpStatus = httpStatus,
                pendingConfirmation = next.pendingEditions.isNotEmpty()))
            dao.trimRecords(entity.accountId)
        }
        try {
            if (value.automatic && value.initializeCollection && !hasReading(entity.bookId)) {
                dao.delete(entity.accountId, entity.bookId)
                return false
            }
            if (entity.subjectId == null) {
                val book = localBook(entity.bookId)
                val (candidates, more) = search(entity.bookId, book.title, 0)
                val subject = BangumiMatching.automaticSubject(candidates, more)
                if (subject == null || dao.getAll(session.user.id).any { it.subjectId == subject.id && it.bookId != entity.bookId }) {
                    finish(value.copy(status = BangumiSyncStatus.MATCH_REQUIRED)); return false
                }
                val preview = preview(entity.bookId, subject.id)
                val baseline = BangumiMatching.automaticBaseline(preview)
                if (baseline == null || preview.mapping.none { it.editionKey != null }) {
                    finish(value.copy(status = BangumiSyncStatus.MAPPING_CHANGED)); return false
                }
                saveBinding(preview, preview.mapping, baseline, true, automatic = true)
                entity = requireNotNull(dao.get(entity.accountId, entity.bookId))
                value = entity.binding()
                if (preview.remote != null && preview.remote.type != 3) {
                    finish(value.copy(initializeCollection = false, status = BangumiSyncStatus.REMOTE_STATE)); return false
                }
            }
            val subjectId = requireNotNull(entity.subjectId)
            if (snapshot(entity, value) == null) {
                val volumes = database.bookVolumesDao().getBookVolumes(entity.bookId)?.volumes.orEmpty()
                var refreshed = BangumiMatching.refresh(value.mapping, volumes)
                if (refreshed == null && volumes.any { volume -> value.mapping.none { it.volumeId == volume.volumeId } }) {
                    val subject = api.subject(subjectId)
                    if (subject.isNovel && subject.series) refreshed = BangumiMatching.refresh(value.mapping, volumes, api.related(subjectId))
                }
                if (refreshed == null) { finish(value.copy(status = BangumiSyncStatus.MAPPING_CHANGED)); return false }
                save(value.copy(mapping = refreshed))
            }
            val knownCompleted = snapshot(entity, value)
            val progress = database.userReadingDataDao().getEntity(entity.bookId)?.maxChapterReadingProgressMap.orEmpty()
            if (needsMappingReview(value, progress)) {
                finish(value.copy(status = BangumiSyncStatus.MAPPING_CHANGED)); return false
            }
            if (knownCompleted != null && knownCompleted.size <= value.remote && !value.initializeCollection &&
                !value.forceSync && value.pendingEditions.isEmpty()) {
                val next = value.copy(target = knownCompleted.size, retryAt = 0,
                    status = if (value.remote > knownCompleted.size) BangumiSyncStatus.REMOTE_AHEAD
                        else if (knownCompleted.isEmpty()) BangumiSyncStatus.READY else BangumiSyncStatus.SYNCED)
                if (value != next) save(next)
                return false
            }
            if (knownCompleted != null && knownCompleted.size != value.target) save(value.copy(target = knownCompleted.size))
            var remote = api.collection(session, subjectId)
            if (value.initializeCollection) {
                // Any unexpected progress change since the confirmation must be reviewed first.
                if ((remote?.volumes ?: 0) != value.remote) {
                    finish(value.copy(status = BangumiSyncStatus.REMOTE_CHANGED)); return false
                }
                if (value.automatic && !hasReading(entity.bookId)) {
                    dao.delete(entity.accountId, entity.bookId); return false
                }
                if (remote == null) api.createCollection(session, subjectId, value.privateCollection)
                else if (remote.type != 3) api.resumeCollection(session, subjectId)
                remote = api.collection(session, subjectId)
                save(value.copy(initializeCollection = false))
            }
            if (remote == null) { finish(value.copy(status = BangumiSyncStatus.REMOTE_MISSING), 404); return false }
            if (remote.type != 3) { finish(value.copy(status = BangumiSyncStatus.REMOTE_STATE)); return false }
            if (remote.volumes < value.remote) { finish(value.copy(status = BangumiSyncStatus.REMOTE_CHANGED)); return false }
            if (remote.volumes != value.remote) save(value.copy(remote = remote.volumes))
            if (value.pendingEditions.isNotEmpty() && remote.volumes >= value.pendingEditions.size) {
                save(value.copy(acknowledged = value.acknowledged + value.pendingEditions, pendingEditions = emptySet()))
            }
            // Re-read committed progress immediately before a write, including any unread reset.
            val completed = snapshot(entity, value)
            if (completed == null) { finish(value.copy(status = BangumiSyncStatus.MAPPING_CHANGED)); return false }
            val target = completed.size
            if (target > remote.volumes) {
                session.checkActive()
                // Persist publication identities before sending: a timeout may follow an accepted PATCH.
                save(value.copy(target = target, pendingEditions = completed))
                api.updateVolumes(session, subjectId, target)
                remote = api.collection(session, subjectId)
                if (remote == null || remote.type != 3 || remote.volumes < target) {
                    finish(value.copy(status = BangumiSyncStatus.REMOTE_CHANGED), if (remote == null) 404 else null); return false
                }
            }
            finish(value.copy(acknowledged = completed, pendingEditions = emptySet(), target = target, remote = remote.volumes,
                lastSyncedAt = System.currentTimeMillis(), retryAt = 0, forceSync = false,
                status = if (remote.volumes > target) BangumiSyncStatus.REMOTE_AHEAD
                    else if (target == 0) BangumiSyncStatus.READY else BangumiSyncStatus.SYNCED))
            return false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BangumiApiException) {
            val retry = failure.status == 429 || failure.status >= 500
            finish(value.copy(status = when {
                failure.status == 401 -> BangumiSyncStatus.AUTH_REQUIRED
                retry -> BangumiSyncStatus.OFFLINE
                else -> BangumiSyncStatus.REQUEST_REJECTED
            }, retryAt = if (retry) System.currentTimeMillis() + failure.retryAfterSeconds.coerceAtMost(86400) * 1000 else 0), failure.status)
            return retry
        } catch (_: BangumiResponseException) {
            finish(value.copy(status = BangumiSyncStatus.REQUEST_REJECTED))
            return false
        } catch (_: IOException) {
            finish(value.copy(status = BangumiSyncStatus.OFFLINE)); return true
        } catch (_: IllegalArgumentException) {
            finish(value.copy(status = BangumiSyncStatus.MAPPING_CHANGED)); return false
        }
    }

    private suspend fun snapshot(entity: BangumiBindingEntity, value: BangumiBinding,
        knownProgress: Map<String, Float>? = null): Set<String>? = database.withTransaction {
        if (!BangumiMatching.supports(BookIdentity.book(entity.bookId))) return@withTransaction null
        val volumes = database.bookVolumesDao().getBookVolumes(entity.bookId)?.volumes ?: return@withTransaction null
        if (!BangumiMatching.catalogMatches(value.mapping, volumes)) return@withTransaction null
        val progress = knownProgress ?: database.userReadingDataDao().getEntity(entity.bookId)?.maxChapterReadingProgressMap.orEmpty()
        if (needsMappingReview(value, progress)) return@withTransaction null
        value.baseline + value.acknowledged + BangumiMatching.completed(value.mapping, progress)
    }

    private fun needsMappingReview(value: BangumiBinding, progress: Map<String, Float>): Boolean {
        val awaitingCatalog = BangumiMatching.completed(value.mapping.map { it.copy(complete = true) }, progress) -
            value.baseline - value.acknowledged - BangumiMatching.completed(value.mapping, progress)
        if (awaitingCatalog.isNotEmpty()) return true
        // An automatically excluded numbered special needs review once read; an explicit exclusion does not.
        return value.automatic && value.mapping.any { row ->
            row.editionKey == null && BangumiMatching.localNumber(row.title) != null && row.chapterIds.isNotEmpty() &&
                row.chapterIds.all { id -> progress[id]?.let { it.isFinite() && it >= 1f } == true }
        }
    }

    private suspend fun hasReading(bookId: String): Boolean {
        val reading = database.userReadingDataDao().getEntity(bookId) ?: return false
        return reading.lastReadChapterId.isNotBlank() || reading.totalReadTime > 0 ||
            reading.maxChapterReadingProgressMap.values.any { it.isFinite() && it > 0f }
    }

    companion object {
        private val confirmationRequiredStatuses = setOf(BangumiSyncStatus.AUTH_REQUIRED, BangumiSyncStatus.REMOTE_CHANGED,
            BangumiSyncStatus.MAPPING_CHANGED, BangumiSyncStatus.REMOTE_STATE, BangumiSyncStatus.REMOTE_MISSING)
        private val pausedStatuses = confirmationRequiredStatuses + setOf(BangumiSyncStatus.REQUEST_REJECTED, BangumiSyncStatus.MATCH_REQUIRED)
    }
}
