package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.ui.unit.IntSize
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import kotlin.math.min
import kotlin.math.roundToInt

private const val AUTO_DOUBLE_MIN_WIDTH_DP = 840
private const val DOUBLE_MIN_HEIGHT_DP = 480
private const val DOUBLE_MIN_LEAF_WIDTH_DP = 320
private const val DOUBLE_MIN_LEAF_WIDTH_EM = 20
private const val DOUBLE_MIN_LINES = 8
private const val DOUBLE_GUTTER_DP = 24
private const val SCROLL_MAX_WIDTH_DP = 720

/** Resolved readerPadding, including applicable insets and the indicator, in whole pixels. */
internal data class ReaderPaddingPx(
    val startPx: Int,
    val endPx: Int,
    val topPx: Int = 0,
    val bottomPx: Int = 0,
)

/** The outer host size is before padding; text sizes are resolved with the current text style/density. */
internal data class ReaderLayoutInput(
    val hostSize: IntSize,
    val density: Float,
    val padding: ReaderPaddingPx,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val mode: ReaderMode,
    val preference: String = ReaderPageLayoutPreference.Auto,
    // Only validated chapter components opt in; unknown content stays single-page.
    val supportsDoublePage: Boolean = false,
)

/** Logical-start coordinates; both leaves share leafSize and receive no further reader padding. */
internal data class ReaderBodyGeometry(
    val startPx: Int,
    val topPx: Int,
    val leafSize: IntSize,
    val columns: Int = 1,
    val gutterPx: Int = 0,
) {
    val widthPx: Int get() = leafSize.width * columns + gutterPx
}

internal enum class ReaderLayoutReason {
    AwaitingMeasurement, ScrollMode, UnsupportedContent, WindowTooNarrow, WindowTooShort, TextTooLarge,
}

internal data class ReaderLayoutResult(
    // No geometry means wait for measurement, not an empty page or chapter end.
    val geometry: ReaderBodyGeometry?,
    val reason: ReaderLayoutReason? = null,
)

/** Unknown preference values behave like auto; this function never changes the stored preference. */
internal fun resolveReaderLayout(input: ReaderLayoutInput): ReaderLayoutResult = with(input) {
    val usableWidth = hostSize.width - padding.startPx - padding.endPx
    val usableHeight = hostSize.height - padding.topPx - padding.bottomPx
    if (hostSize.width <= 0 || hostSize.height <= 0 || usableWidth <= 0 || usableHeight <= 0) {
        return ReaderLayoutResult(null, ReaderLayoutReason.AwaitingMeasurement)
    }
    val single = ReaderBodyGeometry(padding.startPx, padding.topPx, IntSize(usableWidth, usableHeight))
    if (mode == ReaderMode.Scroll) {
        val width = min(usableWidth, (SCROLL_MAX_WIDTH_DP * density).roundToInt())
        return ReaderLayoutResult(
            single.copy(
                startPx = padding.startPx + (usableWidth - width) / 2,
                leafSize = IntSize(width, usableHeight),
            ),
            ReaderLayoutReason.ScrollMode,
        )
    }
    if (preference == ReaderPageLayoutPreference.Single) return ReaderLayoutResult(single)
    if (!supportsDoublePage) return ReaderLayoutResult(single, ReaderLayoutReason.UnsupportedContent)

    val gutter = (DOUBLE_GUTTER_DP * density).roundToInt()
    val leafWidth = (usableWidth - gutter) / 2
    val reason = when {
        hostSize.height < DOUBLE_MIN_HEIGHT_DP * density -> ReaderLayoutReason.WindowTooShort
        preference != ReaderPageLayoutPreference.Double && hostSize.width < AUTO_DOUBLE_MIN_WIDTH_DP * density ->
            ReaderLayoutReason.WindowTooNarrow
        leafWidth < DOUBLE_MIN_LEAF_WIDTH_DP * density -> ReaderLayoutReason.WindowTooNarrow
        leafWidth < DOUBLE_MIN_LEAF_WIDTH_EM * fontSizePx -> ReaderLayoutReason.TextTooLarge
        usableHeight < DOUBLE_MIN_LINES * lineHeightPx -> ReaderLayoutReason.WindowTooShort
        else -> null
    }
    if (reason != null) return ReaderLayoutResult(single, reason)
    ReaderLayoutResult(single.copy(
        leafSize = IntSize(leafWidth, usableHeight),
        columns = 2,
        // Rounding leaves both bodies equal; the spare pixel belongs to the gutter.
        gutterPx = usableWidth - 2 * leafWidth,
    ))
}
