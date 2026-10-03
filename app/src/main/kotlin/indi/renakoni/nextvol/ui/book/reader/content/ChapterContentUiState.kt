package indi.renakoni.nextvol.ui.book.reader.content

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.ui.book.reader.bookmark.computeBookmarkFingerprint
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.tts.SpeechTextIndex
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Stable
class ChapterContentUiState(
    val id: String,
    val title: String,
    val content: List<AbstractContentComponent<*>>,
    val prevChapter: String?,
    val nextChapter: String?,
    // Exact processed source, scoped to its book, for reuse by the active scroll window.
    internal val source: Pair<String, ChapterContent>? = null,
) {
    internal val bookmarkFingerprint by lazy { computeBookmarkFingerprint() }

    private val speechIndexMutex = Mutex()
    internal var speechTextIndex by mutableStateOf<SpeechTextIndex?>(null)
        private set

    /** Only explicit speech consumers prepare this index; synchronous UI reads never build it. */
    internal suspend fun prepareSpeechTextIndex(): SpeechTextIndex = speechIndexMutex.withLock {
        speechTextIndex ?: withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            SpeechTextIndex(content.mapIndexedNotNull { index, component ->
                context.ensureActive()
                (component as? SimpleTextComponent)?.let { index to it.data.text }
            })
        }.also { speechTextIndex = it }
    }

    fun hasPrevChapter(): Boolean = prevChapter != null

    fun hasNextChapter(): Boolean = nextChapter != null
}
