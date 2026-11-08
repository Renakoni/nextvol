package indi.renakoni.nextvol.ui.dialog

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AddBookToBookshelfDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val shelves = listOf(Bookshelf(id = 1, name = "First"), Bookshelf(id = 2, name = "Second"))
    private val selected = mutableListOf<Int>()
    private val deselected = mutableListOf<Int>()
    private var confirmations = 0
    private var dismissals = 0

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun destroy() { activity.pause().stop().destroy() }

    private fun show(
        allBookshelf: List<Bookshelf> = shelves,
        isLoading: Boolean = false,
        isSaving: Boolean = false,
        errorMessage: Int? = null
    ) {
        activity.get().setContent {
            MaterialTheme {
                AddBookToBookshelfDialog(
                    onDismissRequest = { dismissals++ },
                    onConfirmation = { confirmations++ },
                    onSelectBookshelf = { selected.add(it) },
                    onDeselectBookshelf = { deselected.add(it) },
                    allBookshelf = allBookshelf,
                    selectedBookshelfIds = listOf(2),
                    isLoading = isLoading,
                    isSaving = isSaving,
                    errorMessage = errorMessage
                )
            }
        }
    }

    private fun confirmation() = compose.onNode(
        hasText(activity.get().getString(R.string.add_to_bookshelf)) and hasClickAction()
    )

    private fun cancel() = compose.onNodeWithText(activity.get().getString(R.string.cancel))

    @Test fun explanationIsAbsentAndShelfActionsStillWork() {
        show()
        compose.onNodeWithText("Add this book to the following bookshelves").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOn().performClick()
        compose.onNodeWithText("First").performClick()
        assertEquals(listOf(1), selected)
        assertEquals(listOf(2), deselected)
        confirmation().assertIsEnabled().performClick()
        cancel().assertIsEnabled().performClick()
        assertEquals(1, confirmations)
        assertEquals(1, dismissals)
    }

    @Test fun savingDisablesButtonsAndBothShelfClickTargets() {
        show(isSaving = true)
        confirmation().assertIsNotEnabled().performClick()
        cancel().assertIsNotEnabled().performClick()
        compose.onNodeWithText("First").assertIsNotEnabled().performClick()
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled().performClick()
        assertEquals(0, confirmations)
        assertEquals(0, dismissals)
        assertTrue(selected.isEmpty())
        assertTrue(deselected.isEmpty())
    }

    @Test fun savingFailureKeepsTheSelectionAndOffersRetry() {
        show(errorMessage = R.string.save_failed)
        compose.onNodeWithText(activity.get().getString(R.string.save_failed)).assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsOn().assertIsEnabled()
        confirmation().assertIsEnabled().performClick()
        assertEquals(1, confirmations)
    }

}
