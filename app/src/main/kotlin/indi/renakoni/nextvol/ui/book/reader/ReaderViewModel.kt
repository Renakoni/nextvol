package indi.renakoni.nextvol.ui.book.reader

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.reading.RepositoryReaderRecordStore
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.tts.ReadAloudController
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeFactory
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeHost
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPositionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    statsRepository: StatsRepository,
    private val chapterSource: ChapterSource,
    private val readingData: BookReadingDataAccess,
    userDataRepository: UserDataRepository,
    private val modeFactory: ReaderModeFactory,
    val readAloud: ReadAloudController,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val foreground = indi.renakoni.nextvol.data.web.ForegroundSourceRequest()
    private val readerScope = CoroutineScope(viewModelScope.coroutineContext + foreground)
    fun setActive(value: Boolean, retainBrowser: Boolean = false) {
        if (!value) positions.captureNow()
        foreground.setActive(value, retainBrowser)
    }
    internal val positions = ReaderPositionSession(savedStateHandle)
    private val settingState = SettingState(userDataRepository, viewModelScope)
    val readerSettings: ReaderSettingsEditor = settingState
    val fontFamilySettings: ReaderFontFamilySettings = settingState.fontFamilySettings
    private val modeHost: ReaderModeHost = ReaderModeHost(positions) { mode ->
        modeFactory.create(mode, readerScope, settingState.continuousScrollSettings, ::saveReadingProgress)
    }
    private val _uiState = MutableReaderScreenUiState(modeHost.uiState)
    val uiState: ReaderScreenUiState = _uiState
    private val statisticsScope = CoroutineScope(Dispatchers.IO)
    private val catalogChapterIdsByBook = ConcurrentHashMap<String, Set<String>>()
    private val readingRecords = ReaderReadingRecords(
        store = RepositoryReaderRecordStore(readingData, statsRepository, userDataRepository),
        scope = viewModelScope,
        statisticsScope = statisticsScope,
        currentBookId = { bookId },
        currentChapterTitle = {
            _uiState.contentUiState?.readingChapterContent?.map { it.title }?.getOrElse { null }
        },
        catalogChapterIds = { id -> catalogChapterIdsByBook[id] },
    )
    private var bookVolumesJob: Job? = null
    private var bookVolumesRequest = 0L
    @set:MainThread
    var bookId = ""
        set(value) {
            field = value
            _uiState.bookId = value
            modeHost.changeBookId(value)
            readingRecords.openBook(value)

            bookVolumesJob?.cancel()
            val request = ++bookVolumesRequest
            _uiState.bookVolumes = null
            bookVolumesJob = viewModelScope.launch(Dispatchers.IO + foreground) {
                chapterSource.getBookVolumesFlow(value).collect {
                    withContext(Dispatchers.Main.immediate) {
                        if (request == bookVolumesRequest) {
                            // Errors may expose a partial directory to the UI. Keep only the
                            // last successful catalog for completion, including a confirmed empty one.
                            it.map { volumes ->
                                catalogChapterIdsByBook[value] = volumes.volumes
                                    .flatMap { volume -> volume.chapters }.map { chapter -> chapter.id }.toSet()
                            }
                            _uiState.bookVolumes = it
                        }
                    }
                }
            }
    }
    private var chapterId = ""
    private var lastModeChapterId: String? = null

    /** A restored route can reconstruct the session; returning from dialogs keeps progress. */
    fun openBook(id: String, chapter: String) {
        if (bookId == id && chapterId.isNotEmpty()) return
        val checkpoint = positions.checkpoint?.takeIf { it.bookId == id }
        bookId = id
        changeChapter(checkpoint?.chapterId ?: chapter)
        if (checkpoint != null) positions.restore(checkpoint)
    }

    init {
        viewModelScope.launch {
            settingState.isUsingFlipPageUserData.getFlowWithDefault(false).collect { flip ->
                val mode = if (flip) ReaderMode.Flip else ReaderMode.Scroll
                if (modeHost.select(mode, { bookId }, ::currentChapterIdForModeSwitch)) {
                    _uiState.contentUiState = modeHost.uiState
                }
            }
        }
    }

    fun prevChapter() = modeHost.loadPrevChapter()

    fun nextChapter() = modeHost.loadNextChapter()

    internal suspend fun applySourcePanelRefresh(book: String, chapter: String?, update: indi.renakoni.nextvol.data.book.ReadingPanelUpdate) {
        if (indi.renakoni.nextvol.data.book.BookIdentity.bookKey(bookId) != book ||
            chapter != uiState.contentUiState?.readingChapterId) return
        if (update.contentChanged && chapter != null) {
            modeHost.flushProgress()
            readingRecords.awaitProgress()
            if (indi.renakoni.nextvol.data.book.BookIdentity.bookKey(bookId) != book ||
                chapter != uiState.contentUiState?.readingChapterId) return
        }
        update.volumes?.let { volumes ->
            ++bookVolumesRequest
            bookVolumesJob?.cancel()
            _uiState.bookVolumes = com.github.michaelbull.result.Ok(volumes)
            catalogChapterIdsByBook[bookId] = volumes.volumes
                .flatMap { it.chapters }.map { it.id }.toSet()
        }
        if (update.contentChanged && chapter != null) modeHost.changeChapter(chapter)
    }

    fun startReadAloud() {
        val chapter = currentChapterIdForModeSwitch()
        if (bookId.isNotBlank() && chapter.isNotBlank()) readAloud.start(bookId, chapter)
    }

    fun changeChapter(chapterId: String) {
        this.chapterId = chapterId
        lastModeChapterId = chapterId
        modeHost.changeChapter(chapterId)
    }

    private fun currentChapterIdForModeSwitch(): String {
        val requestedChapterId = modeHost.requestedChapterId
            ?.takeIf { it.isNotBlank() }
        if (requestedChapterId != null) {
            lastModeChapterId = requestedChapterId
            return requestedChapterId
        }
        val displayedChapterId = _uiState.contentUiState?.readingChapterId
            ?.takeIf { it.isNotBlank() }
        if (displayedChapterId != null) {
            lastModeChapterId = displayedChapterId
        }
        return displayedChapterId ?: lastModeChapterId ?: chapterId
    }

    private fun saveReadingProgress(chapterId: String, progress: Float) {
        val content = _uiState.contentUiState ?: return
        val chapter = content.readingChapterContent?.get() ?: return
        if (content.readingChapterId != chapterId) return
        val book = content.bookId
        readingRecords.saveProgress(chapterId, progress) {
            _uiState.contentUiState === content && content.bookId == book &&
                content.readingChapterId == chapterId && content.readingChapterContent?.get() === chapter
        }
    }

    fun updateTotalReadingTime(bookId: String, totalReadingTime: Int) =
        readingRecords.updateTotalReadingTime(bookId, totalReadingTime)

    fun accumulateReadingTime(bookId: String, seconds: Int) =
        readingRecords.accumulateReadingTime(bookId, seconds)

    override fun onCleared() {
        bookVolumesRequest++
        modeHost.close()
        bookVolumesJob?.cancel()
        super.onCleared()
    }
}
