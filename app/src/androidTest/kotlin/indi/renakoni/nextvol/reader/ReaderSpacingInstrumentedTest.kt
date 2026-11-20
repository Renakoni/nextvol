package indi.renakoni.nextvol.reader

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.content.componet.*
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderPage
import indi.renakoni.nextvol.ui.book.reader.content.flip.paginateReaderComponents
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSpacingInstrumentedTest {
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

    @Test fun imageBoundariesStayIndependentWhileAdjacentTextSharesAPage() = runBlocking {
        val repository = UserDataRepository(database.userDataDao())
        fun text(value: String) = SimpleTextComponent(SimpleTextComponentData(value), repository, context)
        val image = ImageComponent(ImageComponentData(Uri.parse("file:///fixture-image.png")))
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
        val layout = ReaderLayoutSettings.from(settings)
        val pages = paginateReaderComponents(listOf(text("A"), text("B"), image, text("C")), 400, 300,
            ReaderTextLayoutInput(layout, layout.textStyle(FontFamily.Default), measurer, 12))
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1), (pages[0] as ReaderPage).ranges.map { it.componentIndex })
        assertSame(image.data, pages[1].data)
        assertEquals(3, (pages[2] as ReaderPage).anchor.componentIndex)
    }

    @Test fun renderedTextUsesTheSameStyleLineBreaksAndHeightAsPagination() {
        val density = Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val style = ReaderLayoutSettings.from(settings).textStyle(FontFamily.Default)
        val width = with(density) { 220.dp.roundToPx() }
        val fragments = layoutReaderText(listOf(ReaderTextSource(0,
            "A paragraph long enough to wrap across several lines in the reader. ".repeat(3) + "\nShort paragraph.")),
            width, 300, 12, style, measurer).first()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(220.dp)) { ReaderTextFragments(fragments, style, Color.Black, Modifier.fillMaxWidth()) }
            }
        }
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        assertEquals(fragments.size, nodes.fetchSemanticsNodes().size)
        fragments.forEachIndexed { index, fragment ->
            val rendered = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rendered) }
            val result = rendered.single()
            assertEquals(fragment.text, result.layoutInput.text.text)
            assertEquals(fragment.height, result.size.height)
            val expected = measurer.measure(fragment.text, style, constraints = Constraints(maxWidth = width))
            assertEquals(expected.lineCount, result.lineCount)
            for (line in 0 until result.lineCount) assertEquals(expected.getLineStart(line), result.getLineStart(line))
        }
    }

}
