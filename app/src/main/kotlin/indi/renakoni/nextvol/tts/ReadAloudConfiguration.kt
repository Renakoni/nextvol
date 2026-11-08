package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.logging.LoggerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class ReadAloudConfiguration @Inject constructor(
    private val settings: SpeechSettingsRepository,
    private val music: BackgroundMusicRepository,
    private val voices: SpeechVoiceCatalog,
    private val pairs: SpeechVoicePoolRepository,
    private val languages: SpeechLanguageDetection,
    private val logger: LoggerRepository,
) {
    suspend fun resolve(request: SpeechRequest): SpeechSettings =
        voices.revised(settings.forBook(request.bookId)).also { logger.speechDebugEnabled = it.diagnosticLogging }

    fun backgroundMusic(state: Flow<ReadAloudState>): Flow<BackgroundMusicSettings> =
        combine(music.changes, settings.schemes, state.map { it.request }.distinctUntilChanged()) { library, schemes, request ->
            // Follow live music edits in the audition's scheme; a deleted scheme retains its snapshot.
            val selected = request?.previewSettings?.let { preview ->
                schemes.items.find { it.id == preview.schemeId }?.settings?.backgroundMusic ?: preview.backgroundMusic
            } ?: schemes.forBook(request?.bookId).backgroundMusic
            library.forScheme(selected)
        }.distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun observeSpeech(state: StateFlow<ReadAloudState>, updateSettings: (SpeechSettings) -> Unit) {
        state.map { it.request?.takeUnless { it.isPreview }?.bookId }.distinctUntilChanged()
            .flatMapLatest { bookId ->
                combine(settings.changesForBook(bookId), voices.changes, pairs.revisions) { selected, _, _ ->
                    pairs.setCacheLimit(selected.maxCacheVoiceNum)
                    voices.revised(selected)
                }.distinctUntilChanged()
            }.collect { selected ->
                logger.speechDebugEnabled = selected.diagnosticLogging
                if (state.value.request?.bookId == selected.bookId && state.value.request?.isPreview == false) {
                    updateSettings(selected)
                }
                if (selected.roleRules.none { it.isLanguageRule }) languages.release()
            }
    }
}
