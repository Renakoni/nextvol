package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import indi.renakoni.nextvol.theme.NextVolDesign

enum class Level { Zero, One, Two, Three, Four }

@Composable
internal fun Level.themedColor(isWeekend: Boolean): Color {
    val isDark = NextVolDesign.tokens.isDark
    return when (this) {
        Level.Zero -> if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
        Level.One -> if (isDark) {
            if (isWeekend) Color(0xFF1F3154) else Color(0xFF1D4330)
        } else {
            if (isWeekend) Color(0xFFD2E0F6) else Color(0xFFCFE8D6)
        }
        Level.Two -> if (isDark) {
            if (isWeekend) Color(0xFF31518A) else Color(0xFF2D6C4C)
        } else {
            if (isWeekend) Color(0xFF92B3E6) else Color(0xFF8CC9A1)
        }
        Level.Three -> if (isDark) {
            if (isWeekend) Color(0xFF5A84CC) else Color(0xFF47A371)
        } else {
            if (isWeekend) Color(0xFF5380CB) else Color(0xFF4DA56E)
        }
        Level.Four -> if (isDark) {
            if (isWeekend) Color(0xFF93B4F5) else Color(0xFF7BE0A8)
        } else {
            if (isWeekend) Color(0xFF2B4C7E) else Color(0xFF247849)
        }
    }
}
