package indi.renakoni.nextvol.tts

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadAloudConfigurationTest {
    @Test fun previewMusicFollowsLiveSchemeEditsFallsBackAfterDeletionAndReturnsToTheBook() = runTest {
        val rain = BackgroundMusicTrack("rain", "Rain")
        val piano = BackgroundMusicTrack("piano", "Piano")
        val library = MutableStateFlow(BackgroundMusicSettings(listOf(rain, piano)))
        val bookMusic = SpeechSchemeMusic(true, setOf(rain.id), 0.2f)
        val previewMusic = SpeechSchemeMusic(true, setOf(piano.id), 0.4f)
        val schemes = MutableStateFlow(SpeechSchemes(items = listOf(
            SpeechScheme(DEFAULT_SPEECH_SCHEME),
            SpeechScheme("book-scheme", settings = SpeechSettings(backgroundMusic = bookMusic)),
            SpeechScheme("preview-scheme", settings = SpeechSettings(backgroundMusic = previewMusic)),
        ), bindings = mapOf("book" to "book-scheme")))
        val settings = mockk<SpeechSettingsRepository>()
        val music = mockk<BackgroundMusicRepository>()
        every { settings.schemes } returns schemes
        every { music.changes } returns library
        val configuration = ReadAloudConfiguration(settings, music, mockk(), mockk(), mockk(), mockk())
        val book = SpeechRequest("book", "chapter")
        val state = MutableStateFlow(ReadAloudState(book, SpeechPhase.Playing))
        val received = mutableListOf<BackgroundMusicSettings>()
        backgroundScope.launch { configuration.backgroundMusic(state).collect { received += it } }
        runCurrent()

        state.value = ReadAloudState(SpeechRequest("", "", "Sample", schemes.value.settings("preview-scheme")), SpeechPhase.Playing)
        runCurrent()
        schemes.value = schemes.value.copy(items = schemes.value.items.map { scheme ->
            if (scheme.id == "preview-scheme") scheme.copy(settings = scheme.settings.copy(
                backgroundMusic = SpeechSchemeMusic(true, setOf(rain.id), 0.7f))) else scheme
        })
        runCurrent()
        schemes.value = schemes.value.copy(items = schemes.value.items.filterNot { it.id == "preview-scheme" })
        runCurrent()
        state.value = ReadAloudState(SpeechRequest("", "", "Sample", schemes.value.settings(DEFAULT_SPEECH_SCHEME)), SpeechPhase.Playing)
        runCurrent()
        state.value = ReadAloudState(book, SpeechPhase.Playing)
        runCurrent()

        val expectedBook = BackgroundMusicSettings(listOf(rain.copy(selected = true), piano), 0.2f, true)
        val expectedPreview = BackgroundMusicSettings(listOf(rain, piano.copy(selected = true)), 0.4f, true)
        assertEquals(listOf(expectedBook, expectedPreview, expectedBook.copy(volume = 0.7f), expectedPreview,
            BackgroundMusicSettings(listOf(rain, piano)), expectedBook), received)
    }
}
