package indi.renakoni.nextvol.ui.book.reader.content.componet

import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.navigateToImageViewerDialog
import indi.renakoni.nextvol.ui.components.ZoomableImage
import indi.renakoni.nextvol.ui.book.reader.rememberReaderFontFamily
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import indi.renakoni.nextvol.ui.LocalReaderBookId

@Composable
internal fun ReaderTextContent(text: String, fontFamilyUriUserData: UriUserData, modifier: Modifier, paginate: Boolean = false) {
    val combinedStyle = LocalReaderStyle.current
    val layout = LocalReaderTextLayout.current
    if (layout != null) {
        BoxWithConstraints(modifier) {
            val height = if (paginate) constraints.maxHeight else Int.MAX_VALUE
            val fragments = remember(text, layout, constraints.maxWidth, height) {
                val pages = layoutReaderText(listOf(ReaderTextSource(0, text)), constraints.maxWidth, height,
                    layout.paragraphSpacingPx, layout.style, layout.measurer)
                if (paginate) pages.firstOrNull().orEmpty() else pages.flatten()
            }
            ReaderTextFragments(fragments, layout.style,
                readerTextColor(combinedStyle.textColor, combinedStyle.textDarkColor), Modifier)
        }
        return
    }
    SimpleTextComponentContent(
        modifier = modifier,
        text = text,
        fontSize = combinedStyle.fontSize.sp,
        fontLineHeight = combinedStyle.fontLineHeight.sp,
        fontWeight = FontWeight(combinedStyle.fontWeight.toInt()),
        fontFamily = rememberReaderFontFamily(fontFamilyUriUserData),
        color = readerTextColor(combinedStyle.textColor, combinedStyle.textDarkColor)
    )
}

@Composable
internal fun readerTextColor(textColor: Color, textDarkColor: Color): Color {
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val onSurface = localTheme.colorScheme.onSurface

    val color = remember(isDark, textColor, textDarkColor, onSurface) {
        when {
            isDark && textDarkColor.isUnspecified -> onSurface
            !isDark && textColor.isUnspecified -> onSurface
            isDark -> textDarkColor
            else -> textColor
        }
    }

    return color
}

internal val LocalReaderImageScale = staticCompositionLocalOf { ContentScale.FillWidth }

@Composable
internal fun ReaderImageContent(uri: Uri, modifier: Modifier) {
    val bookId = requireNotNull(LocalReaderBookId.current) { "Reader image has no book identity" }
    val chapterId = indi.renakoni.nextvol.ui.LocalReaderChapterId.current
    val navController = LocalNavController.current
    ZoomableImage(
        imageUri = uri,
        modifier = modifier.fillMaxSize(),
        onViewImage = {
            navController.navigateToImageViewerDialog(uri, bookId, chapterId = chapterId)
        },
        bookId = bookId,
        contentScale = LocalReaderImageScale.current,
        chapterId = chapterId
    )
}

@Composable
internal fun ReaderErrorContent(message: String) {
    Column {
        Text(stringResource(R.string.reader_content_error))
        Text(message)
    }
}
