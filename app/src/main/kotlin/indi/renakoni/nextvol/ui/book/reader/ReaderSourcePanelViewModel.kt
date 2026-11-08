package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.content.LoginForm
import hnovel.content.LoginReadingContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.LoginAttempt
import indi.renakoni.nextvol.data.web.rules.SourceLoginService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** One reader navigation entry owns one panel. The snapshot never follows another book/chapter. */
@HiltViewModel
class ReaderSourcePanelViewModel @Inject internal constructor(
    private val login: SourceLoginService, private val registry: WebSourceRegistry,
    private val accounts: SourceSessionManager, private val refresh: ReadingPanelRefresh,
) : ViewModel() {
    var form: LoginForm? by mutableStateOf(null); private set
    var busy by mutableStateOf(false); private set
    var visible by mutableStateOf(false); private set
    var notice: Int? by mutableStateOf(null); private set
    var available by mutableStateOf(false); private set
    private var book: SourceBookId? = null
    private var chapter: SourceChapterId? = null
    private var attempt: LoginAttempt? = null
    private var runtime: SourceRuntime? = null
    private var job: Job? = null
    private var version = 0L
    private val foreground = ForegroundSourceRequest()
    fun setActive(value: Boolean, retainBrowser: Boolean = false) = foreground.setActive(value, retainBrowser)

    init {
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, _ -> sources }.collect { sources ->
                val listing = sources.firstOrNull { it.metadata.id == book?.sourceId }
                available = listing?.metadata?.let { it.id.namespace == "rules" && SourceCapability.Login in it.capabilities } == true
                attempt?.let { panel ->
                    if (listing == null || listing.metadata.revision != panel.revision ||
                        accounts.current(panel.source).generation != panel.generation || runtime?.isAvailable != true)
                        dismiss(R.string.reader_source_panel_expired)
                }
            }
        }
    }

    fun bind(bookId: String, chapterId: String?) {
        val next = BookIdentity.book(bookId)
        val nextChapter = chapterId?.takeIf { it.isNotBlank() }?.let { BookIdentity.chapter(it, next) }
        if (book != next || chapter != nextChapter) dismiss(if (visible) R.string.reader_source_panel_expired else null)
        book = next
        chapter = nextChapter
        available = registry.sources.value.any { it.metadata.id == next.sourceId &&
            it.metadata.id.namespace == "rules" && SourceCapability.Login in it.metadata.capabilities }
    }

    fun open(progress: Float) {
        if (!available || visible) return
        val capturedBook = book ?: return
        val capturedChapter = chapter
        val epoch = ++version
        visible = true; busy = true; notice = null
        job = viewModelScope.launch(foreground) {
            try {
                val resolved = registry.resolve(capturedBook.sourceId) as? SourceResolution.Ready ?: error("Source unavailable")
                val panel = login.begin(capturedBook.sourceId, reading = LoginReadingContext(
                    capturedBook.remoteId, capturedChapter?.remoteId, progress))
                if (epoch != version) { login.cancel(panel); return@launch }
                attempt = panel
                runtime = resolved.runtime
                check(resolved.runtime.isAvailable && resolved.runtime.metadata.revision == panel.revision &&
                    resolved.runtime.metadata.accountGeneration == panel.generation)
                val loaded = login.form(panel)
                if (epoch == version) form = loaded
            } catch (cancelled: CancellationException) {
                if (epoch == version) dismiss(R.string.reader_source_panel_cancelled)
                throw cancelled
            } catch (_: Exception) {
                if (epoch == version) dismiss(R.string.reader_source_panel_failed)
            } finally { if (epoch == version) busy = false }
        }
    }

    internal fun submit(values: Map<String, String>, action: String?, formId: String,
        longClick: Boolean = false, apply: suspend (String, String?, ReadingPanelUpdate) -> Unit) {
        if (busy || !visible) return
        val panel = attempt ?: return
        val capturedRuntime = runtime ?: return
        val capturedBook = book ?: return
        val capturedChapter = chapter
        val epoch = version
        busy = true; notice = null
        job = viewModelScope.launch(foreground) {
            var submitted = false
            try {
                val result = login.submit(panel, values, action, formId, longClick)
                submitted = true
                login.withAttempt(panel) {
                    val update = refresh.refresh(capturedBook, capturedChapter, capturedRuntime, result.refreshTargets)
                    if (update.isErr) {
                        if (epoch == version) notice = R.string.reader_source_panel_refresh_failed
                    } else if (epoch == version) {
                        apply(capturedBook.storageKey, capturedChapter?.storageKey, update.get()!!)
                        if (result.messages.isEmpty()) notice = R.string.reader_source_panel_completed
                    }
                }
                val updated = login.form(panel)
                if (epoch == version) form = updated
            } catch (cancelled: CancellationException) {
                if (epoch == version) dismiss(R.string.reader_source_panel_cancelled)
                throw cancelled
            } catch (_: Exception) {
                if (epoch == version) notice = if (submitted) R.string.reader_source_panel_refresh_failed else R.string.reader_source_panel_failed
            } finally { if (epoch == version) busy = false }
        }
    }

    fun clearNotice() { notice = null }

    fun dismiss(message: Int? = R.string.reader_source_panel_cancelled) {
        ++version
        job?.cancel(); job = null
        // close() revokes the panel ticket immediately; it never closes the shared source.
        attempt?.let { it.rules.close(); it.lifetime.cancel() }
        attempt = null; runtime = null; form = null
        visible = false; busy = false; notice = message
    }

    override fun onCleared() { dismiss(null); super.onCleared() }
}
