package indi.renakoni.nextvol.ui.book.reader.content

import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.data.image.SourceImageSize

/** Geometry belongs to the mapped chapter; pixels remain owned by Coil and visible painters. */
internal class ReaderImageResource(val uri: Uri) {
    var size by mutableStateOf<SourceImageSize?>(null)
}

internal val LocalReaderChapterImages = compositionLocalOf { emptyMap<Int, ReaderImageResource>() }
internal val LocalReaderImageResource = compositionLocalOf<ReaderImageResource?> { null }
internal val LocalReaderImageEnabled = compositionLocalOf { true }
internal val LocalReaderImageVisible = compositionLocalOf { true }
internal val LocalReaderImageResize = compositionLocalOf<suspend (ReaderImageResource, SourceImageSize) -> Unit> {
    { resource, size -> resource.size = size }
}

/** The viewport and one image on either side, never the whole chapter's composed Column. */
internal fun <T> readerImageWindow(images: List<Triple<T, Int, Int>>, top: Int, bottom: Int): Map<T, Boolean> {
    if (bottom <= top) return emptyMap()
    return buildMap {
        images.filter { it.second < bottom && it.third > top }.forEach { put(it.first, true) }
        images.lastOrNull { it.third <= top }?.let { put(it.first, false) }
        images.firstOrNull { it.second >= bottom }?.let { put(it.first, false) }
    }
}
