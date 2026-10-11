package indi.renakoni.nextvol.ui.book.reader.bookmark

import android.app.Application
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderBottomBar
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReaderBookmarksUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val book = SourceBookId(Identifier("fixture", "source"), "book")
    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }
    private fun bookmark(number: Int) = ReadingBookmark(bookId = book.storageKey,
        chapterId = SourceChapterId(book, number.toString()).storageKey, chapterTitle = "Chapter $number",
        componentIndex = 0, offset = 0, fingerprint = "a".repeat(64), preview = "Recognizable passage $number", progress = .42f)

    @Test fun outgoingCaptureReleasesWithoutClearingTheIncomingRegistration() {
        val session = ReaderBookmarkSession()
        val old = ReaderBookmarkPosition("old", mockk(), ReaderContentAnchor(0, 1), .1f)
        val fresh = ReaderBookmarkPosition("new", mockk(), ReaderContentAnchor(0, 2), .2f)
        var switched by mutableStateOf(false)
        var keepOld by mutableStateOf(true)
        activity.get().setContent {
            CompositionLocalProvider(LocalReaderBookmarks provides session) {
                if (keepOld) key("old") {
                    CompositionLocalProvider(LocalReaderRendererActive provides !switched) { RegisterBookmarkCapture { old } }
                }
                if (switched) key("new") { RegisterBookmarkCapture { fresh } }
            }
        }
        val oldCallback = compose.runOnIdle { session.capture!! }
        assertSame(old, compose.runOnIdle { oldCallback() })
        compose.runOnIdle { switched = true }
        compose.waitForIdle()
        assertNull(compose.runOnIdle { oldCallback() })
        assertSame(fresh, compose.runOnIdle { session.capture!!() })
        compose.runOnIdle { keepOld = false }
        compose.waitForIdle()
        assertSame(fresh, compose.runOnIdle { session.capture!!() })
    }

    @Test fun entryIsEnabledAndInvokesTheBookmarkAction() {
        var clicks = 0
        activity.get().setContent { MaterialTheme {
            ReaderBottomBar(false, false, {}, {}, {}, {}, onClickBookmarks = { clicks++ })
        } }
        compose.onNodeWithContentDescription(activity.get().getString(R.string.action_bookmark)).assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test fun longListJumpsAndDeletesTheRequestedIdentityAndAddStaysReachable() {
        val values = (1..60).map(::bookmark)
        var added = 0
        var jumped: ReadingBookmark? = null
        var deleted: ReadingBookmark? = null
        activity.get().setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                ReaderBookmarksSheet(values, false, onAdd = { added++ }, onJump = { jumped = it },
                    onDelete = { deleted = it }, onDismiss = {})
            }
        } }
        compose.onNodeWithText("Bookmark this position").assertIsDisplayed().performClick()
        assertEquals(1, added)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Chapter 60"))
        compose.onNodeWithText("Chapter 60").performClick()
        assertEquals(values.last(), jumped)
        compose.onNodeWithContentDescription("Delete bookmark in Chapter 60").performClick()
        assertEquals(values.last(), deleted)
        compose.onNodeWithText("Bookmark this position").assertIsDisplayed()
    }

    @Test fun exportDialogLetsTheUserExcludeBookmarks() {
        var included: Boolean? = null
        activity.get().setContent { MaterialTheme {
            indi.renakoni.nextvol.ui.components.ExportUserDataDialog({}, {}, { included = it.bookmark })
        } }
        compose.onNodeWithText("Bookmarks").performScrollTo().performClick()
        compose.onNodeWithText(activity.get().getString(R.string.export_to_file)).performClick()
        assertEquals(false, included)
    }

    @Test fun resourcesFormatInFourLanguagesAndRegionalRussian() {
        for (language in listOf("en", "zh-CN", "zh-TW", "ru", "ru-RU")) {
            val localized = activity.get().createConfigurationContext(Configuration(activity.get().resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            assertTrue(localized.getString(R.string.reader_bookmarks_position, 42).contains("42%"))
            assertTrue(localized.getString(R.string.reader_bookmarks_delete, "Title").contains("Title"))
            if (language != "en") assertNotEquals("Bookmarks", localized.getString(R.string.reader_bookmarks_title))
        }
    }

    @Test fun fingerprintDetectsTextAndImageChangesButSurvivesInstallationPaths() {
        fun chapter(text: String, image: String) = ChapterContentUiState(SourceChapterId(book, "1").storageKey,
            "Chapter", listOf(SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), activity.get()),
                ImageComponent(ImageComponentData(Uri.parse(image)))), null, null)
        val original = chapter("A recognizable passage", "file:///old/local-books/book/assets/image.png")
        val saved = ReaderBookmarkPosition(book.storageKey, original, ReaderContentAnchor(0, 2), .4f).bookmark()
        assertEquals(ReaderContentAnchor(0, 2), saved.anchorIn(chapter("A recognizable passage", "file:///new/local-books/book/assets/image.png")))
        assertNull(saved.anchorIn(chapter("Changed text", "file:///new/local-books/book/assets/image.png")))
        assertNull(saved.anchorIn(chapter("A recognizable passage", "file:///new/local-books/book/assets/other.png")))
    }
}
