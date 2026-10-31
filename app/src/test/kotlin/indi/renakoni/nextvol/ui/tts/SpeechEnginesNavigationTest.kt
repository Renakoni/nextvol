package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import hnovel.speech.OfflineEngineConfig
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
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
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechEnginesNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var nav: NavHostController

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun enginesHaveAnIndependentEntryAndHttpEditorReturnsToThatEntry() {
        val source = SavedHttpSpeechSource(previewHttpSpeech("""{"name":"Custom HTTP engine","url":"https://speech.example/speak?text={{speakText}}"}""")
            .sources.single(), listOf("https://speech.example"))
        val engines = SpeechEnginesState(engines = listOf(OfflineEngineConfig("sherpa", "Sherpa-ONNX")), http = listOf(source), loading = false)
        var httpState by mutableStateOf(HttpSpeechSourcesState(sources = listOf(source)))
        var sourcesOpened = 0
        var savedName: String? = null
        activity.get().setContent { MaterialTheme {
            nav = rememberNavController()
            NavHost(nav, startDestination = SpeechSettingsRoute) {
                composable<SpeechSettingsRoute> {
                    SpeechSettingsScreen(SpeechSettingsUiState(loading = false), ReadAloudState(), {},
                        onSources = { sourcesOpened++ }, onPreferences = {}, onCommand = {},
                        onEngines = { nav.navigate(SpeechEnginesRoute) })
                }
                composable<SpeechEnginesRoute> {
                    SpeechEnginesScreen(engines, { nav.popBackStack() }, {},
                        onHttp = { nav.navigate(HttpSpeechSourcesRoute(editId = it)) })
                }
                composable<HttpSpeechSourcesRoute> { entry ->
                    HttpSpeechSourcesScreen(httpState, { nav.popBackStack() }, {}, {}, {}, { _, _ -> }, {}, {},
                        onEdit = { _, name, _, _ ->
                            savedName = name
                            httpState = httpState.copy(configurationSaved = httpState.configurationSaved + 1)
                        }, initialEditId = entry.toRoute<HttpSpeechSourcesRoute>().editId,
                        onInitialEditorClosed = { nav.popBackStack() })
                }
            }
        } }
        compose.onNodeWithTag("speech-settings-list").performScrollToNode(hasTestTag("speech-engines-entry"))
        compose.onNodeWithTag("speech-engines-entry").performClick()
        compose.onNodeWithText("Sherpa-ONNX").assertIsDisplayed()
        compose.onNodeWithText("Custom HTTP engine").performClick()
        compose.onNodeWithText("Edit engine").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(nav.currentDestination!!.hasRoute<SpeechEnginesRoute>()); assertEquals(0, sourcesOpened) }
        compose.onNodeWithText("Custom HTTP engine").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Custom HTTP engine", savedName)
            assertTrue(nav.currentDestination!!.hasRoute<SpeechEnginesRoute>())
            assertEquals(0, sourcesOpened)
        }
    }
}
