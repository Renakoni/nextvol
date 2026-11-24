package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import indi.renakoni.nextvol.theme.NextVolDesign

enum class Level(val color: Color, val colorWeekends: Color) {
    Zero(color = Color(0x16444444), colorWeekends = Color(0x16444444)),
    One(color = Color(0x33329c32), colorWeekends = Color(0x3329538f)),
    Two(color = Color(0x77329c32), colorWeekends = Color(0x7729538f)),
    Three(color = Color(0xAA329c32), colorWeekends = Color(0xAA29538f)),
    Four(color = Color(0xFF329c32), colorWeekends = Color(0xFF29538f)),
}

internal fun Level.resolvedColor(
    isWeekend: Boolean,
    isDark: Boolean,
    emptySurface: Color,
): Color {
    if (this == Level.Zero) return emptySurface
    return if (isDark) {
        if (isWeekend) {
            when (this) {
                Level.One -> Color(0xFF1F3154)
                Level.Two -> Color(0xFF31518A)
                Level.Three -> Color(0xFF5A84CC)
                Level.Four -> Color(0xFF93B4F5)
                Level.Zero -> emptySurface
            }
        } else {
            when (this) {
                Level.One -> Color(0xFF1D4330)
                Level.Two -> Color(0xFF2D6C4C)
                Level.Three -> Color(0xFF47A371)
                Level.Four -> Color(0xFF7BE0A8)
                Level.Zero -> emptySurface
            }
        }
    } else {
        if (isWeekend) {
            when (this) {
                Level.One -> Color(0xFFD2E0F6)
                Level.Two -> Color(0xFF92B3E6)
                Level.Three -> Color(0xFF5380CB)
                Level.Four -> Color(0xFF2B4C7E)
                Level.Zero -> emptySurface
            }
        } else {
            when (this) {
                Level.One -> Color(0xFFCFE8D6)
                Level.Two -> Color(0xFF8CC9A1)
                Level.Three -> Color(0xFF4DA56E)
                Level.Four -> Color(0xFF247849)
                Level.Zero -> emptySurface
            }
        }
    }
}

@Composable
internal fun Level.themedColor(isWeekend: Boolean): Color {
    val isDark = NextVolDesign.tokens.isDark
    val emptySurface = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    return resolvedColor(isWeekend = isWeekend, isDark = isDark, emptySurface = emptySurface)
}
