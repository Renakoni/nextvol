package indi.renakoni.nextvol.ui.book.reader.bookmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import java.security.MessageDigest

internal data class ReaderBookmarkPosition(
    val bookId: String,
    val chapter: ChapterContentUiState,
    val anchor: ReaderContentAnchor,
    val progress: Float,
) {
    fun bookmark(): ReadingBookmark {
        val text = (chapter.content.getOrNull(anchor.componentIndex) as? SimpleTextComponent)?.data?.text
        return ReadingBookmark(bookId = bookId, chapterId = chapter.id, chapterTitle = chapter.title,
            componentIndex = anchor.componentIndex, offset = anchor.offset,
            fingerprint = chapter.bookmarkFingerprint,
            preview = text?.drop(anchor.offset)?.take(160)?.replace('\n', ' ')?.trim().orEmpty(),
            progress = progress.coerceIn(0f, 1f))
    }
}

internal fun ChapterContentUiState.computeBookmarkFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    content.forEach { component ->
        // Imported image paths change with the installation directory. Their archive asset names do not.
        val data = component.data
        val value = if (data is ImageComponentData && data.uri.scheme == "file" && "/assets/" in data.uri.path.orEmpty())
            data.uri.path.orEmpty().substringAfterLast("/assets/") else data.toJsonElement().toString()
        digest.update(data.id.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun ReadingBookmark.anchorIn(chapter: ChapterContentUiState): ReaderContentAnchor? {
    if (chapterId != chapter.id || fingerprint != chapter.bookmarkFingerprint) return null
    val component = chapter.content.getOrNull(componentIndex) ?: return null
    if (component is SimpleTextComponent && offset !in component.data.text.indices) return null
    return ReaderContentAnchor(componentIndex, offset)
}

internal class ReaderBookmarkSession {
    var capture by mutableStateOf<(() -> ReaderBookmarkPosition?)?>(null)
    var pending by mutableStateOf<ReadingBookmark?>(null)
    var notice by mutableStateOf<Int?>(null)

    fun finish(bookmark: ReadingBookmark, positioned: Boolean) {
        if (pending !== bookmark) return
        pending = null
        if (!positioned) notice = R.string.reader_bookmarks_changed
    }
}

internal val LocalReaderBookmarks = compositionLocalOf<ReaderBookmarkSession?> { null }

/** The active renderer owns this callback; old/disposed renderers cannot clear a newer one. */
@Composable
internal fun RegisterBookmarkCapture(capture: () -> ReaderBookmarkPosition?) {
    val session = LocalReaderBookmarks.current
    val active by rememberUpdatedState(indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive.current)
    val current by rememberUpdatedState(capture)
    DisposableEffect(session, active) {
        val callback = { if (active) current() else null }
        if (active) session?.capture = callback
        onDispose { if (session?.capture === callback) session.capture = null }
    }
}
