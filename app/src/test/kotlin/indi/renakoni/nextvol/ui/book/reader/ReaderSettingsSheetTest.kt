package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSettingsSheetTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sameWindowSheetRetainsExpansionBackAndScrimDismissAcrossReopening() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var visible by mutableStateOf(false)
        lateinit var sheet: SheetState
        lateinit var scope: CoroutineScope
        fun await(condition: () -> Boolean) = compose.waitUntil(10_000) { compose.waitForIdle(); condition() }
        try {
            activity.get().setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        TextButton(onClick = { visible = true }) { Text("Open settings") }
                        if (visible) {
                            sheet = rememberBottomSheetState(initialValue = SheetValue.Hidden)
                            scope = rememberCoroutineScope()
                            ReaderSettingsSheet(sheet, onDismissRequest = { visible = false }) {
                                Column(Modifier.fillMaxHeight()) { Text("Sheet content") }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Open settings").performClick()
            await { sheet.currentValue == SheetValue.PartiallyExpanded }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithText("Sheet content").assertIsDisplayed()
            compose.runOnIdle { scope.launch { sheet.expand() } }
            await { sheet.currentValue == SheetValue.Expanded }
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            await { sheet.currentValue == SheetValue.PartiallyExpanded }
            assertTrue(visible)
            compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            await { !visible }
            val previous = sheet
            compose.onNodeWithText("Open settings").performClick()
            await { sheet.currentValue == SheetValue.PartiallyExpanded }
            assertNotSame(previous, sheet)
            compose.onNodeWithContentDescription(activity.get().getString(R.string.close))
                .performTouchInput { click(Offset(10f, 10f)) }
            await { !visible }
            compose.onNodeWithText("Open settings").performClick()
            await { sheet.currentValue == SheetValue.PartiallyExpanded }
            compose.onNodeWithContentDescription(activity.get().getString(R.string.close))
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            await { !visible }
            compose.onNodeWithText("Open settings").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
