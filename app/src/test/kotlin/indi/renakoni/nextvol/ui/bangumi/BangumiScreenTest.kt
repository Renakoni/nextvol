package indi.renakoni.nextvol.ui.bangumi

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bangumi.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
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
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BangumiScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun close() { activity.pause().stop().destroy() }
    private fun text(id: Int) = activity.get().getString(id)

    @Test fun tokenIsClearedImmediatelyAfterConnectingAndDisconnectRequiresConfirmation() {
        var submitted: String? = null
        var disconnected = false
        val state = BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), loaded = true))
        activity.get().setContent { MaterialTheme { screen(state, null,
            connect = { submitted = it }, disconnect = { disconnected = true }) } }
        compose.onNodeWithText(text(R.string.bangumi_change_token)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.bangumi_account_actions)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_change_token)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("test-only-token")
        compose.onNodeWithText(text(R.string.bangumi_connect)).performClick()
        assertEquals("test-only-token", submitted)
        compose.onNode(hasSetTextAction()).assertTextContains("")
        compose.onNodeWithContentDescription(text(R.string.bangumi_account_actions)).performClick()
        compose.onNodeWithText(text(R.string.bangumi_disconnect)).performClick()
        assertFalse(disconnected)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        assertTrue(disconnected)
    }

    @Test fun incompleteVolumesNeedConfirmationAndSingleBooksCannotAcceptMultiplePublications() {
        val book = BookInformation("book", "Novel", "", Uri.EMPTY, "Author", "", emptyList(), "", WordCount(0), LocalDateTime.MIN, false)
        val row = BangumiVolumeMapping("one", "Volume one", "one", setOf("chapter"), false)
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            preview = BangumiBookPreview(book, BangumiSubject(1, name = "Novel", volumes = 1), listOf(row), null, 1, "generation"),
            mapping = listOf(row)))
        var complete = false
        var confirmed = false
        activity.get().setContent { MaterialTheme { screen(state, "book", complete = { _, value -> complete = value }, confirm = { confirmed = true }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.bangumi_complete_volume)))
        compose.onNodeWithText(text(R.string.bangumi_complete_volume)).performClick()
        assertTrue(complete)
        compose.runOnIdle {
            val mapping = listOf(row, row.copy(volumeId = "two", editionKey = "two"))
            state = state.copy(mapping = mapping, preview = state.preview!!.copy(mapping = mapping))
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.bangumi_confirm)))
        compose.onNodeWithText(text(R.string.bangumi_confirm)).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(mapping = listOf(row.copy(editionKey = null))) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.bangumi_confirm)))
        compose.onNodeWithText(text(R.string.bangumi_confirm)).assertIsNotEnabled()
        assertFalse(confirmed)
    }

    @Test fun reenablingAVolumePreservesItsConfirmedPublicationIdentity() {
        val book = BookInformation("book", "Novel", "", Uri.EMPTY, "Author", "", emptyList(), "", WordCount(0), LocalDateTime.MIN, false)
        val row = BangumiVolumeMapping("one", "Volume one", "subject:11", setOf("chapter"), true)
        var state by mutableStateOf(BangumiUiState(account = BangumiAccountState(BangumiUser(1, "test"), true),
            preview = BangumiBookPreview(book, BangumiSubject(1, name = "Novel", volumes = 1), listOf(row), null, 1, "generation"),
            mapping = listOf(row)))
        activity.get().setContent { MaterialTheme { screen(state, "book", mapping = { id, key ->
            state = state.copy(mapping = state.mapping.map { if (it.volumeId == id) it.copy(editionKey = key) else it })
        }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(row.title))
        compose.onNodeWithText(row.title).performClick()
        assertNull(state.mapping.single().editionKey)
        compose.onNodeWithText(row.title).performClick()
        assertEquals("subject:11", state.mapping.single().editionKey)
    }

    @Composable private fun screen(state: BangumiUiState, bookId: String?, connect: (String) -> Unit = {},
        disconnect: () -> Unit = {}, complete: (String, Boolean) -> Unit = { _, _ -> }, confirm: () -> Unit = {},
        openBook: (String) -> Unit = {}, sync: () -> Unit = {},
        mapping: (String, String?) -> Unit = { _, _ -> }) {
        BangumiScreen(state, bookId, {}, {}, openBook, connect, disconnect, {}, {}, {}, {}, sync,
            mapping, complete, { _, _ -> }, {}, confirm, {})
    }
}
