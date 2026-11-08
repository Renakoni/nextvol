package indi.renakoni.nextvol.ui.book.reader

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry

@Composable
internal fun ReaderPageLayoutEntry(settings: ReaderSettingsEditor, modifier: Modifier = Modifier) {
    SettingsMenuEntry(
        modifier = modifier.testTag("reader-page-layout"),
        painter = painterResource(R.drawable.menu_book_24px),
        title = stringResource(R.string.reader_page_layout_title),
        description = stringResource(readerPageLayoutDescription(settings.isUsingFlipPage, LocalReaderLayoutResult.current?.value)),
        options = ReaderPageLayoutOptions,
        selectedOptionKey = settings.pageLayout,
        stringUserData = settings.pageLayoutUserData,
    )
}

@StringRes
internal fun readerPageLayoutDescription(paginated: Boolean, result: ReaderLayoutResult?): Int {
    if (!paginated || result?.reason == ReaderLayoutReason.ScrollMode) return R.string.reader_page_layout_scroll
    if (result?.geometry == null) return R.string.reader_page_layout_waiting
    return when (result.reason) {
        ReaderLayoutReason.AwaitingMeasurement -> R.string.reader_page_layout_waiting
        ReaderLayoutReason.ScrollMode -> R.string.reader_page_layout_scroll
        ReaderLayoutReason.UnsupportedContent -> R.string.reader_page_layout_unsupported
        ReaderLayoutReason.WindowTooNarrow -> R.string.reader_page_layout_narrow
        ReaderLayoutReason.WindowTooShort -> R.string.reader_page_layout_short
        ReaderLayoutReason.TextTooLarge -> R.string.reader_page_layout_large_text
        null -> if (result.geometry.columns == 2) R.string.reader_page_layout_current_double
            else R.string.reader_page_layout_current_single
    }
}
