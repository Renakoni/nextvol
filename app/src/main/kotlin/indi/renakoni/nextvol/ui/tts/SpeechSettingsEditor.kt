package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.scopes.ViewModelScoped
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechSchemes
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

/** Routes own the edit target. Neither playback nor another editor can change it. */
@ViewModelScoped
class SpeechSettingsEditor @Inject constructor(private val repository: SpeechSettingsRepository,
    private val savedState: SavedStateHandle) {
    val bookId: String? = savedState["bookId"]
    private val followBook = savedState.get<Boolean>("followBook") == true && savedState.get<String>("schemeId") == null
    private fun target(schemes: SpeechSchemes) = if (followBook) schemes.effectiveId(bookId) else
        savedState.get<String>("schemeId") ?: schemes.effectiveId(bookId).also { savedState["schemeId"] = it }

    val changes = repository.schemes.mapNotNull { schemes ->
        val id = target(schemes)
        // A back-stack editor can outlive a scheme deleted from another book's settings.
        schemes.items.find { it.id == id }?.let { schemes.settings(id) }
    }.distinctUntilChanged()

    suspend fun get(): SpeechSettings = repository.snapshot().let { it.settings(target(it)) }

    suspend fun update(change: (SpeechSettings) -> SpeechSettings) = repository.update(
        savedState.get<String>("schemeId")?.takeUnless { followBook } ?: target(repository.snapshot()), change)
    suspend fun updateAll(change: (SpeechSettings) -> SpeechSettings) = repository.updateAll(change)
    suspend fun forBook(bookId: String?) = repository.forBook(bookId)
}
