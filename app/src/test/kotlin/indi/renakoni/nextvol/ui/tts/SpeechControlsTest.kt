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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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

    @Test fun voiceManagementFiltersBulkActionsWithoutChangingNarrationDialogueOrPlayback() {
        val voices = listOf("Alpha one", "Alpha two", "Beta").mapIndexed { index, name ->
            SavedHttpSpeechSource(hnovel.speech.previewHttpSpeech(
                """{"id":"manage-$index","name":"$name","url":"https://example.org","nextvolEngineName":"Managed"}""").sources.single(),
                listOf("https://example.org:443"), group = if (index < 2) "Managed" else null)
        }
        var selectedCalls = 0
        val playbackActions = mutableListOf<SpeechAction>()
        var deleted = emptyList<SavedHttpSpeechSource>()
        var added = emptyList<SpeechVoiceRef>()
        val settings = SpeechSettings(voiceRoutingConfigured = true,
            narrationVoices = listOf(SpeechVoiceRef(httpSource = voices.first().definition.id)))
        activity.get().setContent {
            MaterialTheme {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(sources = voices, loaded = true), {}, {},
                    { selectedCalls++ }, {}, {}, {}, { _, _, _, _ -> },
                    selection = settings, onDialogue = { selectedCalls++ }, onPreview = {},
                    playback = ReadAloudState(SpeechRequest("book", "chapter"), SpeechPhase.Paused),
                    onCommand = { playbackActions += it }, onDeleteHttp = { deleted = it },
                    onAddToPool = { added = it },
                    focusVoiceKey = "http:${voices.first().definition.id}")
            }
        }
        fun label(id: Int) = activity.get().getString(id)
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_library_actions)).performClick()
        compose.onNodeWithText(label(indi.renakoni.nextvol.R.string.tts_manage_voices)).performClick()
        val previewLabel = activity.get().getString(indi.renakoni.nextvol.R.string.tts_preview_voice, "Alpha one")
        compose.onNodeWithContentDescription(previewLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_resume)).assertDoesNotExist()
        compose.onNodeWithText("Alpha one").performClick()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_voice_search)).performClick()
        compose.onNodeWithTag("voice-search-input").performTextInput("Alpha")
        compose.onNodeWithTag("voice-select-inverse").performClick()
        compose.onNodeWithTag("voice-delete-selected").performClick()
        compose.runOnIdle { assertEquals(listOf(voices[1]), deleted) }
        compose.onNodeWithTag("voice-select-all").performClick()
        compose.onNodeWithTag("voice-add-to-pool").performClick()
        compose.runOnIdle { assertEquals(voices.take(2).map { it.definition.id }, added.map { it.httpSource }) }
        compose.onNodeWithTag("voice-delete-selected").performClick()
        compose.runOnIdle { assertEquals(voices.take(2), deleted) }
        compose.onNodeWithTag("voice-select-none").performClick()
        compose.onNodeWithTag("voice-delete-selected").assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_manage_exit)).performClick()
        compose.onNodeWithTag("voice-select-all").assertDoesNotExist()
        compose.onNodeWithContentDescription(previewLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_resume)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, selectedCalls)
            assertTrue(playbackActions.isEmpty())
        }
        compose.onNodeWithText("Alpha two").performClick()
        compose.runOnIdle { assertEquals(1, selectedCalls) }
    }

    @Test fun catalogRemovalKeepsGroupIdentityAndClearsEmptyTabsAndTheirSelection() {
        val first = SavedHttpSpeechSource(hnovel.speech.previewHttpSpeech(
            """{"id":"first","name":"First voice","url":"https://example.org"}""").sources.single(),
            emptyList(), group = "Built-in")
        val second = newHttpVoice(hnovel.speech.SpeechEngineConfig("http_second", "Shared", "http", url = "https://example.org"))
        val third = newHttpVoice(hnovel.speech.SpeechEngineConfig("http_third", "Shared", "http", url = "https://example.org"))
        val voices = listOf(first, editHttpVoice(second, kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive("Second voice"))
        }, false, false), editHttpVoice(third, kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive("Third voice"))
        }, true, false))
        val state = androidx.compose.runtime.mutableStateOf(HttpSpeechSourcesState(sources = voices, loaded = true))
        var imports = 0
        activity.get().setContent { MaterialTheme {
            HttpSpeechSourcesScreen(state.value, {}, { imports++ }, {}, {}, {}, {}, { _, _, _, _ -> },
                focusVoiceKey = "http:${second.definition.id}", onDeleteHttp = { deleted ->
                    state.value = state.value.copy(sources = state.value.sources - deleted.toSet())
                })
        } }
        fun label(id: Int) = activity.get().getString(id)
        fun manage() {
            compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_library_actions)).performClick()
            compose.onNodeWithText(label(indi.renakoni.nextvol.R.string.tts_manage_voices)).performClick()
        }
        compose.onAllNodesWithText("Shared").assertCountEquals(2)
        manage()
        compose.onNodeWithText("Second voice").performClick().assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(sources = voices.drop(1), busy = true) }
        compose.onNodeWithText("Built-in").assertDoesNotExist()
        compose.onNodeWithText("Second voice").assertIsDisplayed().assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.tts_manage_selected, 1)).assertIsDisplayed()

        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_voice_search)).performClick()
        compose.onNodeWithTag("voice-search-input").performTextInput("No matching voice")
        compose.onNodeWithText(label(indi.renakoni.nextvol.R.string.search_no_results)).assertIsDisplayed()
        compose.onAllNodesWithText("Shared").assertCountEquals(2)
        compose.onNodeWithTag("voice-search-input").performTextClearance()
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.close)).performClick()
        compose.onNodeWithTag("voice-delete-selected").performClick()
        compose.onAllNodesWithText("Shared").assertCountEquals(1)
        compose.onNodeWithText("Third voice").assertIsDisplayed()
        compose.onNodeWithTag("voice-select-all").assertDoesNotExist()

        manage()
        compose.onNodeWithTag("voice-select-all").performClick()
        compose.onNodeWithTag("voice-delete-selected").performClick()
        compose.onNodeWithText("Shared").assertDoesNotExist()
        compose.onNode(hasText("System speech") and hasAnyAncestor(hasTestTag("speech-source-tabs"))).assertIsSelected()
        compose.onNodeWithTag("voice-delete-selected").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(indi.renakoni.nextvol.R.string.tts_library_actions)).performClick()
        compose.onNodeWithText(label(indi.renakoni.nextvol.R.string.tts_import_sources)).performClick()
        compose.runOnIdle { assertEquals(1, imports) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sleepTimerCanBeSetAndCancelledOnANarrowLargeTextScreen() {
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
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sleep timer").assertIsDisplayed().performClick()
        compose.onNodeWithText("10 min").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.onNodeWithContentDescription("Off").assertIsDisplayed().performClick()
        assertEquals(listOf(10, null), selected)
    }

    @Test fun previewDoesNotOfferASleepTimerAndFinishedPlaybackCannotSetOne() {
        val state = androidx.compose.runtime.mutableStateOf(ReadAloudState(
            SpeechRequest("", "", "Preview", previewLabel = "Sample voice"), SpeechPhase.Playing))
        activity.get().setContent { MaterialTheme { ReadAloudControls(state.value, {}, onSleepTimer = {}) } }
        compose.onNodeWithText("Sample voice").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sleep timer").assertDoesNotExist()
        compose.runOnIdle { state.value = ReadAloudState(SpeechRequest("book", "one"), SpeechPhase.Completed) }
        compose.onNodeWithContentDescription("Sleep timer").assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h900dp")
    fun voicePreviewTitleAndRowButtonFollowTheSamePlaybackStateAsTheBottomBar() {
        val sources = listOf("Doubao · General female", "Other voice").mapIndexed { index, name ->
            SavedHttpSpeechSource(hnovel.speech.previewHttpSpeech(
                """{"id":"preview-$index","name":"$name","url":"https://example.org"}""").sources.single(),
                listOf("https://example.org:443"))
        }
        val state = androidx.compose.runtime.mutableStateOf(ReadAloudState(
            SpeechRequest("", "", "Sample.", SpeechSettings(httpSource = "preview-0"), sources[0].definition.name), SpeechPhase.Preparing))
        activity.get().setContent { MaterialTheme {
            HttpSpeechSourcesScreen(HttpSpeechSourcesState(sources = sources, loaded = true), {}, {}, {}, {}, {}, {}, { _, _, _, _ -> },
                playback = state.value, onPreview = {}, focusVoiceKey = "http:preview-0")
        } }
        compose.onAllNodesWithText(sources[0].definition.name).assertCountEquals(2)
        compose.onNodeWithContentDescription("Pause preview of ${sources[0].definition.name}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview Other voice").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(phase = SpeechPhase.Paused) }
        compose.onNodeWithContentDescription("Pause preview of ${sources[0].definition.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview ${sources[0].definition.name}").assertIsDisplayed()
        compose.runOnIdle { state.value = ReadAloudState(
            SpeechRequest("", "", "Sample.", SpeechSettings(httpSource = "preview-1"), sources[1].definition.name), SpeechPhase.Playing) }
        compose.onAllNodesWithText(sources[0].definition.name).assertCountEquals(1)
        compose.onAllNodesWithText(sources[1].definition.name).assertCountEquals(2)
        compose.onNodeWithContentDescription("Preview ${sources[0].definition.name}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause preview of Other voice").assertIsDisplayed()
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

    @Test fun loadingCanPauseAndFailedPlaybackOpensListeningSettingsWithBookContext() {
        val actions = mutableListOf<SpeechAction>()
        val state = androidx.compose.runtime.mutableStateOf(ReadAloudState(SpeechRequest("book", "chapter"), SpeechPhase.Preparing))
        val sources = androidx.compose.runtime.mutableStateOf(HttpSpeechSourcesState())
        val definition = hnovel.speech.previewHttpSpeech(
            """{"id":"cloud-voice","name":"Cloud voice","url":"https://example.org"}""",
        ).sources.single()
        val source = SavedHttpSpeechSource(definition, listOf("https://example.org:443"),
            group = "Cloud engine", credentialKeys = listOf("AppKey"))
        val voice = SpeechVoiceRef(httpSource = source.definition.id, label = source.definition.name)
        val role = SpeechRoleMatch("Alice", "character", "Female role", true, voice)
        var configured: Map<String, String>? = null
        lateinit var nav: NavHostController
        activity.get().setContent {
            MaterialTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = "reader",
                    enterTransition = { androidx.compose.animation.EnterTransition.None },
                    exitTransition = { androidx.compose.animation.ExitTransition.None }) {
                    composable("reader") {
                        ReadAloudControls(state.value, { actions += it },
                            onSettings = { nav.navigateToSpeechSettings("reader-book") })
                    }
                    composable<SpeechSettingsRoute> { androidx.compose.material3.Text("Listening settings") }
                    composable<HttpSpeechSourcesRoute> { entry ->
                        val route = entry.toRoute<HttpSpeechSourcesRoute>()
                        HttpSpeechSourcesScreen(sources.value, {}, {}, {}, {}, {}, {}, { _, _, _, _ -> },
                            focusVoiceKey = route.voiceKey, configureCredentials = route.configureCredentials,
                            catalogReady = sources.value.loaded, onConfigure = { target, values ->
                                assertEquals(source.definition.id, target.definition.id)
                                configured = values
                                sources.value = sources.value.copy(configurationSaved = 1)
                            })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle { state.value = state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.HttpLogin,
            failedVoice = voice, failedRole = role) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Retry").performClick()
        assertEquals(listOf(SpeechAction.Pause, SpeechAction.Resume), actions)
        compose.onNodeWithContentDescription("Next passage").assertIsNotEnabled()
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.tts_voice_error,
            "Alice · Cloud voice", activity.get().getString(SpeechError.HttpLogin.messageResource))).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.get().getString(indi.renakoni.nextvol.R.string.tts_settings)).performClick()
        compose.onNodeWithText("Listening settings").assertIsDisplayed()
        compose.onNodeWithText("AppKey").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(SpeechSettingsRoute("reader-book"), nav.currentBackStackEntry!!.toRoute<SpeechSettingsRoute>())
            // Opening the source editor explicitly still waits for its catalog before configuring credentials.
            nav.navigate(HttpSpeechSourcesRoute("http:${source.definition.id}", true))
        }
        compose.onNodeWithText("AppKey").assertDoesNotExist()
        compose.runOnIdle {
            sources.value = sources.value.copy(sources = listOf(source), loaded = true)
        }
        compose.onNodeWithText("AppKey").assertIsDisplayed().performTextInput("account-key")
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.confirm)).performClick()
        assertEquals(mapOf("AppKey" to "account-key"), configured)
        compose.onNodeWithText("Cloud engine").assertIsSelected()
        compose.onNodeWithText(source.definition.name).assertIsDisplayed()
        compose.runOnIdle {
            nav.popBackStack("reader", false)
            state.value = state.value.copy(error = SpeechError.MissingVoice, failedRole = role.copy(voiceRef = null))
        }
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.tts_voice_error,
            "Alice", activity.get().getString(indi.renakoni.nextvol.R.string.tts_role_voice_missing))).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.get().getString(indi.renakoni.nextvol.R.string.tts_settings)).performClick()
        compose.onNodeWithText("Listening settings").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(SpeechSettingsRoute("reader-book"), nav.currentBackStackEntry!!.toRoute<SpeechSettingsRoute>())
        }
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
        var enginesOpened = 0
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(typography = AppTypography) {
                    SpeechSettingsScreen(
                        SpeechSettingsUiState(loading = false),
                        ReadAloudState(SpeechRequest("book", "chapter"), SpeechPhase.Playing,
                            bookTitle = "A long book title ".repeat(8)),
                        {}, onSources = { fail("The engine entry must not open voice sources") },
                        onPreferences = {}, onCommand = { actions += it }, onEngines = { enginesOpened++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        compose.onNodeWithTag("speech-settings-list").performScrollToNode(hasTestTag("speech-engines-entry"))
        compose.onNodeWithTag("speech-engines-entry").performClick()
        assertEquals(1, enginesOpened)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithText("A long book title ".repeat(8)).performClick()
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(listOf(SpeechAction.Pause, SpeechAction.Stop), actions)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h900dp")
    fun readAloudSettingsExposeOnlyTheThreeOriginalControlScales() {
        val state = androidx.compose.runtime.mutableStateOf(SpeechSettingsUiState(loading = false))
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) {
                SpeechPreferencesScreen(state.value, {},
                    onRate = { state.value = state.value.copy(settings = state.value.settings.copy(rate = it)) },
                    onVolume = { state.value = state.value.copy(settings = state.value.settings.copy(volume = it)) },
                    onPitch = { state.value = state.value.copy(settings = state.value.settings.copy(pitch = it)) })
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(3)
        compose.onAllNodesWithText("50").assertCountEquals(2)
        compose.onNodeWithText("25").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speed").performSemanticsAction(SemanticsActions.SetProgress) { it(51f) }
        compose.onNodeWithContentDescription("Volume").performSemanticsAction(SemanticsActions.SetProgress) { it(73f) }
        compose.onNodeWithContentDescription("Pitch").performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        compose.runOnIdle {
            assertEquals(SpeechSettings(rate = 1.02f, volume = 1.46f, pitch = 1.14f), state.value.settings)
        }
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h900dp")
    fun refreshingTheSameMissingEngineKeepsTheErrorAndDisablesVoiceSelection() {
        val repository = mockk<SpeechSettingsEditor>()
        every { repository.changes } returns MutableStateFlow(SpeechSettings())
        val engines = mockk<SystemSpeechEngines>()
        coEvery { engines.installed() } returns emptyList()
        coEvery { engines.inspect("") } throws SpeechException(SpeechError.NoEngine)
        val model = SpeechSettingsViewModel(repository, engines, mockk(), mockk(), mockk())
        val inspection = CompletableDeferred<SpeechEngineState>()
        fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
        try {
            activity.get().setContent { MaterialTheme {
                val state by model.state.collectAsState()
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(), {}, {}, {}, {}, {}, {}, { _, _, _, _ -> },
                    onRefresh = model::refresh,
                    systemSettings = { SystemSpeechSettings(state, {}, {}, {}) })
            } }
            waitUntil { !model.state.value.loading }
            val error = activity.get().getString(SpeechError.NoEngine.messageResource)
            compose.onNodeWithText(error).assertIsDisplayed()
            coEvery { engines.inspect("") } coAnswers { inspection.await() }
            compose.onNodeWithContentDescription(activity.get().getString(indi.renakoni.nextvol.R.string.tts_library_actions)).performClick()
            compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.tts_refresh)).performClick()
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

    @Test fun systemVoiceAuditionAndSelectionKeepTheDialogOpenForFurtherGestures() {
        var selected: String? = null
        var previewed: String? = null
        var dialogue: String? = null
        activity.get().setContent {
            MaterialTheme {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(), {}, {}, {}, {}, {}, {}, { _, _, _, _ -> },
                    systemSettings = {
                        SystemSpeechSettings(SpeechSettingsUiState(loading = false,
                            voices = listOf(SpeechVoice("candidate", "en-US", false, true))), {}, { selected = it }, {},
                            onPreviewVoice = { previewed = it }, onDialogue = { dialogue = it })
                    })
            }
        }
        compose.onNodeWithText("Voice").performClick()
        compose.onNodeWithContentDescription("Preview candidate").performClick()
        assertEquals("candidate", previewed)
        assertNull(selected)
        compose.onNodeWithText("candidate · Offline").performClick()
        assertEquals("candidate", selected)
        compose.onNodeWithContentDescription("Preview candidate").assertIsDisplayed()
        compose.onNodeWithText("candidate · Offline").performTouchInput { longClick() }
        assertEquals("candidate", dialogue)
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
