package indi.renakoni.nextvol.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.content.componet.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: NextVolDatabase
    private lateinit var settings: SettingState

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        settings = SettingState(UserDataRepository(database.userDataDao()), scope)
    }

    @After fun close() { scope.cancel(); database.close() }

    @Test fun softBreakAtPageBoundaryKeepsMeasuredHeightAndSourceOffsets() {
        val source = ReaderTextSource(0, "A soft\u2028line break and a final paragraph.\n")
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1.5f), LayoutDirection.Ltr)
        val style = ReaderLayoutSettings.from(settings).textStyle(FontFamily.Default)
        val pages = layoutReaderText(listOf(source), 90, 100, 13, style, measurer)
        val fragments = pages.flatten()
        assertEquals(source.text, fragments.joinToString("") { source.text.substring(it.start, it.end) })
        pages.forEach { page ->
            val actualHeight = page.sumOf { fragment ->
                val actual = measurer.measure(fragment.text, style, constraints = Constraints.fixedWidth(90))
                assertEquals("fragment=${fragment.text}", fragment.height, actual.size.height)
                actual.size.height + fragment.spacingBefore
            }
            assertTrue("page overflow: $actualHeight", actualHeight <= 100)
        }
    }

}
