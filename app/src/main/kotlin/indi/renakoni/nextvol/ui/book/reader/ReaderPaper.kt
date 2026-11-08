package indi.renakoni.nextvol.ui.book.reader

import android.app.Activity
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle

internal data class ReaderPaperColors(
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val isDark: Boolean = false,
) {
    fun colorScheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = background,
        primaryContainer = lerp(background, accent, 0.12f), onPrimaryContainer = text,
        secondary = accent, onSecondary = background,
        secondaryContainer = lerp(background, accent, 0.12f), onSecondaryContainer = text,
        tertiary = accent, onTertiary = background,
        tertiaryContainer = lerp(background, accent, 0.12f), onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = background, onSurface = text,
        surfaceVariant = lerp(background, text, 0.06f), onSurfaceVariant = muted,
        surfaceTint = accent,
        surfaceDim = lerp(background, text, 0.08f), surfaceBright = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(background, text, 0.02f),
        surfaceContainer = lerp(background, text, 0.04f),
        surfaceContainerHigh = lerp(background, text, 0.06f),
        surfaceContainerHighest = lerp(background, text, 0.08f),
        outline = muted, outlineVariant = lerp(background, text, 0.25f),
        inverseSurface = text, inverseOnSurface = background, inversePrimary = background,
    )
}

/** Stable IDs persist only the paper choice; Default retains the existing reader preferences. */
internal enum class ReaderPaper(val id: String, @param:StringRes val label: Int, val colors: ReaderPaperColors? = null) {
    Default("default", R.string.reader_paper_default),
    Paper("paper", R.string.reader_paper_clear, ReaderPaperColors(Color(0xFFFAF9F6), Color(0xFF262521), Color(0xFF66645F), Color(0xFF545D48))),
    Sepia("sepia", R.string.reader_paper_warm, ReaderPaperColors(Color(0xFFF3EAD7), Color(0xFF373027), Color(0xFF635A4C), Color(0xFF77562E))),
    Sage("sage", R.string.reader_paper_sage, ReaderPaperColors(Color(0xFFDCE8D5), Color(0xFF263327), Color(0xFF51604E), Color(0xFF375F3E))),
    Night("night", R.string.reader_paper_night, ReaderPaperColors(Color(0xFF202322), Color(0xFFD8DDD6), Color(0xFFA4B0A5), Color(0xFFA8C0A2), true)),
    Mist("mist", R.string.reader_paper_mist, ReaderPaperColors(Color(0xFFE5E7E5), Color(0xFF292D2A), Color(0xFF525A53), Color(0xFF4F6055))),
    Black("black", R.string.reader_paper_black, ReaderPaperColors(Color.Black, Color(0xFFD4D4D4), Color(0xFF9B9F9B), Color(0xFFABC2AE), true)),
    Ink("inkwash", R.string.reader_paper_ink, ReaderPaperColors(Color(0xFFF2F0E8), Color(0xFF292A27), Color(0xFF5E5E57), Color(0xFF8E3C32)));

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: Default
    }
}

internal val ReaderSettings.usesBackgroundImage: Boolean
    get() = enableBackgroundImage && ReaderPaper.fromId(paperId) == ReaderPaper.Default

internal val LocalReaderSpeechHighlight = compositionLocalOf { Color.Unspecified }
internal val LocalReaderAppTheme = compositionLocalOf<AppTheme?> { null }

/** Keep the paper's accent quiet while protecting the existing text contrast. */
internal fun readerSpeechHighlight(background: Color, text: Color, accent: Color, image: Boolean = false): Color {
    // An image has no single surface color. A translucent paper backing stabilizes the marked text.
    fun contrast(surface: Color): Float {
        val a = text.luminance()
        val b = surface.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
    val paper = if (!image) background else {
        val neutral = listOf(Color(0xFFFAF9F6), Color(0xFF202322)).maxBy(::contrast)
        if (contrast(neutral) >= 4.5f) neutral else listOf(Color.White, Color.Black).maxBy(::contrast)
    }
    val tint = lerp(accent, text, 0.15f)
    val minimum = minOf(4.5f, contrast(paper))
    var alpha = if (paper.luminance() < 0.18f) 0.20f else 0.14f
    while (alpha > 0.01f && contrast(tint.copy(alpha = alpha).compositeOver(paper)) < minimum) alpha *= 0.75f
    val mark = tint.copy(alpha = if (alpha > 0.01f) alpha else 0f)
    if (!image) return mark
    val backing = mark.compositeOver(paper)
    var opacity = 0.9f
    while (opacity < 1f && minOf(contrast(backing.copy(alpha = opacity).compositeOver(Color.White)),
            contrast(backing.copy(alpha = opacity).compositeOver(Color.Black))) < 4.5f) {
        opacity = (opacity + 0.025f).coerceAtMost(1f)
    }
    return backing.copy(alpha = opacity)
}

/** Keep the same composition when switching papers so reader position and open controls survive. */
@Composable
internal fun ReaderPaperTheme(
    settings: ReaderSettings,
    manageSystemBars: Boolean = false,
    content: @Composable () -> Unit,
) {
    val appTheme = LocalReaderAppTheme.current ?: LocalAppTheme.current
    val colors = ReaderPaper.fromId(settings.paperId).colors
    val scheme = remember(colors) { colors?.colorScheme() } ?: MaterialTheme.colorScheme
    val theme = remember(colors, scheme, appTheme) {
        if (colors == null) appTheme else AppTheme(colors.isDark, scheme)
    }
    val style = LocalReaderStyle.current
    val readerStyle = remember(colors, style) {
        if (colors == null) style else style.copy(textColor = colors.text, textDarkColor = colors.text)
    }
    val background = readerBackgroundColor(settings)
    val text = readerTextColor(settings)
    val image = settings.usesBackgroundImage
    val speechHighlight = remember(background, text, scheme.primary, image) {
        readerSpeechHighlight(background, text, scheme.primary, image)
    }
    if (manageSystemBars) {
        val view = LocalView.current
        DisposableEffect(view, theme.isDark, appTheme.isDark) {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !theme.isDark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                controller.isAppearanceLightNavigationBars = !theme.isDark
            }
            onDispose {
                controller.isAppearanceLightStatusBars = !appTheme.isDark
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    controller.isAppearanceLightNavigationBars = !appTheme.isDark
                }
            }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalReaderAppTheme provides appTheme, LocalAppTheme provides theme, LocalReaderStyle provides readerStyle,
            LocalReaderSpeechHighlight provides speechHighlight, content = content)
    }
}
