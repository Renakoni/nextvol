package indi.renakoni.nextvol.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.book.BookIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeechAction { Start, Pause, Resume, Stop, Previous, Next, PreviousChapter, NextChapter, SleepTimer }

/** UI sends explicit commands. Merely observing this state never starts a service or an engine. */
@Singleton
class ReadAloudController @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutableState = MutableStateFlow(ReadAloudState())
    val state = mutableState.asStateFlow()
    private var closingPlayer = false

    fun start(bookId: String, chapterId: String) {
        val book = BookIdentity.book(bookId)
        val chapter = BookIdentity.chapter(chapterId, book)
        val current = state.value
        if (current.request?.bookId == book.storageKey && current.phase !in setOf(SpeechPhase.Stopped, SpeechPhase.Completed)) {
            // Reopening this book's controls preserves its playback intent; Resume is explicit.
            return
        }
        start(SpeechRequest(book.storageKey, chapter.storageKey))
    }

    fun preview(text: String) = start(SpeechRequest("", "", text))

    private fun start(request: SpeechRequest) {
        closingPlayer = false
        mutableState.value = ReadAloudState(request, SpeechPhase.Preparing)
        send(SpeechAction.Start, request)
    }

    fun command(action: SpeechAction) {
        if (action == SpeechAction.Resume) closingPlayer = false
        if (action == SpeechAction.Stop) {
            closingPlayer = true
            mutableState.value = state.value.copy(showFloatingPlayer = false, position = null)
        }
        send(action, state.value.request)
    }
    fun setSleepTimer(minutes: Int?) {
        require(minutes == null || minutes in 1..60)
        send(SpeechAction.SleepTimer, state.value.request, minutes)
    }
    internal fun publish(state: ReadAloudState) {
        val terminal = state.phase in setOf(SpeechPhase.Stopped, SpeechPhase.Completed)
        if (terminal) closingPlayer = false
        val previous = mutableState.value
        // Track eligibility where playback is published, even while the Activity is not collecting.
        val showPlayer = !terminal && !closingPlayer && state.request?.isPreview == false &&
            (state.phase == SpeechPhase.Playing ||
                previous.request?.bookId == state.request.bookId && previous.showFloatingPlayer)
        mutableState.value = state.copy(showFloatingPlayer = showPlayer,
            position = if (terminal || closingPlayer || state.request?.isPreview != false) null else state.position)
    }

    private fun send(action: SpeechAction, request: SpeechRequest?, timerMinutes: Int? = null) {
        if (request == null) return
        try {
            val intent = intent(context, action, request).putExtra(TIMER_MINUTES, timerMinutes ?: 0)
            if (action == SpeechAction.Start || action == SpeechAction.Resume) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        } catch (_: IllegalStateException) {
            mutableState.value = state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.ServiceUnavailable)
        } catch (_: SecurityException) {
            mutableState.value = state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.ServiceUnavailable)
        }
    }

    companion object {
        internal const val ACTION = "speech.action"
        internal const val REQUEST = "speech.request"
        internal const val TIMER_MINUTES = "speech.timerMinutes"
        const val OPEN_PLAYER = "indi.renakoni.nextvol.OPEN_READ_ALOUD"
        internal fun intent(context: Context, action: SpeechAction, request: SpeechRequest): Intent =
            Intent(context, ReadAloudService::class.java).putExtra(ACTION, action.name)
                .putExtra(REQUEST, Json.encodeToString(request))
    }
}
