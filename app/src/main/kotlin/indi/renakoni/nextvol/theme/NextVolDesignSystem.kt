package indi.renakoni.nextvol.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.theme.Colors as MiuixColors
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

/**
 * Editorial Minimalism x Liquid Glass design tokens shared across NextVol.
 * Keeps spacing, continuous squircle radii, glass tints, and specular rim hairlines disciplined.
 */
@Immutable
data class NextVolDesignTokens(
    val isDark: Boolean,
    val spaceXs: Dp = 4.dp,
    val spaceSm: Dp = 8.dp,
    val spaceMd: Dp = 12.dp,
    val spaceLg: Dp = 16.dp,
    val spaceXl: Dp = 20.dp,
    val spaceXxl: Dp = 24.dp,
    val cardRadius: Dp = 20.dp,
    val sheetRadius: Dp = 28.dp,
    val popupRadius: Dp = 18.dp,
    val controlRadius: Dp = 14.dp,
    val chipRadius: Dp = 12.dp,
    val cardSurface: Color,
    val cardElevatedSurface: Color,
    val cardBorder: Color,
    val divider: Color,
    val iconBadgeContainer: Color,
    val iconBadgeContent: Color,
    val glassSurfaceTint: Color,
    val glassFallbackSurface: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val glassShadow: Color,
    val activePillFill: Color,
    val activePillBorder: Color,
    val topBarGlassTint: Color,
    val topBarFallbackSurface: Color,
    val snackbarContainer: Color,
    val snackbarContent: Color,
    val snackbarAction: Color,
)

val LocalNextVolTokens = staticCompositionLocalOf<NextVolDesignTokens> {
    buildNextVolDesignTokens(DefaultLightColorScheme, isDark = false)
}

/**
 * Root-level HazeState captured behind floating chrome (navigation capsule, overlays, top bars).
 * Nullable so isolated previews and unit tests gracefully fall back to translucent scrim surfaces.
 */
val LocalNextVolHazeState = staticCompositionLocalOf<HazeState?> { null }

object NextVolDesign {
    val tokens: NextVolDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalNextVolTokens.current
}

internal fun buildNextVolDesignTokens(
    colorScheme: ColorScheme,
    isDark: Boolean,
): NextVolDesignTokens {
    return NextVolDesignTokens(
        isDark = isDark,
        cardSurface = colorScheme.surfaceContainerLow,
        cardElevatedSurface = colorScheme.surfaceContainerHigh,
        cardBorder = if (isDark) {
            Color.White.copy(alpha = 0.11f)
        } else {
            Color(0xFF0F172A).copy(alpha = 0.12f)
        },
        divider = if (isDark) {
            Color.White.copy(alpha = 0.11f)
        } else {
            Color(0xFF0F172A).copy(alpha = 0.11f)
        },
        iconBadgeContainer = colorScheme.primaryContainer.copy(alpha = if (isDark) 0.42f else 0.52f),
        iconBadgeContent = colorScheme.primary,
        glassSurfaceTint = if (isDark) {
            colorScheme.surfaceContainer.copy(alpha = 0.56f)
        } else {
            Color.White.copy(alpha = 0.46f)
        },
        glassFallbackSurface = if (isDark) {
            colorScheme.surfaceContainer.copy(alpha = 0.92f)
        } else {
            Color.White.copy(alpha = 0.92f)
        },
        glassBorderTop = if (isDark) {
            Color.White.copy(alpha = 0.18f)
        } else {
            Color.White.copy(alpha = 0.96f)
        },
        glassBorderBottom = if (isDark) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color(0xFF0F172A).copy(alpha = 0.15f)
        },
        glassShadow = Color.Black.copy(alpha = if (isDark) 0.52f else 0.24f),
        activePillFill = colorScheme.primary.copy(alpha = if (isDark) 0.18f else 0.10f),
        activePillBorder = colorScheme.primary.copy(alpha = if (isDark) 0.28f else 0.16f),
        topBarGlassTint = if (isDark) {
            colorScheme.surface.copy(alpha = 0.54f)
        } else {
            colorScheme.surface.copy(alpha = 0.52f)
        },
        topBarFallbackSurface = colorScheme.surface.copy(alpha = 0.96f),
        snackbarContainer = if (isDark) {
            colorScheme.surfaceContainerHighest.copy(alpha = 0.95f)
        } else {
            colorScheme.inverseSurface.copy(alpha = 0.94f)
        },
        snackbarContent = if (isDark) colorScheme.onSurface else colorScheme.inverseOnSurface,
        snackbarAction = if (isDark) colorScheme.primary else colorScheme.inversePrimary,
    )
}

@Composable
internal fun rememberBridgedMiuixColors(
    colorScheme: ColorScheme,
    isDark: Boolean,
): MiuixColors = remember(colorScheme, isDark) {
    val base = if (isDark) miuixDarkColorScheme() else miuixLightColorScheme()
    base.copy(
        primary = colorScheme.primary,
        onPrimary = colorScheme.onPrimary,
        primaryVariant = colorScheme.primary,
        onPrimaryVariant = colorScheme.onPrimary,
        error = colorScheme.error,
        onError = colorScheme.onError,
        errorContainer = colorScheme.errorContainer,
        onErrorContainer = colorScheme.onErrorContainer,
        disabledPrimary = colorScheme.primary.copy(alpha = 0.38f),
        disabledOnPrimary = colorScheme.onPrimary.copy(alpha = 0.60f),
        disabledPrimaryButton = colorScheme.surfaceContainerHighest,
        disabledOnPrimaryButton = colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
        disabledPrimarySlider = colorScheme.primary.copy(alpha = 0.38f),
        primaryContainer = colorScheme.primaryContainer,
        onPrimaryContainer = colorScheme.onPrimaryContainer,
        secondary = colorScheme.secondary,
        onSecondary = colorScheme.onSecondary,
        secondaryContainer = colorScheme.secondaryContainer,
        onSecondaryContainer = colorScheme.onSecondaryContainer,
        tertiaryContainer = colorScheme.tertiaryContainer,
        onTertiaryContainer = colorScheme.onTertiaryContainer,
        background = colorScheme.background,
        onBackground = colorScheme.onBackground,
        onBackgroundVariant = colorScheme.onSurfaceVariant,
        surface = colorScheme.surface,
        onSurface = colorScheme.onSurface,
        surfaceVariant = colorScheme.surfaceVariant,
        onSurfaceSecondary = colorScheme.onSurfaceVariant,
        onSurfaceVariantSummary = colorScheme.onSurfaceVariant,
        onSurfaceVariantActions = colorScheme.onSurfaceVariant,
        disabledOnSurface = colorScheme.onSurface.copy(alpha = 0.38f),
        surfaceContainer = colorScheme.surfaceContainerLow,
        onSurfaceContainer = colorScheme.onSurface,
        onSurfaceContainerVariant = colorScheme.onSurfaceVariant,
        surfaceContainerHigh = colorScheme.surfaceContainerHigh,
        onSurfaceContainerHigh = colorScheme.onSurface,
        surfaceContainerHighest = colorScheme.surfaceContainerHighest,
        onSurfaceContainerHighest = colorScheme.onSurface,
        outline = colorScheme.outlineVariant,
        dividerLine = colorScheme.outlineVariant.copy(alpha = if (isDark) 0.50f else 0.65f),
        sliderBackground = colorScheme.surfaceContainerHighest,
        sliderKeyPoint = colorScheme.outline.copy(alpha = 0.45f),
        sliderKeyPointForeground = colorScheme.onPrimary.copy(alpha = 0.72f),
    )
}
