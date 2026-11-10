package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.tts.DEFAULT_SPEECH_SCHEME
import indi.renakoni.nextvol.tts.SpeechScheme
import indi.renakoni.nextvol.tts.SpeechSchemes
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SpeechSchemesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val state = mutableStateOf(SpeechSchemesState(
        schemes = SpeechSchemes(items = listOf(SpeechScheme(DEFAULT_SPEECH_SCHEME), SpeechScheme("other", "Other scheme"))),
        loading = false))

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun globalSettingsCanChangeDefaultWithoutOfferingBookBinding() {
        activity.get().setContent { MaterialTheme {
            SpeechSchemesScreen(state.value, null, {}, onOpen = {},
                onBind = { fail("Global settings must not bind a book") },
                onDefault = { state.value = state.value.copy(schemes = state.value.schemes.copy(defaultId = it)) },
                onCreate = { _, _, _ -> }, onRename = { _, _, _ -> }, onDelete = { _, _, _ -> })
        } }
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        compose.onNodeWithText("Use this scheme").assertDoesNotExist()
        compose.onNodeWithText("Set as default").performClick()
        assertEquals("other", state.value.schemes.defaultId)
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        compose.onNodeWithText("Set as default").assertDoesNotExist()
    }

    @Test fun bookMenuUsesAnExistingSchemeAndHidesUseForTheCurrentScheme() {
        activity.get().setContent { MaterialTheme {
            SpeechSchemesScreen(state.value, "book", {}, onOpen = {}, onBind = { id ->
                val schemes = state.value.schemes
                state.value = state.value.copy(schemes = schemes.copy(bindings = mapOf("book" to requireNotNull(id))))
            }, onDefault = { fail("Using a scheme must not set the default") },
                onCreate = { _, _, _ -> fail("Using a scheme must not create a copy") },
                onRename = { _, _, _ -> }, onDelete = { _, _, _ -> })
        } }
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        assertMenuOrder("Set as default", "Use this scheme", "Copy scheme", "Rename scheme", "Delete scheme")
        compose.onNodeWithText("Use this scheme").performClick()
        assertEquals("other", state.value.schemes.bindings["book"])
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        compose.onNodeWithText("Use this scheme").assertDoesNotExist()
        compose.onNodeWithText("Set as default").assertIsDisplayed()
    }

    private fun assertMenuOrder(vararg labels: String) {
        val positions = labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Unexpected menu order: ${labels.toList()}", positions.zipWithNext().all { (a, b) -> a < b })
    }
}
