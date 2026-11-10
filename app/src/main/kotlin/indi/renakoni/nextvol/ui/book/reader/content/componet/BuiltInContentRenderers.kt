package indi.renakoni.nextvol.ui.book.reader.content.componet

import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.size.Dimension
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageSize
import indi.renakoni.nextvol.data.image.SourceImageSizes
import indi.renakoni.nextvol.ui.book.reader.content.ReaderImageResource
import indi.renakoni.nextvol.ui.book.reader.content.ReaderImageWindow
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderImageWindow
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderChapterImages
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderImageResource
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderImageEnabled
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderImageVisible
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderImageResize
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive
import indi.renakoni.nextvol.ui.components.Loading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
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
    val resource = LocalReaderImageResource.current
        ?: LocalReaderChapterImages.current.values.firstOrNull { it.uri == uri }
        ?: remember(uri) { ReaderImageResource(uri) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resize by rememberUpdatedState(LocalReaderImageResize.current)
    val scale = LocalReaderImageScale.current
    WindowedReaderImage(resource, modifier, scale) { size, finished -> ZoomableImage(
        imageUri = uri,
        modifier = Modifier.fillMaxSize(),
        onViewImage = {
            navController.navigateToImageViewerDialog(uri, bookId, chapterId = chapterId)
        },
        bookId = bookId,
        contentScale = scale,
        chapterId = chapterId,
        requestSize = size,
        onResult = { result ->
            finished()
            if (result is SuccessResult) {
                val dimensions = SourceImageSize(result.image.width, result.image.height)
                if (dimensions.width > 0 && dimensions.height > 0) {
                    // Local/downloaded files also acquire a reusable layout hint on first display.
                    val image = SourceImage(BookIdentity.book(bookId), uri.toString(), chapterId = chapterId)
                    val sizes = SourceImageSizes(context)
                    if (sizes.get(image) == null) sizes.put(image, dimensions)
                    if (resource.size?.let { it.width.toLong() * dimensions.height == it.height.toLong() * dimensions.width } != true)
                        scope.launch { resize(resource, dimensions) }
                }
            }
        },
    ) }
}

/** A neighbour retains the very same request/painter when it becomes visible. */
@Composable
internal fun WindowedReaderImage(
    resource: ReaderImageResource,
    modifier: Modifier,
    scale: ContentScale = ContentScale.FillWidth,
    content: @Composable (Size, () -> Unit) -> Unit,
) {
    val window = LocalReaderImageWindow.current ?: remember { ReaderImageWindow() }
    val enabled = LocalReaderImageEnabled.current && LocalReaderRendererActive.current
    val visible by rememberUpdatedState(LocalReaderImageVisible.current)
    val finished = remember(window, resource, enabled) { CompletableDeferred<Unit>() }
    var admitted by remember(window, resource, enabled) { mutableStateOf(false) }
    LaunchedEffect(window, resource, enabled) {
        if (!enabled) return@LaunchedEffect
        // Current images enter the two load slots before newly composed neighbours.
        if (!visible) withFrameNanos { }
        window.permits.withPermit {
            admitted = true
            finished.await()
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val size = if (scale == ContentScale.FillWidth) Size(Dimension(constraints.maxWidth), Dimension.Undefined)
            else Size(constraints.maxWidth, constraints.maxHeight)
        val height = if (scale == ContentScale.FillWidth)
            resource.size?.let { maxOf(200.dp, maxWidth * (it.height.toFloat() / it.width)) } ?: 200.dp else maxHeight
        Box(Modifier.fillMaxWidth().height(height)) {
            if (admitted) content(size) { finished.complete(Unit) }
            else if (enabled) Loading()
        }
    }
}

@Composable
internal fun ReaderErrorContent(message: String) {
    Column {
        Text(stringResource(R.string.reader_content_error))
        Text(message)
    }
}
