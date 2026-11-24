package indi.renakoni.nextvol.ui.components

import androidx.compose.ui.graphics.Color
import indi.renakoni.nextvol.theme.DarkObsidianColorScheme
import indi.renakoni.nextvol.theme.DefaultDarkColorScheme
import indi.renakoni.nextvol.theme.DefaultLightColorScheme
import indi.renakoni.nextvol.theme.buildNextVolDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsSliderStepTest {

    @Test
    fun resolveDiscreteStepIndexMatchesExactClampsOutOfRangeAndSnapsNearest() {
        val steps = listOf(10f, 14f, 18f, 24f)

        assertEquals(2, resolveDiscreteStepIndex(steps, 18f))
        assertEquals(0, resolveDiscreteStepIndex(steps, 4f))
        assertEquals(3, resolveDiscreteStepIndex(steps, 40f))
        assertEquals(1, resolveDiscreteStepIndex(steps, 15.5f))
        assertEquals(2, resolveDiscreteStepIndex(steps, 17f))
    }

    @Test
    fun buildNextVolDesignTokensPreservesDistinctSurfaceLayersAcrossThemes() {
        val light = buildNextVolDesignTokens(DefaultLightColorScheme, isDark = false)
        val dark = buildNextVolDesignTokens(DefaultDarkColorScheme, isDark = true)
        val obsidian = buildNextVolDesignTokens(DarkObsidianColorScheme, isDark = true)

        assertNotEquals(DefaultLightColorScheme.surface, light.cardSurface)
        assertNotEquals(DefaultDarkColorScheme.surface, dark.cardSurface)
        assertEquals(Color(0xFF000000), DarkObsidianColorScheme.surface)
        assertNotEquals(Color(0xFF000000), obsidian.cardSurface)
    }
}
