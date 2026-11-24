package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StatsHeatmapLevelsTest {

    @Test
    fun resolvedColorProducesDistinctOrderedStepsForLightAndDarkModes() {
        val lightEmpty = Color(0xFFEBE6DD)
        val darkEmpty = Color(0xFF282D39)

        for (isWeekend in listOf(false, true)) {
            val lightRamp = Level.entries.map { it.resolvedColor(isWeekend, isDark = false, emptySurface = lightEmpty) }
            val darkRamp = Level.entries.map { it.resolvedColor(isWeekend, isDark = true, emptySurface = darkEmpty) }

            assertEquals(lightEmpty, lightRamp.first())
            assertEquals(darkEmpty, darkRamp.first())
            assertEquals(Level.entries.size, lightRamp.toSet().size)
            assertEquals(Level.entries.size, darkRamp.toSet().size)
            assertNotEquals(lightRamp.last(), darkRamp.last())
        }
    }
}
