package indi.renakoni.nextvol.data.statistics

import indi.renakoni.nextvol.data.book.BookIdentity
import android.app.Application
import indi.renakoni.nextvol.data.local.room.dao.BookRecordDao
import indi.renakoni.nextvol.data.local.room.dao.DailyCountDao
import indi.renakoni.nextvol.data.local.room.entity.BookRecordEntity
import indi.renakoni.nextvol.data.local.room.entity.DailyCountEntity
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Statistics buffer ownership, settlement, and summary precision contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class StatsRepositoryCharacterizationTest {
    private val records = mutableMapOf<Pair<String, LocalDate>, BookRecordEntity>()
    private val dailyCounts = mutableMapOf<LocalDate, DailyCountEntity>()
    private var failRecordWrite = false
    private var recordWriteGate: CompletableDeferred<Unit>? = null
    private var recordWriteStarted: CompletableDeferred<Unit>? = null
    private var recordWriteCalls = 0
    private var cancelDailyWriteAfterCommit = false
    private val recordDao = mockk<BookRecordDao> {
        coEvery { getBookRecordByIdAndDate(any(), any()) } answers { records[firstArg<String>() to secondArg<LocalDate>()] }
        coEvery { insertBookRecord(any()) } coAnswers {
            if (failRecordWrite) throw IllegalStateException("record write failed")
            val call = recordWriteCalls++
            if (call == 0) {
                recordWriteStarted?.complete(Unit)
                recordWriteGate?.await()
            }
            val record = firstArg<BookRecordEntity>()
            records[record.bookId to record.date] = record
        }
        coEvery { getAllBookRecords() } answers { records.values.toList() }
        coEvery { clear() } just Runs
    }
    private val dailyDao = mockk<DailyCountDao> {
        coEvery { getByDate(any()) } answers { dailyCounts[firstArg<LocalDate>()] }
        coEvery { insert(any()) } answers {
            val record = firstArg<DailyCountEntity>()
            dailyCounts[record.date] = record
            if (cancelDailyWriteAfterCommit) {
                cancelDailyWriteAfterCommit = false
                throw CancellationException("daily write cancelled after commit")
            }
        }
        coEvery { deleteByDate(any()) } answers {
            dailyCounts.remove(firstArg<LocalDate>())
        }
        coEvery { getAll() } answers { dailyCounts.values.toList() }
        coEvery { clear() } just Runs
    }
    private val repository = StatsRepository(
        recordDao,
        dailyDao,
        mockk(),
        StatisticsWriteCoordinator()
    )

    @Test
    fun failedFlushRetainsBufferedSecondsForRetry() = runTest {
        repository.accumulateBookReadTime(BookIdentity.bookKey("book"), 10)
        failRecordWrite = true

        var failed = false
        try {
            repository.accumulateBookReadTime(BookIdentity.bookKey("book"), -1)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertEquals(true, failed)

        failRecordWrite = false
        repository.accumulateBookReadTime(BookIdentity.bookKey("book"), -1)

        assertEquals(10, records.getValue(BookIdentity.bookKey("book") to LocalDate.now()).seconds)
    }

    @Test
    fun failedRecordWriteRestoresAnExistingDailyCountWithoutTheFailedDelta() = runTest {
        repository.accumulateBookReadTime(BookIdentity.bookKey("book"), 60)
        repository.accumulateBookReadTime(BookIdentity.bookKey("book"), -1)
        failRecordWrite = true

        try {
            repository.accumulateBookReadTime(BookIdentity.bookKey("book"), 60)
        } catch (_: IllegalStateException) {
            // The pre-existing daily count must remain intact for the retry.
        }

        assertEquals(1, repository.getTotalReadingSummary().totalMinutes)
    }

    @Test
    fun finishingABookCannotOverwriteAConcurrentReadingUpdate() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()
        recordWriteStarted = writeStarted
        recordWriteGate = writeGate

        val readingJob = launch {
            repository.updateReadingStatistics(
                ReadingStatsUpdate(bookId = BookIdentity.bookKey("book"), readEventDelta = 1)
            )
        }
        writeStarted.await()

        val finishingJob = launch { repository.markBookFinished(BookIdentity.bookKey("book")) }
        runCurrent()
        assertFalse(finishingJob.isCompleted)

        writeGate.complete(Unit)
        readingJob.join()
        finishingJob.join()

        val record = records.getValue(BookIdentity.bookKey("book") to LocalDate.now())
        assertEquals(1, record.reads)
        assertTrue(record.isFinished)
    }

    @Test
    fun cancellationAfterDailyCountCommitStillRollsBackBeforeRetry() = runTest {
        cancelDailyWriteAfterCommit = true

        try {
            repository.accumulateBookReadTime(BookIdentity.bookKey("book"), 60)
        } catch (_: CancellationException) {
            // The daily row was committed before cancellation was reported.
        }

        assertEquals(0, dailyCounts[LocalDate.now()]?.timeCount?.getTotalMinutes() ?: 0)

        repository.accumulateBookReadTime(BookIdentity.bookKey("book"), -1)

        assertEquals(1, dailyCounts.getValue(LocalDate.now()).timeCount.getTotalMinutes())
    }

    @Test
    fun twoThirtySecondSettlementsProduceOneSummaryMinuteFromBookSeconds() = runTest {
        repeat(2) {
            repository.accumulateBookReadTime(BookIdentity.bookKey("book"), 30)
            repository.accumulateBookReadTime(BookIdentity.bookKey("book"), -1)
        }

        assertEquals(60, records.values.sumOf { it.seconds })
        assertEquals(1, repository.getTotalReadingSummary().totalMinutes)
    }
}
