package indi.renakoni.nextvol.data.statistics

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.local.room.dao.BookRecordDao
import indi.renakoni.nextvol.data.local.room.dao.DailyCountDao
import indi.renakoni.nextvol.data.local.room.entity.BookRecordEntity
import indi.renakoni.nextvol.data.local.room.entity.DailyCountEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Suppress("unused")
@Singleton
class StatsRepository @Inject constructor(
    private val bookRecordDao: BookRecordDao,
    private val dailyCountDao: DailyCountDao,
    private val bookRepository: BookRepository,
    private val statisticsWriteCoordinator: StatisticsWriteCoordinator
) {
    private val bookReadTimeBuffer = mutableMapOf<String, Pair<LocalTime, Int>>()
    private val bookReadTimeBufferMutex = Mutex()

    suspend fun accumulateBookReadTime(bookId: String, seconds: Int) {
        val key = BookIdentity.bookKey(bookId)
        bookReadTimeBufferMutex.withLock {
            if (seconds < 0) {
                clearBookReadTimeBufferLocked(key)
                return
            }
            val current = bookReadTimeBuffer[key] ?: Pair(LocalTime.now(), 0)
            val newTotal = current.second + seconds
            bookReadTimeBuffer[key] = current.copy(second = newTotal)

            if (newTotal >= 60 || Duration.between(current.first, LocalTime.now()).seconds >= 60) {
                clearBookReadTimeBufferLocked(key)
            }
        }
    }

    private suspend fun clearBookReadTimeBufferLocked(key: String) {
        val (startTime, totalSeconds) = bookReadTimeBuffer[key] ?: return

        updateReadingStatistics(
            ReadingStatsUpdate(
                bookId = key,
                secondDelta = totalSeconds,
                localTime = startTime,
                readEventDelta = 0
            )
        )

        bookReadTimeBuffer.remove(key)
    }

    suspend fun getBookRecords(
        start: LocalDate,
        end: LocalDate? = null
    ): Map<LocalDate, List<BookRecord>> {
        return if (end == null) {
            bookRecordDao.getBookRecordsForDate(start)
                .map { it.toData(bookRepository) }
                .takeIf { it.isNotEmpty() }
                ?.let { mapOf(start to it) }
                ?: emptyMap()
        } else {
            bookRecordDao
                .getBookRecordsBetweenDates(start, end)
                .map { it.toData(bookRepository) }
                .groupBy { it.date }
                .filterValues { it.isNotEmpty() }
        }
    }

    suspend fun getDailyCounts(start: LocalDate, end: LocalDate): Map<LocalDate, Count> {
        return dailyCountDao.getBetween(start, end)
            .associate { it.date to it.timeCount }
    }

    suspend fun getTotalReadingSummary(): TotalReadingSummary {
        val records = bookRecordDao.getAllBookRecords()
        val totalMinutes = (records.sumOf { it.seconds.toLong() } / 60L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val totalReadCount = records.sumOf { it.reads }
        return TotalReadingSummary(
            totalMinutes = totalMinutes,
            totalReadCount = totalReadCount
        )
    }

    suspend fun updateReadingStatistics(update: ReadingStatsUpdate) {
        val key = BookIdentity.bookKey(update.bookId)
        statisticsWriteCoordinator.withLock {
            val today = LocalDate.now()
            val existingDailyCount = dailyCountDao.getByDate(today)
            val dailyCount = existingDailyCount ?: DailyCountEntity(today, Count())
            val updatedDailyCount = dailyCount.copy(
                timeCount = updateCount(dailyCount.timeCount.copy(), update)
            )

            try {
                dailyCountDao.insert(updatedDailyCount)
                val existingRecord = bookRecordDao.getBookRecordByIdAndDate(key, today)
                    ?: createRecordEntity(key, today)
                val updatedRecord = existingRecord.copy(
                    reads = existingRecord.reads + update.readEventDelta,
                    seconds = existingRecord.seconds + update.secondDelta,
                    lastSeen = update.localTime,
                )
                bookRecordDao.insertBookRecord(updatedRecord)
            } catch (failure: Throwable) {
                // Keep a failed retry from counting the daily delta twice. Room's
                // production implementation should eventually move this pair of
                // writes into one database transaction; restoring the prior row
                // keeps the current DAO boundary idempotent while preserving the
                // buffered book seconds for the caller to retry.
                withContext(NonCancellable) {
                    if (existingDailyCount == null) {
                        dailyCountDao.deleteByDate(today)
                    } else {
                        dailyCountDao.insert(existingDailyCount)
                    }
                }
                throw failure
            }
        }
    }

    suspend fun markBookFinished(bookId: String) {
        val key = BookIdentity.bookKey(bookId)
        statisticsWriteCoordinator.withLock {
            val today = LocalDate.now()
            val existingRecord = bookRecordDao.getBookRecordByIdAndDate(key, today)
                ?: createRecordEntity(key, today)

            if (!existingRecord.isFinished) {
                bookRecordDao.insertBookRecord(existingRecord.copy(isFinished = true))
            }
        }
    }

    suspend fun markBookFavorited(bookId: String) {
        val key = BookIdentity.bookKey(bookId)
        statisticsWriteCoordinator.withLock {
            val today = LocalDate.now()
            val existingRecord = bookRecordDao.getBookRecordByIdAndDate(key, today)
                ?: createRecordEntity(key, today)

            if (!existingRecord.isFavorited) {
                bookRecordDao.insertBookRecord(existingRecord.copy(isFavorited = true))
            }
        }
    }

    suspend fun getBookFirstReadDateMap(): Map<String, LocalDate> =
        bookRecordDao.getFirstReadDates().associate { it.bookId to it.date }

    suspend fun getBookFirstFinishedDateMap(): Map<String, LocalDate> =
        bookRecordDao.getFirstFinishedDates().associate { it.bookId to it.date }

    suspend fun getBookFavoriteDateMap(): Map<String, LocalDate> =
        bookRecordDao.getFirstFavoritedDates().associate { it.bookId to it.date }

    private fun createRecordEntity(bookId: String, date: LocalDate): BookRecordEntity =
        BookRecordEntity(
            bookId = bookId,
            date = date,
            reads = 0,
            seconds = 0,
            isFinished = false,
            isFavorited = false,
            firstSeen = LocalTime.now(),
            lastSeen = LocalTime.now(),
        )

    private fun updateCount(count: Count, update: ReadingStatsUpdate): Count {
        val minutesDelta = update.secondDelta / 60
        if (minutesDelta > 0) {
            val hour = update.localTime.hour
            val totalMinutes = count.getMinute(hour) + minutesDelta
            count.setMinute(hour, totalMinutes.coerceAtMost(60))
        }
        return count
    }

    suspend fun clear() {
        withStatisticsResetLock {
            bookRecordDao.clear()
            dailyCountDao.clear()
        }
    }

    suspend fun withStatisticsResetLock(block: suspend () -> Unit) {
        bookReadTimeBufferMutex.withLock {
            statisticsWriteCoordinator.withLock {
                block()
                bookReadTimeBuffer.clear()
            }
        }
    }
}
