package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.ui.book.reader.ReaderTopBar
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalMaterial3Api::class)
class SpeechControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sleepTimerCanBeSetAndCancelledOnANarrowLargeTextScreen() {
        checkSleepTimerMenu("Sleep timer", "10 min", "Off", "Pause", "Stop")
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sleepTimerUsesChineseLabelsAndKeepsPlaybackControlsReachable() {
        checkSleepTimerMenu("定时关闭", "10分钟", "关闭定时", "暂停", "停止")
    }

    private fun checkSleepTimerMenu(title: String, minutes: String, off: String, pause: String, stop: String) {
        val selected = mutableListOf<Int?>()
        val playback = androidx.compose.runtime.mutableStateOf(ReadAloudState(SpeechRequest("book", "one"), SpeechPhase.Playing,
            bookTitle = "Book", nextChapterId = "two"))
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(typography = AppTypography) {
                    ReadAloudControls(playback.value, {}, {},
                        Modifier.width(320.dp).padding(horizontal = 24.dp), onSleepTimer = {
                            selected += it
                            playback.value = playback.value.copy(sleepTimerDeadline = it?.let { minutes ->
                                android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                            })
                        })
                }
            }
        }
        compose.onNodeWithContentDescription(pause).assertIsDisplayed()
        compose.onNodeWithText(stop).assertIsDisplayed()
        compose.onNodeWithContentDescription(title).assertIsDisplayed().performClick()
        compose.onNodeWithText(minutes).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(title).performClick()
        compose.onNodeWithContentDescription(off).assertIsDisplayed().performClick()
        assertEquals(listOf(10, null), selected)
    }

    @Test fun previewDoesNotOfferASleepTimerAndFinishedPlaybackCannotSetOne() {
        val state = androidx.compose.runtime.mutableStateOf(ReadAloudState(SpeechRequest("", "", "Preview"), SpeechPhase.Playing))
        activity.get().setContent { MaterialTheme { ReadAloudControls(state.value, {}, onSleepTimer = {}) } }
        compose.onNodeWithContentDescription("Sleep timer").assertDoesNotExist()
        compose.runOnIdle { state.value = ReadAloudState(SpeechRequest("book", "one"), SpeechPhase.Completed) }
        compose.onNodeWithContentDescription("Sleep timer").assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longReaderTitleLeavesAFixedAccessibleListeningActionOnNarrowLargeTextScreens() {
        var starts = 0
        val title = "A very long chapter title with extra words ".repeat(5)
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(typography = AppTypography) {
                    Column(Modifier.width(320.dp)) {
                        ReaderTopBar({}, title, TopAppBarDefaults.pinnedScrollBehavior(), { starts++ })
                    }
                }
            }
        }
        val button = compose.onNodeWithTag("reader-read-aloud")
        button.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        val label = compose.onNodeWithText(title)
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Long title must be ellipsized within ${layouts.single().size}", layouts.single().isLineEllipsized(0))
        assertTrue(label.fetchSemanticsNode().boundsInRoot.right <= button.fetchSemanticsNode().boundsInRoot.left)
        assertEquals(1, starts)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun openingABookWithALongChapterTitleShowsThePrimaryControls() {
        val state = ReadAloudState(
            SpeechRequest("book", "chapter"), SpeechPhase.Playing,
            bookTitle = "NextVol-TTS-acceptance",
            chapterTitle = "A long chapter title that describes a quiet morning after the rain ".repeat(3),
            voiceLabel = "eSpeak Chinese", segmentCount = 2,
            currentText = "The rain has stopped and the morning light falls across the quiet street. ".repeat(3),
        )
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) { ReadAloudSheet(state, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test fun pausedControlsOfferResumeWithoutRepeatingTheStatusAsText() {
        val actions = mutableListOf<SpeechAction>()
        activity.get().setContent {
            MaterialTheme {
                ReadAloudControls(ReadAloudState(SpeechRequest("book", "chapter"), SpeechPhase.Paused), { actions += it })
            }
        }
        compose.onNodeWithText("Paused").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume").assertIsDisplayed().performClick()
        assertEquals(listOf(SpeechAction.Resume), actions)
    }

    @Test fun loadingCanBePausedAndFailedPlaybackCanRetry() {
        val actions = mutableListOf<SpeechAction>()
        val state = androidx.compose.runtime.mutableStateOf(ReadAloudState(SpeechRequest("book", "chapter"), SpeechPhase.Preparing))
        activity.get().setContent {
            MaterialTheme { ReadAloudControls(state.value, { actions += it }) }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle { state.value = state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.NoEngine) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Retry").performClick()
        assertEquals(listOf(SpeechAction.Pause, SpeechAction.Resume), actions)
        compose.onNodeWithContentDescription("Next passage").assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w640dp-h360dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aLongErrorDoesNotPushRetryAndStopOffALandscapeScreen() {
        val state = ReadAloudState(
            SpeechRequest("book", "chapter"), SpeechPhase.Failed,
            bookTitle = "A long book title ".repeat(8), chapterTitle = "A long chapter title ".repeat(8),
            previousChapterId = "previous", nextChapterId = "next", error = SpeechError.NoEngine,
        )
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme(typography = AppTypography) { ReadAloudSheet(state, {}, {}, {}) }
            }
        }
        compose.onNodeWithContentDescription("Retry").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h480dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun settingsKeepPauseReachableAndOfferStopInTheExpandedPlayerWithLargeText() {
        val actions = mutableListOf<SpeechAction>()
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(typography = AppTypography) {
                    SpeechSettingsScreen(
                        SpeechSettingsUiState(loading = false),
                        ReadAloudState(SpeechRequest("book", "chapter"), SpeechPhase.Playing,
                            bookTitle = "A long book title ".repeat(8)),
                        {}, onSources = {}, onPreferences = {}, onCommand = { actions += it },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        compose.onNodeWithTag("speech-settings-list").performScrollToNode(hasText("Playback settings"))
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithText("A long book title ".repeat(8)).performClick()
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(listOf(SpeechAction.Pause, SpeechAction.Stop), actions)
    }

    @Test fun playbackSpeedIsPrimaryAndSynthesisRateKeepsItsIndependentDefault() {
        val state = androidx.compose.runtime.mutableStateOf(SpeechSettingsUiState(loading = false))
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) {
                SpeechPreferencesScreen(state.value, ReadAloudState(),
                    {},
                    onRate = { state.value = state.value.copy(settings = state.value.settings.copy(rate = it)) },
                    onPitch = {}, onCommand = {},
                    onPlaybackSpeed = { state.value = state.value.copy(settings = state.value.settings.copy(playbackSpeed = it)) })
            }
        }
        val list = compose.onNodeWithTag("speech-preferences-list")
        list.performScrollToNode(hasContentDescription("Playback speed"))
        compose.onNodeWithContentDescription("Playback speed").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.runOnIdle {
            assertEquals(2f, state.value.settings.playbackSpeed)
            assertNull(state.value.settings.rate)
        }
        compose.onNodeWithContentDescription("Synthesis rate").assertDoesNotExist()
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(1f, state.value.settings.playbackSpeed) }
        list.performScrollToNode(hasText("Advanced settings"))
        compose.onNodeWithText("Advanced settings").performClick()
        list.performScrollToNode(hasContentDescription("Synthesis rate"))
        compose.onNodeWithContentDescription("Synthesis rate").performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        compose.runOnIdle {
            assertEquals(1.5f, state.value.settings.rate)
            assertEquals(1f, state.value.settings.playbackSpeed)
            assertNull(state.value.settings.pitch)
        }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertNull(state.value.settings.rate) }
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h900dp")
    fun refreshingTheSameMissingEngineKeepsTheErrorAndDisablesVoiceSelection() {
        val repository = mockk<SpeechSettingsRepository>()
        every { repository.changes } returns MutableStateFlow(SpeechSettings())
        val engines = mockk<SystemSpeechEngines>()
        coEvery { engines.installed() } returns emptyList()
        coEvery { engines.inspect("") } throws SpeechException(SpeechError.NoEngine)
        val model = SpeechSettingsViewModel(repository, engines, mockk(), mockk())
        val inspection = CompletableDeferred<SpeechEngineState>()
        fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
        try {
            activity.get().setContent { MaterialTheme {
                val state by model.state.collectAsState()
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(), {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _, _, _ -> },
                    onRefresh = model::refresh,
                    systemSettings = { SystemSpeechSettings(state, {}, {}, {}) })
            } }
            waitUntil { !model.state.value.loading }
            val error = activity.get().getString(SpeechError.NoEngine.messageResource)
            compose.onNodeWithText(error).assertIsDisplayed()
            coEvery { engines.inspect("") } coAnswers { inspection.await() }
            compose.onNodeWithContentDescription(activity.get().getString(indi.renakoni.nextvol.R.string.tts_refresh)).performClick()
            waitUntil { model.state.value.loading }
            compose.onNodeWithText(error).assertIsDisplayed()
            compose.onNodeWithText("Voice").assertIsNotEnabled()
            inspection.completeExceptionally(SpeechException(SpeechError.NoEngine))
            waitUntil { !model.state.value.loading }
            compose.onNodeWithText(error).assertIsDisplayed()
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun systemVoicePreviewLeavesTheSelectionDialogOpenUntilAChoiceIsMade() {
        var selected: String? = null
        var previewed: String? = null
        activity.get().setContent {
            MaterialTheme {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(), {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _, _, _ -> },
                    systemSettings = {
                        SystemSpeechSettings(SpeechSettingsUiState(loading = false,
                            voices = listOf(SpeechVoice("candidate", "en-US", false, true))), {}, { selected = it }, {},
                            onPreviewVoice = { previewed = it })
                    })
            }
        }
        compose.onNodeWithText("Voice").performClick()
        compose.onNodeWithContentDescription("Preview candidate").performClick()
        assertEquals("candidate", previewed)
        assertNull(selected)
        compose.onNodeWithText("candidate · Offline").performClick()
        assertEquals("candidate", selected)
        compose.onNodeWithContentDescription("Preview candidate").assertDoesNotExist()
    }

    @Test fun builtInVoicesAreSelectableAndKeysHaveASeparateMaskedEditor() {
        val definition = hnovel.speech.previewHttpSpeech("""{"id":"builtin-test","name":"Test voice","url":"https://example.org"}""").sources.single()
        val ready = SavedHttpSpeechSource(definition, listOf("https://example.org:443"), group = "Built-in service")
        val locked = ready.copy(definition = hnovel.speech.previewHttpSpeech("""{"id":"key-test","name":"Key voice","url":"https://example.org"}""").sources.single(),
            group = "Key service", credentialKeys = listOf("Token"))
        var selected: String? = null
        var previewed: String? = null
        var saved: Map<String, String>? = null
        val state = androidx.compose.runtime.mutableStateOf(HttpSpeechSourcesState(sources = listOf(ready, locked)))
        activity.get().setContent {
            MaterialTheme { HttpSpeechSourcesScreen(state.value,
                {}, {}, { selected = it }, {}, { _, _ -> }, {}, {}, { _, _, _, _ -> },
                onConfigure = { _, values ->
                    saved = values
                    state.value = state.value.copy(configurationSaved = state.value.configurationSaved + 1)
                }, onPreview = { previewed = it }) }
        }
        compose.onNodeWithText("Import voices").assertDoesNotExist()
        compose.onNodeWithText("Built-in service").performClick()
        compose.onNodeWithContentDescription("Preview Test voice").performClick()
        assertEquals("builtin-test", previewed)
        assertNull(selected)
        compose.onNodeWithText("Test voice").performClick()
        assertEquals("builtin-test", selected)
        compose.onNodeWithText("Remove voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("Configure Key service").performClick()
        compose.onNodeWithText("Confirm").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("test-token")
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(mapOf("Token" to "test-token"), saved)
        compose.onNodeWithText("Token").assertDoesNotExist()
        assertEquals("builtin-test", selected)
        compose.onNodeWithText("Key service").performClick()
        compose.onNodeWithContentDescription("Preview Key voice").assertIsNotEnabled()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chapterActionsDoNotSplitWordsOnNarrowLargeTextScreens() {
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(typography = AppTypography) {
                    ReadAloudControls(ReadAloudState(SpeechRequest("book", "one"), SpeechPhase.Playing,
                        bookTitle = "Book", nextChapterId = "two"), {}, {},
                        Modifier.width(320.dp).padding(horizontal = 24.dp))
                }
            }
        }
        for (label in listOf("Previous Chapter", "Next Chapter")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(1.8f, layout.layoutInput.density.fontScale)
            for (line in 1 until layout.lineCount) {
                val offset = layout.getLineStart(line)
                assertTrue("$label splits a word at $offset", label.getOrNull(offset - 1)?.isWhitespace() == true ||
                    label.getOrNull(offset)?.isWhitespace() == true)
            }
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

}
