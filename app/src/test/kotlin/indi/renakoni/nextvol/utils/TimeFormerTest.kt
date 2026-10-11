package indi.renakoni.nextvol.utils

import android.app.Application
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class TimeFormerTest {
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun readingDurationUsesTheSmallestUsefulUnit() {
        assertEquals("45s", formReadingDuration(45))
        assertEquals("1m", formReadingDuration(60))
        assertEquals("59m", formReadingDuration(3599))
    }

    @Test
    fun readingDurationUsesOneDecimalHourAfterOneHour() {
        assertEquals("1.0h", formReadingDuration(3600))
        assertEquals("1.5h", formReadingDuration(5400))
    }

    @Test
    fun minuteBasedStatisticsUseTheSameHourFormat() {
        assertEquals("1.0h", formMinutes(60))
    }
}
