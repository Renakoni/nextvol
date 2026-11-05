package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.*
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.names.*
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
class SpeechTextAnalysisDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun bookOffersCachedAndFullTextAndShowsProgressOnlyWhileDownloading() {
        val book = SpeechAnalysisBook("book", "Novel", 3, 8, false)
        val state = mutableStateOf(SpeechTextAnalysisState(book = book, sourceName = book.title))
        val starts = mutableListOf<Boolean>()
        activity.get().setContent { MaterialTheme {
            SpeechTextAnalysisContent(state.value, {}, starts::add, {}, {}, {}, { _, _, _ -> }, {}, {})
        } }
        compose.onNodeWithText(activity.get().getString(R.string.tts_analysis_coverage, 3, 8)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.tts_analysis_cached)).performClick()
        compose.onNodeWithText(activity.get().getString(R.string.tts_analysis_download)).performClick()
        assertEquals(listOf(false, true), starts)
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(running = true,
            download = BookDownloadStatus(task = DownloadTaskState(DownloadTaskStatus.Queued))) }
        compose.onAllNodes(progress).assertCountEquals(1)
        compose.runOnIdle { state.value = state.value.copy(download = null) }
        compose.onNodeWithText(activity.get().getString(R.string.tts_analysis_running)).assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun originalTableSeparatesSelectionGenderAndEditingAndUsesAlgorithmStatistic() {
        val candidate = SpeechTextAnalysisCandidate("Alice", "Alice", 97,
            SpeechNameClassification(SpeechNameHint.Female, SpeechNameHintBasis.Model), true, true)
        val state = mutableStateOf(SpeechTextAnalysisState(sourceName = "Novel",
            result = SpeechTextAnalysisResult(NewWordAlgorithm.Native, listOf(candidate))))
        var toggled: String? = null
        var gender: String? = null
        var saved = false
        activity.get().setContent { MaterialTheme {
            SpeechTextAnalysisContent(state.value, {}, {}, {}, { toggled = it }, { gender = it },
                { _, _, _ -> }, { saved = true }, {})
        } }
        compose.onNodeWithText(activity.get().getString(R.string.tts_new_word_score)).assertIsDisplayed()
        compose.onNodeWithText("● Alice").performClick()
        assertEquals("Alice", toggled)
        val label = activity.get().getString(R.string.tts_analysis_gender_action, "Alice",
            activity.get().getString(R.string.tts_voice_female))
        compose.onNodeWithContentDescription(label).performClick()
        assertEquals("Alice", gender)
        compose.onNodeWithText("● Alice").performTouchInput { longClick() }
        compose.onNodeWithText(activity.get().getString(R.string.tts_analysis_original_word, "Alice")).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.tts_name_basis_model), substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(activity.get().getString(R.string.cancel)).onLast().performClick()
        compose.runOnIdle { state.value = state.value.copy(result = state.value.result!!.copy(algorithm = NewWordAlgorithm.Hanlp)) }
        compose.onNodeWithText(activity.get().getString(R.string.tts_analysis_frequency)).assertIsDisplayed()
        compose.onNodeWithTag("analysis-save").performClick()
        assertTrue(saved)
    }
}
