package indi.renakoni.nextvol.ui.book.reader

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography

/** Units and validation belong to the reader, independently of storage and Compose controls. */
internal object ReaderLayoutValues {
    val fontSizeRange = 8f..64f
    val fontWeightRange = 100f..900f
    val lineSpacingRange = 0f..32f
    val paragraphSpacingRange = 0f..64f
    val marginRange = 0f..128f

    fun normalize(value: Float, range: ClosedFloatingPointRange<Float>, default: Float): Float =
        if (value.isFinite()) value.coerceIn(range) else default

    fun fontSize(value: Float) = normalize(value, fontSizeRange, 15f)
    fun fontWeight(value: Float) = normalize(value, fontWeightRange, 500f)
    fun lineSpacing(value: Float) = normalize(value, lineSpacingRange, 7f)
    fun paragraphSpacing(value: Float) = normalize(value, paragraphSpacingRange, 0f)
    fun margin(value: Float, default: Float) = normalize(value, marginRange, default)
}

internal data class ReaderLayoutSettings(
    val fontSize: Float,
    val fontWeight: Float,
    val lineSpacing: Float,
    val paragraphSpacing: Float,
    val fontUri: Uri,
    val autoPadding: Boolean,
    val top: Float,
    val bottom: Float,
    val start: Float,
    val end: Float,
) {
    init {
        require(fontSize in ReaderLayoutValues.fontSizeRange)
        require(fontWeight in ReaderLayoutValues.fontWeightRange)
        require(lineSpacing in ReaderLayoutValues.lineSpacingRange)
        require(paragraphSpacing in ReaderLayoutValues.paragraphSpacingRange)
        require(listOf(top, bottom, start, end).all { it in ReaderLayoutValues.marginRange })
    }

    fun textStyle(fontFamily: FontFamily?, locales: LocaleList): TextStyle =
        AppTypography.bodyMedium.copy(
            fontSize = fontSize.sp,
            fontWeight = FontWeight(fontWeight.toInt()),
            lineHeight = (fontSize + lineSpacing).sp,
            fontFamily = fontFamily ?: FontFamily.Default,
            localeList = locales,
            textAlign = TextAlign.Start,
            lineBreak = LineBreak.Simple,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            // A line has the same height at paragraph and page edges as in its interior.
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )

    companion object {
        fun from(settings: ReaderSettings) = with(ReaderLayoutValues) {
            ReaderLayoutSettings(
                fontSize(settings.fontSize), fontWeight(settings.fontWeigh),
                lineSpacing(settings.fontLineHeight), paragraphSpacing(settings.paragraphSpacing),
                settings.fontFamilyUri, settings.autoPadding,
                margin(settings.topPadding, 12f), margin(settings.bottomPadding, 12f),
                margin(settings.leftPadding, 16f), margin(settings.rightPadding, 16f),
            )
        }
    }
}

/** One resolved input is captured by rendering, preview and each pagination request. */
internal data class ReaderTextLayoutInput(
    val settings: ReaderLayoutSettings,
    val style: TextStyle,
    val measurer: TextMeasurer,
    val paragraphSpacingPx: Int,
)

internal val LocalReaderTextLayout = staticCompositionLocalOf<ReaderTextLayoutInput?> { null }

@Composable
internal fun rememberReaderTextLayout(settings: ReaderSettings): ReaderTextLayoutInput {
    val layout = ReaderLayoutSettings.from(settings)
    val font = remember(layout.fontUri) { loadReaderFontFamilySafe(layout.fontUri) }
    val locales = LocalTextLocaleList.current
    val measurer = rememberTextMeasurer()
    val spacing = with(LocalDensity.current) { layout.paragraphSpacing.sp.roundToPx() }
    return remember(layout, font, locales, measurer, spacing) {
        ReaderTextLayoutInput(layout, layout.textStyle(font, locales), measurer, spacing)
    }
}
