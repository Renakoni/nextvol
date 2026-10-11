package indi.renakoni.nextvol.data.bangumi

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.InvalidationTracker
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import hnovel.network.StorageCipher
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import io.nightfish.lightnovelreader.api.book.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BangumiRepositoryTest {
    private lateinit var database: NextVolDatabase
    private lateinit var local: LocalBookDataSource
    private lateinit var accounts: BangumiAccountStore
    private lateinit var api: BangumiApi
    private lateinit var repository: BangumiRepository
    private val server = MockWebServer()
    private val book = BookIdentity.book("1213")
    @Volatile private var remote: BangumiCollection? = BangumiCollection(3, 0, 17, true)
    @Volatile private var nextFailure = 0
    @Volatile private var nextResponse: MockResponse? = null
    @Volatile private var failReadAfterWrite = false
    @Volatile private var readGate: Pair<CountDownLatch, CountDownLatch>? = null
    private val writes = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val queries = ConcurrentLinkedQueue<String>()
    private var volumeCount = 2
    private var ambiguousSearch = false
    private val volumes get() = (1..volumeCount).map { number -> Volume(BookIdentity.volumeKey(book, "$number"), "第${number}卷",
        listOf(ChapterInformation(chapter(number), "正文"))) }
    private fun chapter(number: Int) = SourceChapterId(book, "$number").storageKey

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> queries += sql }, Executor { it.run() }).build()
        local = LocalBookDataSource(database.bookInformationDao(), database.bookVolumesDao(), database.chapterContentDao(), database.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(database))
        accounts = BangumiAccountStore(context, StorageCipher.Plain)
        accounts.disconnect()
        accounts.connect(BangumiUser(17, "test"), "test-only-token")
        local.updateBookInformation(BookInformation(book.storageKey, "Novel", "", Uri.EMPTY, "Author", "", emptyList(), "Publisher", WordCount(0), LocalDateTime.MIN, true))
        local.updateBookVolumes(BookVolumes(book.storageKey, volumes))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path == "/v0/me") return json("""{"id":17,"username":"test"}""")
                if (path.startsWith("/v0/search/subjects")) return json(bangumiJson.encodeToString(BangumiSearchPage(
                    (if (ambiguousSearch) listOf(10, 20) else listOf(10)).map { BangumiSubject(it, name = "Novel") }, if (ambiguousSearch) 2 else 1)))
                if (path == "/v0/subjects/10" || path == "/v0/subjects/20") return json("""{"id":${path.substringAfterLast('/')},"type":1,"name":"Novel","platform":"小说","series":true,"volumes":$volumeCount,"infobox":[{"key":"作者","value":"Author"},{"key":"书系","value":"Publisher"}]}""")
                if (path == "/v0/subjects/10/subjects") return json(bangumiJson.encodeToString((1..volumeCount).map {
                    BangumiRelatedSubject(10 + it, 1, "Novel ($it)", relation = "单行本")
                }))
                if (path == "/v0/users/17/collections/10") {
                    readGate?.let { (started, release) -> started.countDown(); release.await(5, TimeUnit.SECONDS) }
                    nextResponse?.let { nextResponse = null; return it }
                    if (nextFailure != 0) return MockResponse().setResponseCode(nextFailure.also { nextFailure = 0 }).setHeader("Retry-After", "60")
                    return remote?.let { json(bangumiJson.encodeToString(it)) } ?: MockResponse().setResponseCode(404)
                }
                if (path == "/v0/users/-/collections/10") {
                    if (request.getHeader("Content-Type") != "application/json") return MockResponse().setResponseCode(415)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    writes += body
                    if (request.method == "POST") remote = BangumiCollection(3, private = body["private"]!!.jsonPrimitive.boolean)
                    else {
                        val value = remote ?: return MockResponse().setResponseCode(404)
                        remote = value.copy(type = body["type"]?.jsonPrimitive?.int ?: value.type,
                            volumes = body["vol_status"]?.jsonPrimitive?.int ?: value.volumes)
                        if (failReadAfterWrite && "vol_status" in body) {
                            failReadAfterWrite = false
                            nextFailure = 503
                        }
                    }
                    return MockResponse().setResponseCode(if (request.method == "POST") 202 else 204)
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        api = BangumiApi(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/"))
        repository = BangumiRepository(accounts, api, database)
    }

    @After fun close() = runBlocking { accounts.disconnect(); database.close(); server.shutdown() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun read(vararg numbers: Int) = local.updateUserReadingData(book.storageKey) {
        it.copy(maxChapterReadingProgressMap = numbers.associate { n -> chapter(n) to 1f })
    }
    private suspend fun bind() {
        val preview = repository.preview(book.storageKey, 10)
        repository.bind(preview, preview.mapping, emptySet(), true)
    }
    private suspend fun binding() = database.bangumiBindingDao().get(17, book.storageKey)!!.binding()

    @Test fun completedVolumesSyncOnceWithoutChangingChaptersOrReadingState() = runBlocking {
        read(1, 2); bind()
        assertFalse(repository.syncAll())
        assertEquals(BangumiCollection(3, 2, 17, true), remote)
        assertEquals(1, writes.size)
        assertEquals(setOf("vol_status"), writes.single().keys)
        assertFalse(repository.syncAll())
        assertEquals(1, writes.size)
        assertEquals(BangumiSyncStatus.SYNCED, binding().status)
        assertEquals(2, local.getUserReadingData(book.storageKey).maxChapterReadingProgressMap.size)
        val records = database.bangumiBindingDao().getRecords(17)
        assertEquals(1, records.size)
        assertEquals(2, records.single().remote)
        assertEquals(BangumiSyncStatus.SYNCED, records.single().status)
        database.bangumiBindingDao().delete(17, book.storageKey)
        assertEquals(records, database.bangumiBindingDao().getRecords(17))
        assertTrue(database.bangumiBindingDao().getRecords(18).isEmpty())
    }

    @Test fun positionOnlyUpdatesReuseCatalogsButCompletionAndResetStillChangeTheTarget() = runBlocking {
        bind(); repository.syncAll()
        local.updateUserReadingData(book.storageKey) { it.copyWithUpdatedChapterReadingProgress(chapter(1), 0.1f) }
        repository.reconcile(reuseCatalog = true)
        queries.clear()
        repository.reconcile()
        assertEquals(3, queries.count { it.contains("select * from volume", ignoreCase = true) ||
            it.contains("select * from chapter_information", ignoreCase = true) })
        val requests = server.requestCount
        repeat(20) { index ->
            local.updateUserReadingData(book.storageKey) {
                it.copyWithUpdatedChapterReadingProgress(chapter(1), 0.2f + index / 100f)
            }
            // Exclude the reader's own read-modify-write queries from this comparison.
            queries.clear()
            assertTrue(repository.reconcile(reuseCatalog = true).isEmpty())
            assertFalse(queries.any { it.contains("select * from volume", ignoreCase = true) ||
                it.contains("select * from chapter_information", ignoreCase = true) })
            assertFalse(queries.any { it.contains("select * from user_reading_data", ignoreCase = true) })
        }
        assertEquals(requests, server.requestCount)
        read(1)
        assertTrue(repository.reconcile(reuseCatalog = true).isNotEmpty())
        assertEquals(1, binding().target)
        database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
        assertTrue(repository.reconcile(reuseCatalog = true).isEmpty())
        assertEquals(0, binding().target)
        assertTrue(writes.isEmpty())
    }

    @Test fun catalogInvalidationAndExplicitReconcileCannotReuseAnOutdatedMapping() = runBlocking {
        bind(); repository.syncAll()
        read()
        repository.reconcile(reuseCatalog = true)
        val invalidated = CompletableDeferred<Unit>()
        val observer = object : InvalidationTracker.Observer("volume", "chapter_information") {
            override fun onInvalidated(tables: Set<String>) { invalidated.complete(Unit) }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            local.updateBookVolumes(BookVolumes(book.storageKey, volumes.map { it.copy(volumeTitle = "Renamed ${it.volumeTitle}") }))
            withTimeout(5_000) { invalidated.await() }
            assertTrue(repository.reconcile(reuseCatalog = true).isNotEmpty())
            assertEquals(BangumiSyncStatus.PENDING, binding().status)
            queries.clear()
            repository.reconcile()
            assertTrue(queries.any { it.contains("select * from volume", ignoreCase = true) })
        } finally { database.invalidationTracker.removeObserver(observer) }
    }

    @Test fun cachedReadingReconcileStillCreatesAndRemovesTheFirstAutomaticBinding() = runBlocking {
        assertTrue(repository.reconcile(reuseCatalog = true).isEmpty())
        local.updateUserReadingData(book.storageKey) { it.copyWithUpdatedChapterReadingProgress(chapter(1), 0.1f) }
        assertTrue(repository.reconcile(reuseCatalog = true).isNotEmpty())
        assertTrue(binding().automatic)
        database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
        assertTrue(repository.reconcile(reuseCatalog = true).isEmpty())
        assertNull(database.bangumiBindingDao().get(17, book.storageKey))
        assertEquals(0, server.requestCount)
    }

    @Test fun missingCollectionIsCreatedPrivatelyAndThenUpdated() = runBlocking {
        remote = null
        read(1); bind(); repository.syncAll()
        assertEquals(2, writes.size)
        assertEquals(setOf("type", "private"), writes[0].keys)
        assertEquals(BangumiCollection(3, 1, 0, true), remote)
    }

    @Test fun readingAutomaticallyEntersSyncButBrowsingDoesNot() = runBlocking {
        remote = null
        assertTrue(repository.reconcile().isEmpty())
        assertEquals(0, server.requestCount)
        read(1)
        assertEquals(1, repository.reconcile().size)
        assertNull(database.bangumiBindingDao().get(17, book.storageKey)!!.subjectId)
        repository.syncAll()
        assertEquals(10, database.bangumiBindingDao().get(17, book.storageKey)!!.subjectId)
        assertEquals(BangumiCollection(3, 1, 0, true), remote)
        assertEquals(BangumiSyncStatus.SYNCED, binding().status)
        val requests = server.requestCount
        repository.requestSync(); repository.syncAll()
        assertEquals(requests, server.requestCount)
    }

    @Test fun ambiguousMatchesPersistAsErrorsUntilGlobalSyncOrCorrection() = runBlocking {
        ambiguousSearch = true
        read(1); repository.requestSync(); repository.syncAll()
        assertEquals(BangumiSyncStatus.MATCH_REQUIRED, binding().status)
        assertEquals(BangumiSyncStatus.MATCH_REQUIRED, database.bangumiBindingDao().getRecords(17).single().status)
        assertTrue(writes.isEmpty())
        val requests = server.requestCount
        repository.reconcile(); repository.syncAll()
        assertEquals(requests, server.requestCount)
        ambiguousSearch = false
        repository.requestSync(); repository.syncAll()
        assertEquals(BangumiSyncStatus.SYNCED, binding().status)
        assertEquals(1, remote!!.volumes)
    }

    @Test fun automaticLinkUsesExistingRemoteBaselineAndKeepsRereadingIdempotent() = runBlocking {
        volumeCount = 16
        local.updateBookVolumes(BookVolumes(book.storageKey, volumes))
        remote = remote!!.copy(volumes = 4)
        read(1); repository.requestSync(); repository.syncAll()
        assertEquals(4, binding().baseline.size)
        assertTrue(writes.isEmpty())
        database.userReadingDataDao().clear()
        read(1, 2, 3, 4); repository.reconcile(); repository.syncAll()
        assertTrue(writes.isEmpty())
        read(5); repository.reconcile(); repository.syncAll()
        assertEquals(5, remote!!.volumes)
        assertEquals(1, writes.size)
    }

    @Test fun clearingReadingBeforeAutomaticMatchingDoesNotCreateACollection() = runBlocking {
        remote = null
        read(1); repository.reconcile()
        database.userReadingDataDao().clear()
        repository.syncAll()
        assertNull(database.bangumiBindingDao().get(17, book.storageKey))
        assertEquals(0, server.requestCount)
    }

    @Test fun aFinishedButUnconfirmedLatestCatalogAppearsAsAnActionableError() = runBlocking {
        val info = database.bookInformationDao().get(book.storageKey)!!
        local.updateBookInformation(info.copy(isComplete = false))
        read(1); repository.requestSync(); repository.syncAll()
        assertEquals(1, remote!!.volumes)
        read(2)
        assertTrue(repository.reconcile().isNotEmpty())
        repository.syncAll()
        assertEquals(BangumiSyncStatus.MAPPING_CHANGED, binding().status)
        assertEquals(1, writes.size)
        val preview = repository.preview(book.storageKey, 10)
        repository.bind(preview, preview.mapping.map { it.copy(complete = true) }, emptySet(), true)
        repository.syncAll()
        assertEquals(2, remote!!.volumes)
    }

    @Test fun anExcludedNumberedSpecialIsReviewedWhenReadAndCanBeExplicitlyExcluded() = runBlocking {
        val special = Volume(BookIdentity.volumeKey(book, "special"), "第1.5卷",
            listOf(ChapterInformation(chapter(3), "正文")))
        local.updateBookVolumes(BookVolumes(book.storageKey, volumes + special))
        read(1); repository.requestSync(); repository.syncAll()
        assertEquals(1, remote!!.volumes)
        read(3)
        assertTrue(repository.reconcile().isNotEmpty())
        repository.syncAll()
        assertEquals(BangumiSyncStatus.MAPPING_CHANGED, binding().status)
        assertEquals(1, writes.size)
        val preview = repository.preview(book.storageKey, 10)
        assertNull(preview.mapping.last().editionKey)
        repository.bind(preview, preview.mapping, emptySet(), true)
        repository.syncAll()
        assertEquals(BangumiSyncStatus.SYNCED, binding().status)
        assertEquals(1, writes.size)
    }

    @Test fun automaticLinkPreservesAnExistingNonReadingCollectionUntilCorrection() = runBlocking {
        remote = remote!!.copy(type = 2)
        read(1); repository.requestSync(); repository.syncAll()
        assertEquals(BangumiSyncStatus.REMOTE_STATE, binding().status)
        repository.requestSync(); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(2, remote!!.type)
        val preview = repository.preview(book.storageKey, 10)
        repository.bind(preview, preview.mapping, emptySet(), true)
        repository.syncAll()
        assertEquals(3, remote!!.type)
    }

    @Test fun aSingleBookSubjectCannotBeBoundToSeveralPublications() = runBlocking {
        val preview = repository.preview(book.storageKey, 10).let { it.copy(subject = it.subject.copy(series = false)) }
        try { repository.bind(preview, preview.mapping, emptySet(), true); fail() }
        catch (_: IllegalArgumentException) { }
        assertNull(database.bangumiBindingDao().get(17, book.storageKey))
        assertTrue(writes.isEmpty())
    }

    @Test fun alreadyCompletedCollectionIsOnlyResumedAfterBindingConfirmation() = runBlocking {
        remote = BangumiCollection(2, 0, 17)
        read(1)
        val preview = repository.preview(book.storageKey, 10)
        assertTrue(writes.isEmpty())
        repository.bind(preview, preview.mapping, emptySet(), true)
        repository.syncAll()
        assertEquals(setOf("type"), writes[0].keys)
        assertEquals(3, remote!!.type)
        assertEquals(17, remote!!.chapters)
    }

    @Test fun aHigherRemoteCountIsPreserved() = runBlocking {
        remote = BangumiCollection(3, 5)
        read(1); bind(); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(5, remote!!.volumes)
        assertEquals(BangumiSyncStatus.REMOTE_AHEAD, binding().status)
    }

    @Test fun fourPreviouslyReadRemoteVolumesAreNotAddedAgainWhenRereadLocally() = runBlocking {
        volumeCount = 16
        local.updateBookVolumes(BookVolumes(book.storageKey, volumes))
        remote = BangumiCollection(3, 4, 17, true)
        val preview = repository.preview(book.storageKey, 10)
        assertEquals(16, preview.subject.volumes)
        // The reader confirms that Bangumi's four previously read editions are volumes 1–4.
        val baseline = setOf("subject:11", "subject:12", "subject:13", "subject:14")
        repository.bind(preview, preview.mapping, baseline, true)
        repository.syncAll()
        assertTrue(writes.isEmpty())
        assertNull(database.userReadingDataDao().getEntity(book.storageKey))
        val requests = server.requestCount
        for (number in 1..4) {
            read(number)
            assertTrue(repository.reconcile().isEmpty())
            repository.syncAll()
            assertEquals(BangumiCollection(3, 4, 17, true), remote)
            assertEquals(requests, server.requestCount)
        }
        database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
        val fresh = BangumiRepository(accounts, api, database)
        read(1); fresh.syncAll()
        assertEquals(requests, server.requestCount)
        assertEquals(baseline, binding().baseline)
        read(5)
        assertTrue(fresh.reconcile().isNotEmpty())
        fresh.syncAll()
        assertEquals(BangumiCollection(3, 5, 17, true), remote)
        assertEquals(listOf(buildJsonObject { put("vol_status", 5) }), writes)
    }

    @Test fun unreadResetBeforeSendDropsThePendingTargetButNotAnAcknowledgedVolume() = runBlocking {
        bind(); repository.syncAll()
        read(1); assertTrue(repository.reconcile().isNotEmpty())
        read(); repository.syncAll()
        assertTrue(writes.isEmpty())
        read(1); repository.syncAll()
        read(); repository.syncAll()
        assertEquals(1, remote!!.volumes)
        assertEquals(1, writes.size)
    }

    @Test fun deletingReadingRecordsAndRereadingDoesNotResendAcknowledgedVolumes() = runBlocking {
        read(1); bind(); repository.syncAll()
        val acknowledged = binding().acknowledged
        val count = server.requestCount
        // The same DAO deletion used by BookManager's clear-reading-record action.
        database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
        assertNull(database.userReadingDataDao().getEntity(book.storageKey))
        assertTrue(repository.reconcile().isEmpty())
        read(1)
        val fresh = BangumiRepository(accounts, api, database)
        assertFalse(fresh.syncAll())
        assertEquals(count, server.requestCount)
        assertEquals(acknowledged, binding().acknowledged)
        database.userReadingDataDao().clear()
        read(2); fresh.syncAll()
        assertEquals(2, remote!!.volumes)
        assertEquals(listOf(1, 2), writes.map { it["vol_status"]!!.jsonPrimitive.int })
        assertEquals(2, binding().acknowledged.size)
    }

    @Test fun deletingUnsentReadingRecordsCancelsThePendingCount() = runBlocking {
        bind(); repository.syncAll()
        read(1); assertTrue(repository.reconcile().isNotEmpty())
        database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
        val requests = server.requestCount
        repository.syncAll()
        assertEquals(requests, server.requestCount)
        assertTrue(writes.isEmpty())
        assertEquals(0, binding().target)
        assertEquals(BangumiSyncStatus.READY, binding().status)
    }

    @Test fun deletingReadingRecordsDuringTheRemoteReadIsRecheckedBeforeWriting() = runBlocking {
        bind(); repository.syncAll(); read(1)
        val preview = repository.preview(book.storageKey, 10)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        readGate = started to release
        try {
            val syncing = async(Dispatchers.IO) { repository.syncAll() }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
            release.countDown()
            assertFalse(syncing.await())
            assertTrue(writes.isEmpty())
            assertEquals(0, binding().target)
        } finally { readGate = null; release.countDown() }
    }

    @Test fun trimmingHistoryDoesNotForgetAcknowledgedPublications() = runBlocking {
        read(1); bind(); repository.syncAll()
        val dao = database.bangumiBindingDao()
        val original = dao.getRecords(17).single()
        repeat(101) { dao.insertRecord(original.copy(id = 0, timestamp = it.toLong())) }
        dao.trimRecords(17)
        assertEquals(100, dao.getRecords(17).size)
        assertFalse(dao.getRecords(17).any { it.id == original.id })
        database.userReadingDataDao().clear()
        read(1); repository.syncAll()
        assertEquals(1, writes.size)
        assertEquals(setOf("subject:11"), binding().acknowledged)
    }

    @Test fun remoteRegressionAndStatusChangesPauseInsteadOfBeingOverwritten() = runBlocking {
        read(1); bind(); repository.syncAll()
        remote = remote!!.copy(volumes = 0)
        read(1, 2); repository.syncAll()
        assertEquals(BangumiSyncStatus.REMOTE_CHANGED, binding().status)
        assertEquals(1, writes.size)
        assertEquals(0, remote!!.volumes)
    }

    @Test fun manualWebsiteStatusIsPreservedAfterTheInitialLink() = runBlocking {
        bind(); repository.syncAll()
        remote = remote!!.copy(type = 2)
        read(1); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(BangumiSyncStatus.REMOTE_STATE, binding().status)
    }

    @Test fun addedChapterInvalidatesMappingBeforeAnyWrite() = runBlocking {
        read(1); bind()
        val changed = volumes.mapIndexed { index, volume -> if (index == 0) volume.copy(chapters = volume.chapters +
            ChapterInformation(SourceChapterId(book, "new").storageKey, "补章")) else volume }
        local.updateBookVolumes(BookVolumes(book.storageKey, changed))
        repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(BangumiSyncStatus.MAPPING_CHANGED, binding().status)
    }

    @Test fun freshWorkerRecoversProgressWithoutAnInMemoryReadingEvent() = runBlocking {
        bind(); repository.syncAll()
        read(1)
        val restoredAccounts = BangumiAccountStore(RuntimeEnvironment.getApplication(), StorageCipher.Plain)
        val restoredRepository = BangumiRepository(restoredAccounts, api, database)
        restoredRepository.syncAll()
        assertEquals(1, remote!!.volumes)
        restoredAccounts.disconnect()
    }

    @Test fun authenticationFailurePausesAndRateLimitRetainsBackoff() = runBlocking {
        read(1); bind()
        nextFailure = 429
        assertTrue(repository.syncAll())
        val count = server.requestCount
        assertTrue(repository.syncAll())
        assertEquals(count, server.requestCount)
        repository.requestSync()
        assertTrue(repository.syncAll())
        assertEquals(count, server.requestCount)
        repository.retry(book.storageKey)
        nextFailure = 401
        assertFalse(repository.syncAll())
        assertEquals(BangumiSyncStatus.AUTH_REQUIRED, binding().status)
        assertTrue(writes.isEmpty())
        assertEquals(listOf(BangumiSyncStatus.AUTH_REQUIRED, BangumiSyncStatus.OFFLINE),
            database.bangumiBindingDao().getRecords(17).map { it.status })
        assertEquals(listOf(401, 429), database.bangumiBindingDao().getRecords(17).map { it.httpStatus })
        val unauthorizedRequests = server.requestCount
        repository.requestSync()
        assertFalse(repository.syncAll())
        assertEquals(unauthorizedRequests, server.requestCount)
        assertEquals(BangumiSyncStatus.AUTH_REQUIRED, binding().status)
    }

    @Test fun rejectedOrMissingCollectionsPauseWithTheHttpCodeAndNoRepeatedRequests() = runBlocking {
        for (code in listOf(400, 403, 404, 415)) {
            bind(); repository.syncAll(); read(1)
            nextFailure = code
            assertFalse(repository.syncAll())
            val record = database.bangumiBindingDao().getRecords(17).first()
            assertEquals(code, record.httpStatus)
            assertEquals(if (code == 404) BangumiSyncStatus.REMOTE_MISSING else BangumiSyncStatus.REQUEST_REJECTED, record.status)
            val requests = server.requestCount
            repository.syncAll()
            assertEquals(requests, server.requestCount)
            assertTrue(writes.isEmpty())
            read()
        }
    }

    @Test fun interruptedAndMalformedResponsesHaveNoInventedHttpFailureCode() = runBlocking {
        bind(); repository.syncAll(); read(1)
        nextResponse = json("private response that must not be retained").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        assertTrue(repository.syncAll())
        var record = database.bangumiBindingDao().getRecords(17).first()
        assertEquals(BangumiSyncStatus.OFFLINE, record.status)
        assertNull(record.httpStatus)
        repository.requestSync()
        nextResponse = json("{\"private-response\":")
        assertFalse(repository.syncAll())
        record = database.bangumiBindingDao().getRecords(17).first()
        assertEquals(BangumiSyncStatus.REQUEST_REJECTED, record.status)
        assertNull(record.httpStatus)
        assertFalse(record.toString().contains("private-response"))
        assertTrue(writes.isEmpty())
    }

    @Test fun disconnectPreventsLaterQueuedWritesWithoutDeletingRemoteCollection() = runBlocking {
        read(1); bind(); accounts.disconnect(); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertNotNull(remote)
    }

    @Test fun explicitRetryChecksRemoteEvenWhenLocalCountDidNotChange() = runBlocking {
        bind(); repository.syncAll()
        repository.retry(book.storageKey)
        assertTrue(repository.reconcile().isNotEmpty())
        remote = remote!!.copy(type = 4)
        repository.syncAll()
        assertEquals(BangumiSyncStatus.REMOTE_STATE, binding().status)
    }

    @Test fun acceptedWriteSurvivesFailedReadbackUnreadResetAndWorkerRecreation() = runBlocking {
        bind(); repository.syncAll(); read(1)
        failReadAfterWrite = true
        assertTrue(repository.syncAll())
        assertEquals(1, remote!!.volumes)
        assertEquals(setOf("subject:11"), binding().pendingEditions)
        val failure = database.bangumiBindingDao().getRecords(17).first()
        assertEquals(503, failure.httpStatus)
        assertTrue(failure.pendingConfirmation)
        database.userReadingDataDao().deleteByIds(listOf(book.storageKey))
        assertTrue(repository.reconcile().isNotEmpty())
        repository.retry(book.storageKey)
        val fresh = BangumiRepository(accounts, api, database)
        assertFalse(fresh.syncAll())
        assertEquals(setOf("subject:11"), binding().acknowledged)
        assertTrue(binding().pendingEditions.isEmpty())
        read(2); fresh.syncAll()
        assertEquals(2, remote!!.volumes)
        assertEquals(2, writes.size)
    }

    @Test fun reviewingAnAmbiguousWriteRetainsItsPublicationIdentity() = runBlocking {
        bind(); repository.syncAll(); read(1)
        failReadAfterWrite = true
        assertTrue(repository.syncAll())
        database.userReadingDataDao().clear()
        bind()
        repository.syncAll()
        assertEquals(setOf("subject:11"), binding().acknowledged)
        read(2); repository.syncAll()
        assertEquals(2, remote!!.volumes)
        assertEquals(2, writes.size)
    }

    @Test fun correctingAMatchCancelsTheInflightReadBeforeItCanPatch() = runBlocking {
        bind(); repository.syncAll(); read(1)
        val preview = repository.preview(book.storageKey, 10)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        readGate = started to release
        try {
            val syncing = async(Dispatchers.IO) { repository.syncAll() }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(3000) { repository.bind(preview, preview.mapping, emptySet(), true) }
            release.countDown()
            syncing.await()
            assertEquals(BangumiSyncStatus.PENDING, binding().status)
            assertTrue(writes.isEmpty())
        } finally { readGate = null; release.countDown() }
    }

    @Test fun reconnectAfterDisconnectRequiresReviewAndAnotherAccountCannotUseOldBindings() = runBlocking {
        bind(); repository.syncAll(); read(1)
        accounts.connect(BangumiUser(18, "other"), "another-test-token")
        repository.syncAll()
        assertTrue(writes.isEmpty())
        accounts.disconnect()
        repository.connect("replacement-test-token")
        assertEquals(BangumiSyncStatus.REMOTE_CHANGED, binding().status)
        repository.syncAll()
        assertTrue(writes.isEmpty())
    }

    @Test fun globalSyncCannotBypassBindingReviewAfterReconnect() = runBlocking {
        bind(); repository.syncAll(); read(1)
        accounts.disconnect()
        repository.connect("replacement-test-token")
        assertEquals(BangumiSyncStatus.REMOTE_CHANGED, binding().status)
        val requests = server.requestCount
        repository.requestSync(); repository.syncAll()
        assertEquals(BangumiSyncStatus.REMOTE_CHANGED, binding().status)
        assertEquals(requests, server.requestCount)
        assertTrue(writes.isEmpty())
        val preview = repository.preview(book.storageKey, 10)
        repository.bind(preview, preview.mapping, emptySet(), true)
        repository.syncAll()
        assertEquals(1, remote!!.volumes)
        assertEquals(BangumiSyncStatus.SYNCED, binding().status)
    }

    @Test fun roomCommitAutomaticallySchedulesWorkAndFailureDoesNotEnqueueAFeedbackLoop() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == BangumiSyncWork::class.java.name) BangumiSyncWork(appContext, workerParameters, repository) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            bind()
            BangumiSyncScheduler(repository, database, manager).start(scope)
            val first = withTimeout(10_000) { manager.getWorkInfosForUniqueWorkFlow("bangumi-progress").first { it.isNotEmpty() }.single() }
            val beforeDelay = server.requestCount
            driver.setAllConstraintsMet(first.id)
            delay(100)
            assertEquals(beforeDelay, server.requestCount)
            driver.setInitialDelayMet(first.id)
            withTimeout(10_000) { manager.getWorkInfoByIdFlow(first.id).first { it?.state?.isFinished == true } }
            assertTrue(writes.isEmpty())
            nextFailure = 429
            read(1)
            val second = withTimeout(10_000) { manager.getWorkInfosForUniqueWorkFlow("bangumi-progress").first { list -> list.any { it.id != first.id } }.first { it.id != first.id } }
            driver.setAllConstraintsMet(second.id)
            driver.setInitialDelayMet(second.id)
            withTimeout(10_000) { manager.getWorkInfoByIdFlow(second.id).first { it?.runAttemptCount == 1 && it.state == WorkInfo.State.ENQUEUED } }
            val count = server.requestCount
            // Cover two sampling ticks after the worker's own database updates.
            delay(1700)
            assertEquals(2, manager.getWorkInfosForUniqueWork("bangumi-progress").get().size)
            assertEquals(count, server.requestCount)
            assertEquals(BangumiSyncStatus.OFFLINE, binding().status)
            assertTrue(writes.isEmpty())
            accounts.disconnect()
            withTimeout(10_000) {
                manager.getWorkInfosForUniqueWorkFlow("bangumi-progress").first { list -> list.all { it.state.isFinished } }
                manager.getWorkInfosForUniqueWorkFlow("bangumi-recovery").first { list -> list.all { it.state.isFinished } }
            }
            assertTrue(writes.isEmpty())
        } finally {
            scope.cancel()
            manager.cancelAllWork().await()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
