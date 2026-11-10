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
        schemes = SpeechSchemes(items = listOf(SpeechScheme(DEFAULT_SPEECH_SCHEME), SpeechScheme("other", "Other scheme")),
            bindings = mapOf("another-book" to "other")),
        titles = mapOf("book" to "Current book", "another-book" to "Another book"), loading = false))

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun settingsShowCompactRowsAndOnlyGlobalActions() {
        var opened: String? = null
        activity.get().setContent { MaterialTheme {
            SpeechSchemesScreen(state.value, null, {}, onOpen = { opened = it },
                onBind = { fail("Global settings must not bind a book") },
                onDefault = { state.value = state.value.copy(schemes = state.value.schemes.copy(defaultId = it)) },
                onCreate = { _, _, _ -> }, onRename = { _, _, _ -> }, onDelete = { _, _, _ -> })
        } }
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("Scheme for this book").assertDoesNotExist()
        compose.onNodeWithText("Books following the default", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Used by:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Other scheme").performClick()
        assertEquals("other", opened)
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        compose.onNodeWithText("Use this scheme").assertDoesNotExist()
        assertMenuOrder("Set as default", "Copy scheme", "Rename scheme", "Delete scheme")
        compose.onNodeWithText("Set as default").performClick()
        assertEquals("other", state.value.schemes.defaultId)
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        compose.onNodeWithText("Set as default").assertDoesNotExist()
        compose.onNodeWithText("Use this scheme").assertDoesNotExist()
    }

    @Test fun bookMenuUsesAnExistingSchemeAfterSetDefaultAndCanReturnToFollowingDefault() {
        val bindings = mutableListOf<String?>()
        activity.get().setContent { MaterialTheme {
            SpeechSchemesScreen(state.value, "book", {}, onOpen = {}, onBind = { id ->
                bindings += id
                val schemes = state.value.schemes
                state.value = state.value.copy(schemes = schemes.copy(bindings =
                    if (id == null) schemes.bindings - "book" else schemes.bindings + ("book" to id)))
            }, onDefault = { fail("Using a scheme must not set the default") },
                onCreate = { _, _, _ -> fail("Using a scheme must not create a copy") },
                onRename = { _, _, _ -> }, onDelete = { _, _, _ -> })
        } }
        compose.onNodeWithText("Copy as this book’s scheme").assertDoesNotExist()
        compose.onNodeWithText("Follow default: Standard scheme").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for Standard scheme").performClick()
        compose.onNodeWithText("Set as default").assertDoesNotExist()
        compose.onNodeWithText("Use this scheme").assertDoesNotExist()
        compose.onNodeWithText("Rename scheme").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        assertMenuOrder("Set as default", "Use this scheme", "Copy scheme", "Rename scheme", "Delete scheme")
        compose.onNodeWithText("Use this scheme").performClick()
        assertEquals(listOf("other"), bindings)
        compose.onNodeWithText("Follow default: Standard scheme").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for Other scheme").performClick()
        compose.onNodeWithText("Use this scheme").assertDoesNotExist()
        compose.onNodeWithText("Set as default").assertIsDisplayed()
        compose.onNodeWithText("Rename scheme").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Scheme for this book").performClick()
        compose.onNodeWithText("Follow default").performClick()
        assertEquals(listOf("other", null), bindings)
        compose.onNodeWithText("Follow default: Standard scheme").assertIsDisplayed()
    }

    private fun assertMenuOrder(vararg labels: String) {
        val positions = labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Unexpected menu order: ${labels.toList()}", positions.zipWithNext().all { (a, b) -> a < b })
    }
}
