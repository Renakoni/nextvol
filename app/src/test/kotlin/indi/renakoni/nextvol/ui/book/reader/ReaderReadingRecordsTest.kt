package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import indi.renakoni.nextvol.data.reading.ReaderRecordStore
import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderReadingRecordsTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val statisticsScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = RecordingStore()
    private var bookId = "book"
    private var title: String? = "Chapter title"
    private var chapters: Set<String>? = setOf("chapter", "other")
    private var time = LocalDateTime.of(2026, 9, 7, 12, 0)
    private var clockReads = 0
    private val records = ReaderReadingRecords(
        store = store,
        scope = scope,
        statisticsScope = statisticsScope,
        currentBookId = { bookId },
        currentChapterTitle = { title },
        catalogChapterIds = { chapters },
        now = { clockReads++; time },
        ioDispatcher = dispatcher,
    )

    @Test
    fun lateProgressKeepsTheOriginalSessionRevisionAndDoesNotMarkARejectedSaveFinished() {
        store.revision = 1L
        store.rejectProgress = true
        records.saveProgress("chapter", 1f)
        scheduler.runCurrent()
        assertEquals(listOf(0L), store.progressRevisions)
        assertTrue(store.writes.isEmpty())
        assertFalse(store.events.any { it.startsWith("finished:") })
    }

    @Test
    fun delayedProgressKeepsChapterHistoryWithoutReplacingTheNewResumeTarget() {
        var current = true
        val gate = CompletableDeferred<Unit>()
        store.updateGate = gate
        records.saveProgress("old", 0.6f) { current }
        scheduler.runCurrent()
        current = false
        store.data["book"] = UserReadingData("book", lastReadTime = time.plusSeconds(1),
            lastReadChapterId = "new", lastReadChapterTitle = "New chapter")
        gate.complete(Unit)
        scheduler.runCurrent()
        val saved = store.data.getValue("book")
        assertEquals("new", saved.lastReadChapterId)
        assertEquals("New chapter", saved.lastReadChapterTitle)
        assertEquals(time.plusSeconds(1), saved.lastReadTime)
        assertEquals(0.6f, saved.currentChapterReadingProgressMap["old"])
    }

    @After
    fun tearDown() {
        scope.cancel()
        statisticsScope.cancel()
        scheduler.runCurrent()
    }

    @Test
    fun everyBookEntryMovesTheBookToTheEndAndRecordsOneReadEvent() {
        store.recentBooks = listOf("first", "book", "last")
        records.openBook("book")
        records.openBook("book")
        assertTrue(store.events.isEmpty())
        scheduler.runCurrent()

        assertEquals(listOf("first", "last", "book"), store.recentBooks)
        assertEquals(
            listOf("recent:start", "recent:write", "stats:book", "recent:start", "recent:write", "stats:book"),
            store.events,
        )
        assertEquals(listOf("book", "book"), store.statistics.map { it.bookId })
        assertEquals(listOf(1, 1), store.statistics.map { it.readEventDelta })
        assertEquals(listOf(0, 0), store.statistics.map { it.secondDelta })
    }

    @Test
    fun entryStatisticsDoNotWaitForTheRecentBooksWrite() {
        val gate = CompletableDeferred<Unit>()
        store.recentGate = gate
        records.openBook("book")
        scheduler.runCurrent()
        assertEquals(listOf("recent:start", "stats:book"), store.events)

        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(listOf("recent:start", "stats:book", "recent:write"), store.events)
        assertEquals(listOf("book"), store.recentBooks)
    }

    @Test
    fun invalidProgressBlankBookAndMissingTitleProduceNoPersistenceOrClockReads() {
        listOf(Float.NaN, 0f, -0.1f, Float.NEGATIVE_INFINITY).forEach {
            records.saveProgress("chapter", it)
        }
        bookId = "  "
        records.saveProgress("chapter", 0.5f)
        bookId = "book"
        title = null
        records.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()

        assertTrue(store.events.isEmpty())
        assertEquals(0, clockReads)
    }

    @Test
    fun progressUpdatesChapterMapsAndMetadataUsingUpdatedMaximaForTheTotal() {
        store.data["book"] = UserReadingData(
            id = "book",
            totalReadTime = 12,
            currentChapterReadingProgressMap = mapOf("chapter" to 0.25f, "other" to 1f),
            maxChapterReadingProgressMap = mapOf("chapter" to 0.25f, "other" to 1f),
        )
        records.saveProgress("chapter", 0.75f)
        scheduler.runCurrent()
        val first = store.data.getValue("book")
        assertEquals(0.875f, first.readingProgress)
        assertEquals(mapOf("chapter" to 0.75f, "other" to 1f), first.currentChapterReadingProgressMap)
        assertEquals(first.currentChapterReadingProgressMap, first.maxChapterReadingProgressMap)
        assertEquals("chapter", first.lastReadChapterId)
        assertEquals("Chapter title", first.lastReadChapterTitle)
        assertEquals(time, first.lastReadTime)
        assertEquals(12, first.totalReadTime)

        time = time.plusSeconds(5)
        records.saveProgress("chapter", 0.1f)
        scheduler.runCurrent()
        val second = store.data.getValue("book")
        assertEquals(0.875f, second.readingProgress)
        assertEquals(mapOf("chapter" to 0.1f, "other" to 1f), second.currentChapterReadingProgressMap)
        assertEquals(mapOf("chapter" to 0.75f, "other" to 1f), second.maxChapterReadingProgressMap)
        assertEquals(time, second.lastReadTime)
        assertEquals(
            listOf("update:book", "write:book", "update:book", "write:book"),
            store.events,
        )
    }

    @Test
    fun unknownCatalogKeepsTheStoredTotalAndProgressAboveOneIsStillAccepted() {
        chapters = null
        store.data["book"] = UserReadingData(id = "book", readingProgress = 0.4f)
        records.saveProgress("chapter", 1.5f)
        scheduler.runCurrent()

        val data = store.data.getValue("book")
        assertEquals(0.4f, data.readingProgress)
        assertEquals(mapOf("chapter" to 1.5f), data.currentChapterReadingProgressMap)
        assertEquals(mapOf("chapter" to 1.5f), data.maxChapterReadingProgressMap)
        assertEquals(listOf("update:book", "write:book"), store.events)
    }

    @Test
    fun knownEmptyCatalogClearsOnlyTheTotalAndUnknownCatalogCannotFinish() {
        store.data["book"] = UserReadingData("book", readingProgress = 1f,
            maxChapterReadingProgressMap = mapOf("removed" to 1f))
        chapters = null
        records.saveProgress("chapter", 1f)
        scheduler.runCurrent()
        assertEquals(1f, store.data.getValue("book").readingProgress)
        assertFalse(store.events.contains("finished:book"))

        chapters = emptySet()
        records.saveProgress("chapter", 1f)
        scheduler.runCurrent()
        assertEquals(0f, store.data.getValue("book").readingProgress)
        assertEquals(mapOf("chapter" to 1f, "removed" to 1f), store.data.getValue("book").maxChapterReadingProgressMap)
        assertFalse(store.events.contains("finished:book"))
    }

    @Test
    fun totalsUseOnlyCatalogMaximaAndClampEachFiniteContribution() {
        for (other in listOf(0f, -.2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 2f)) {
            store.data["book"] = UserReadingData("book",
                maxChapterReadingProgressMap = mapOf("chapter" to 2f, "other" to other, "removed" to 1f))
            records.saveProgress("chapter", .5f)
            scheduler.runCurrent()
            assertEquals(if (other == 2f) 1f else .5f, store.data.getValue("book").readingProgress)
            assertEquals(1f, store.data.getValue("book").maxChapterReadingProgressMap["removed"])
        }
    }

    @Test
    fun finalChapterUpdateMarksTheBookFinishedAndPendingDuplicatesMerge() {
        store.data["book"] = UserReadingData(
            id = "book",
            maxChapterReadingProgressMap = mapOf("chapter" to 0.5f, "other" to 1f),
        )
        records.saveProgress("chapter", 1f)
        scheduler.runCurrent()
        assertEquals(1f, store.data.getValue("book").readingProgress)
        assertEquals(listOf("update:book", "write:book", "finished:book"), store.events)

        records.saveProgress("chapter", 1f)
        records.saveProgress("chapter", 1f)
        scheduler.runCurrent()
        assertEquals(1f, store.data.getValue("book").readingProgress)
        assertEquals(
            listOf(
                "update:book", "write:book", "finished:book",
                "update:book", "write:book", "finished:book",
            ),
            store.events,
        )
    }

    @Test
    fun firstReportedChapterContributesToTheOverallProgressImmediately() {
        chapters = setOf("chapter", "other", "third", "fourth")
        records.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()

        val data = store.data.getValue("book")
        assertEquals(0.125f, data.readingProgress)
        assertEquals(mapOf("chapter" to 0.5f), data.maxChapterReadingProgressMap)
        assertFalse(store.events.contains("finished:book"))
    }

    @Test
    fun aboveOneChapterProgressStillClampsTheOverallTotal() {
        store.data["book"] = UserReadingData(id = "book", maxChapterReadingProgressMap = mapOf("other" to 1f))
        records.saveProgress("chapter", 1.5f)
        scheduler.runCurrent()

        val data = store.data.getValue("book")
        assertEquals(1f, data.readingProgress)
        assertEquals(mapOf("chapter" to 1.5f), data.currentChapterReadingProgressMap)
        assertEquals(mapOf("chapter" to 1.5f, "other" to 1f), data.maxChapterReadingProgressMap)
        assertTrue(store.events.contains("finished:book"))
    }

    @Test
    fun finishingWaitsForTheFinalProgressWrite() {
        store.data["book"] = UserReadingData(
            id = "book", readingProgress = 0.75f,
            maxChapterReadingProgressMap = mapOf("chapter" to 0.5f, "other" to 1f),
        )
        val gate = CompletableDeferred<Unit>()
        store.updateGate = gate
        records.saveProgress("chapter", 1f)
        scheduler.runCurrent()
        assertEquals(listOf("update:book"), store.events)
        assertEquals(0.75f, store.data.getValue("book").readingProgress)

        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(1f, store.data.getValue("book").readingProgress)
        assertEquals(listOf("update:book", "write:book", "finished:book"), store.events)
    }

    @Test
    fun queuedProgressCapturesBookAndTitleAndResolvesCatalogWhenWriting() {
        store.data["next"] = UserReadingData(id = "next", maxChapterReadingProgressMap = mapOf("other" to 1f))
        records.saveProgress("chapter", 0.5f)
        bookId = "next"
        title = "New title"
        chapters = setOf("chapter", "other", "third", "fourth")
        time = time.plusMinutes(1)
        scheduler.runCurrent()

        val data = store.data.getValue("book")
        assertEquals(listOf("update:book", "write:book"), store.events)
        assertEquals("Chapter title", data.lastReadChapterTitle)
        assertEquals(0.125f, data.readingProgress)
        assertEquals(time, data.lastReadTime)
        assertEquals(
            UserReadingData(id = "next", maxChapterReadingProgressMap = mapOf("other" to 1f)),
            store.data.getValue("next"),
        )
    }

    @Test
    fun progressResolvesCatalogForTheCapturedBookWhenWriting() {
        val catalog = CompletableDeferred<Set<String>>()
        val identityRecords = ReaderReadingRecords(
            store = store,
            scope = scope,
            statisticsScope = statisticsScope,
            currentBookId = { bookId },
            currentChapterTitle = { title },
            catalogChapterIds = {
                assertEquals("book", it)
                catalog.await()
            },
            now = { time },
            ioDispatcher = dispatcher,
        )
        store.data["book"] = UserReadingData(
            id = "book",
            maxChapterReadingProgressMap = mapOf("chapter" to 0.5f, "other" to 0.5f),
        )

        identityRecords.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()
        assertTrue(store.events.isEmpty())
        bookId = "next"
        catalog.complete(setOf("chapter", "other"))
        scheduler.runCurrent()

        assertEquals(0.5f, store.data.getValue("book").readingProgress)
    }

    @Test
    fun progressEventsObserveRefreshedCatalogs() {
        var catalogReads = 0
        var catalog: Set<String>? = setOf("chapter", "other")
        val cachedRecords = ReaderReadingRecords(
            store = store,
            scope = scope,
            statisticsScope = statisticsScope,
            currentBookId = { bookId },
            currentChapterTitle = { title },
            catalogChapterIds = { catalogReads++; catalog },
            now = { time },
            ioDispatcher = dispatcher,
        )

        cachedRecords.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()
        catalog = setOf("chapter", "other", "third", "fourth")
        cachedRecords.saveProgress("chapter", 0.75f)
        scheduler.runCurrent()

        assertEquals(2, catalogReads)
        assertEquals(2, store.writes.size)
        assertEquals(0.1875f, store.writes.last().readingProgress)
    }

    @Test
    fun unknownCatalogDoesNotBlockLaterDirectoryUpdates() {
        var catalogReads = 0
        var catalog: Set<String>? = null
        val retryingRecords = ReaderReadingRecords(
            store = store,
            scope = scope,
            statisticsScope = statisticsScope,
            currentBookId = { bookId },
            currentChapterTitle = { title },
            catalogChapterIds = { catalogReads++; catalog },
            now = { time },
            ioDispatcher = dispatcher,
        )

        retryingRecords.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()
        catalog = setOf("chapter", "other")
        retryingRecords.saveProgress("chapter", 0.75f)
        scheduler.runCurrent()

        assertEquals(2, catalogReads)
        assertEquals(0.375f, store.writes.last().readingProgress)
    }

    @Test
    fun catalogFailureStillPersistsTheProgressEventWithoutFinishing() {
        val failingRecords = ReaderReadingRecords(
            store = store,
            scope = scope,
            statisticsScope = statisticsScope,
            currentBookId = { bookId },
            currentChapterTitle = { title },
            catalogChapterIds = { error("directory unavailable") },
            now = { time },
            ioDispatcher = dispatcher,
        )
        store.data["book"] = UserReadingData(id = "book", readingProgress = 1f)

        failingRecords.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()

        assertEquals(1f, store.data.getValue("book").readingProgress)
        assertEquals(mapOf("chapter" to 0.5f), store.data.getValue("book").currentChapterReadingProgressMap)
        assertEquals(listOf("update:book", "write:book"), store.events)
    }

    @Test
    fun completionCheckUsesTheCapturedBookAfterPersistenceSuspension() {
        val gate = CompletableDeferred<Unit>()
        store.updateGate = gate
        records.saveProgress("chapter", 0.5f)
        scheduler.runCurrent()
        assertEquals(listOf("update:book"), store.events)

        bookId = "next"
        store.data["next"] = UserReadingData(id = "next", readingProgress = 1f)
        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(listOf("update:book", "write:book"), store.events)
    }

    @Test
    fun totalTimeWritesKeepAllDeltasAndMetadataEvenForZeroAndBlankBook() {
        val original = UserReadingData(
            id = "book", totalReadTime = 10, readingProgress = 0.5f,
            lastReadChapterId = "chapter", lastReadChapterTitle = "Stored title",
        )
        store.data["book"] = original
        records.updateTotalReadingTime("book", 61)
        records.updateTotalReadingTime("book", 0)
        records.updateTotalReadingTime("book", -1)
        records.updateTotalReadingTime("", 2)
        scheduler.runCurrent()

        assertEquals(listOf(71, 71, 70, 2), store.writes.map { it.totalReadTime })
        assertEquals(original.copy(totalReadTime = 70, lastReadTime = time), store.data.getValue("book"))
        assertEquals(UserReadingData(id = "", totalReadTime = 2, lastReadTime = time), store.data.getValue(""))
        assertEquals(4, clockReads)
        assertTrue(store.statistics.isEmpty())
    }

    @Test
    fun statisticsForwardTicksAndFlushOnTheIndependentScopeAfterOwnerCancellation() {
        records.accumulateReadingTime("", 1)
        records.accumulateReadingTime("  ", -1)
        records.updateTotalReadingTime("book", 5)
        records.accumulateReadingTime("book", 1)
        scope.cancel()
        records.accumulateReadingTime("book", 0)
        records.accumulateReadingTime("book", -1)
        scheduler.runCurrent()

        assertEquals(listOf("accumulate:book:1", "accumulate:book:0", "accumulate:book:-1"), store.events)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun accumulatedReadingTimeFlushWaitsForEarlierPositiveDelta() {
        val gate = CompletableDeferred<Unit>()
        store.accumulateGate = gate
        records.accumulateReadingTime("book", 3)
        records.accumulateReadingTime("book", -1)

        scheduler.runCurrent()
        assertEquals(listOf("accumulate:book:3"), store.events)

        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(
            listOf("accumulate:book:3", "accumulate:book:-1"),
            store.events,
        )
    }

    @Test
    fun accumulatedReadingTimePreservesCallOrderWhenTheDispatcherStartsFlushFirst() {
        val pending = ArrayDeque<Runnable>()
        val reverseDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending.addLast(block)
            }
        }
        val reverseRecords = ReaderReadingRecords(
            store, scope, statisticsScope, { bookId }, { title }, { chapters },
            ioDispatcher = reverseDispatcher,
        )
        reverseRecords.accumulateReadingTime("book", 3)
        reverseRecords.accumulateReadingTime("book", -1)

        pending.removeLast().run()
        assertTrue(store.events.isEmpty())
        while (pending.isNotEmpty()) pending.removeLast().run()

        assertEquals(listOf("accumulate:book:3", "accumulate:book:-1"), store.events)
    }

    @Test
    fun progressPreservesEventOrderWhenTheIoDispatcherStartsTheNewerSaveFirst() {
        val pending = ArrayDeque<Runnable>()
        val reverseDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val reverseRecords = ReaderReadingRecords(
            store, scope, statisticsScope, { bookId }, { title }, { chapters },
            ioDispatcher = reverseDispatcher,
        )
        reverseRecords.saveProgress("first", 0.1f)
        reverseRecords.saveProgress("second", 0.5f)
        while (pending.isNotEmpty()) pending.removeLast().run()
        assertEquals("second", store.data.getValue("book").lastReadChapterId)
        assertEquals(mapOf("first" to 0.1f, "second" to 0.5f), store.data.getValue("book").currentChapterReadingProgressMap)
    }

    @Test
    fun aBurstBehindAnActiveWriteSavesOnlyTheLatestPositionAndHighestObservedProgress() {
        val history = (1..10_000).associate { "old-$it" to 0.5f }
        store.data["book"] = UserReadingData("book", currentChapterReadingProgressMap = history,
            maxChapterReadingProgressMap = history)
        chapters = history.keys + "chapter"
        val gate = CompletableDeferred<Unit>()
        store.updateGate = gate
        records.saveProgress("chapter", 0.1f)
        scheduler.runCurrent()
        records.saveProgress("chapter", 1f)
        // Let the pending worker start and wait on the active write before more events arrive.
        scheduler.runCurrent()
        repeat(499) { records.saveProgress("chapter", 0.2f + it / 1000f) }
        scheduler.runCurrent()
        assertEquals(listOf("update:book"), store.events)
        gate.complete(Unit)
        scheduler.runCurrent()

        assertEquals(2, store.writes.size)
        assertEquals(0.698f, store.data.getValue("book").currentChapterReadingProgressMap.getValue("chapter"), 0.00001f)
        assertEquals(1f, store.data.getValue("book").maxChapterReadingProgressMap["chapter"])
        assertEquals(history, store.data.getValue("book").maxChapterReadingProgressMap - "chapter")
        assertFalse(store.events.any { it.startsWith("read:") })
    }

    @Test
    fun coalescingDoesNotReorderChapterVisitsOrKeepAnOutdatedCurrentChapterCallback() {
        var currentChapter = "first"
        records.saveProgress("first", 0.9f) { currentChapter == "first" }
        title = "Second"
        records.saveProgress("second", 0.8f) { currentChapter == "second" }
        title = "Returned"
        records.saveProgress("first", 0.3f) { false }
        records.saveProgress("first", 0.2f) { currentChapter == "first" }
        scheduler.runCurrent()

        assertEquals(3, store.writes.size)
        val data = store.data.getValue("book")
        assertEquals(mapOf("first" to 0.2f, "second" to 0.8f), data.currentChapterReadingProgressMap)
        assertEquals(mapOf("first" to 0.9f, "second" to 0.8f), data.maxChapterReadingProgressMap)
        assertEquals("first", data.lastReadChapterId)
        assertEquals("Returned", data.lastReadChapterTitle)
    }

    @Test
    fun totalReadingTimeDeltasAreSerializedAroundReadModifyWrite() {
        val gate = CompletableDeferred<Unit>()
        store.updateGate = gate
        records.updateTotalReadingTime("book", 60)
        records.updateTotalReadingTime("book", 30)

        scheduler.runCurrent()
        assertEquals(listOf("update:book"), store.events)

        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(90, store.data.getValue("book").totalReadTime)
    }

    @Test
    fun defaultTimestampStillUsesTheSystemLocalDateTime() {
        val defaultTimeRecords = ReaderReadingRecords(
            store, scope, statisticsScope, { bookId }, { title }, { chapters },
            ioDispatcher = dispatcher,
        )
        val before = LocalDateTime.now()
        defaultTimeRecords.updateTotalReadingTime("book", 1)
        scheduler.runCurrent()
        val after = LocalDateTime.now()
        val recorded = requireNotNull(store.data.getValue("book").lastReadTime)
        assertFalse(recorded.isBefore(before))
        assertFalse(recorded.isAfter(after))
    }

    private class RecordingStore : ReaderRecordStore {
        var revision = 0L
        var rejectProgress = false
        val progressRevisions = mutableListOf<Long>()
        override fun progressRevision(): Long = revision
        override suspend fun updateChapterProgress(
            bookId: String, chapterId: String, revision: Long,
            update: (UserReadingData) -> UserReadingData,
        ): Boolean {
            progressRevisions += revision
            if (rejectProgress) return false
            updateUserReadingData(bookId, update)
            return true
        }
        val data = mutableMapOf("book" to UserReadingData("book"))
        val events = mutableListOf<String>()
        val writes = mutableListOf<UserReadingData>()
        val statistics = mutableListOf<ReadingStatsUpdate>()
        var recentBooks = emptyList<String>()
        var recentGate: CompletableDeferred<Unit>? = null
        var updateGate: CompletableDeferred<Unit>? = null
        var accumulateGate: CompletableDeferred<Unit>? = null

        override suspend fun updateRecentBooks(update: (List<String>) -> List<String>) {
            events += "recent:start"
            recentGate?.await()
            recentBooks = update(recentBooks)
            events += "recent:write"
        }

        override suspend fun updateUserReadingData(bookId: String, update: (UserReadingData) -> UserReadingData) {
            events += "update:$bookId"
            updateGate?.await()
            val updated = update(data[bookId] ?: UserReadingData(bookId))
            data[bookId] = updated
            writes += updated
            events += "write:$bookId"
        }

        override suspend fun getUserReadingData(bookId: String): UserReadingData {
            events += "read:$bookId"
            return data.getValue(bookId)
        }

        override suspend fun updateReadingStatistics(update: ReadingStatsUpdate) {
            events += "stats:${update.bookId}"
            statistics += update
        }

        override suspend fun markBookFinished(bookId: String) {
            events += "finished:$bookId"
        }

        override suspend fun accumulateBookReadTime(bookId: String, seconds: Int) {
            events += "accumulate:$bookId:$seconds"
            accumulateGate?.await()
        }
    }
}
