package indi.renakoni.nextvol.ui.home.reading

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.ui.home.reading.home.ReadingScreen
import indi.renakoni.nextvol.ui.home.reading.home.RecentReadingBook
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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
@Config(sdk = [27], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReadingHomeScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test fun htmlDescriptionIsReadableAndUpdatesWhenInformationChanges() {
        val id = SourceBookId(Identifier("rules", "fixture"), "book").storageKey
        val raw = "<br>标签：战斗<p class=\"introduce\">边陲小镇</p>"
        val information = BookInformation(id, "Book", author = "Author", description = raw,
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)
        val recent = RecentReadingBook(id, information, UserReadingData(id))
        val result = MutableStateFlow<Result<RecentReadingBook, WebRequestError>?>(Ok(recent))
        activity.get().setContent {
            MaterialTheme {
                ReadingScreen(listOf(id to result), readingListLoading = false,
                    onClickBook = {}, onClickContinueReading = { _, _ -> }, onClickDownloadManager = {},
                    onClickStats = {}, onRemoveBook = {}, onAddBook = {}, onClickOpenChapters = {}, onSettings = {})
            }
        }
        compose.onNodeWithText("标签：战斗\n边陲小镇", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle {
            result.value = Ok(recent.copy(bookInformation = information.copy(description = "<p>更新后的简介</p>")))
        }
        compose.onNodeWithText("更新后的简介", useUnmergedTree = true).assertIsDisplayed()
    }
}
