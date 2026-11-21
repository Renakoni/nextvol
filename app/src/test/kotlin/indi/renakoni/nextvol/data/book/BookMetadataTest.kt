package indi.renakoni.nextvol.data.book

import hnovel.content.parseBookUpdateTime
import hnovel.content.parseBookWordCount
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class BookMetadataTest {
    @Test fun missingInvalidAndOverflowingCountsRemainUnknown() {
        for (raw in listOf("", "未知", "0", "-1", "1.23", "1,23", "30亿", "约十万字", "2026-09-14"))
            assertNull(raw, parseBookWordCount(raw))
    }

    @Test fun oversizedCountsAreRejectedBeforeRegexAndDecimalConversion() {
        assertNull(parseBookWordCount("1,".repeat(10000) + "123"))
        assertNull(parseBookWordCount("0".repeat(10000) + "1万"))
        assertEquals(Int.MAX_VALUE, parseBookWordCount("2,147,483,647"))
        assertNull(parseBookWordCount("2,147,483,648"))
    }

    @Test fun absoluteDatesRetainSourcePrecisionAndCalendarDate() {
        val date = LocalDateTime.of(2026, 9, 14, 0, 0)
        for (raw in listOf("2026-09-14", "2026/9/14", "2026年9月14日"))
            assertEquals(raw, date, parseBookUpdateTime(raw))
        for (raw in listOf(" 2026-09-14 08:00:00 ", "2026-9-14 8:00", "2026/9/14 8:00:00", "2026-09-14T08:00:00"))
            assertEquals(raw, date.withHour(8), parseBookUpdateTime(raw))
        assertEquals(date, parseBookUpdateTime("2026-09-14T00:00:00+08:00"))
    }

    @Test fun missingInvalidAndRelativeDatesNeverBecomeTheCurrentTime() {
        for (raw in listOf("", "未知", "刚刚", "3小时前", "2026-02-30", "2026-13-01", "1970-01-01"))
            assertNull(raw, parseBookUpdateTime(raw))
    }
}
