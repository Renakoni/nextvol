package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderTextLayoutInput
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragments
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextSource
import indi.renakoni.nextvol.ui.book.reader.content.componet.layoutReaderText
import indi.renakoni.nextvol.ui.book.reader.content.componet.readerTextColor
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ReaderContentAnchor(val componentIndex: Int, val offset: Int)
internal data class ReaderContentRange(val componentIndex: Int, val start: Int, val end: Int)

/** Host-only page metadata, separate from serialized source data. */
internal class ReaderPage(
    source: AbstractContentComponent<*>,
    val ranges: List<ReaderContentRange>,
    private val render: @Composable (Modifier) -> Unit,
) : AbstractContentComponent<AbstractContentComponentData>(source.data) {
    override val id = source.id
    val anchor get() = ranges.first().let { ReaderContentAnchor(it.componentIndex, it.start) }
    fun contains(anchor: ReaderContentAnchor) = ranges.any {
        it.componentIndex == anchor.componentIndex && anchor.offset >= it.start && anchor.offset < it.end
    }
    @Composable override fun Content(modifier: Modifier) = render(modifier)
}

internal suspend fun paginateReaderComponents(
    components: List<AbstractContentComponent<*>>, height: Int, width: Int, layout: ReaderTextLayoutInput,
): List<AbstractContentComponent<*>> {
    val pages = mutableListOf<AbstractContentComponent<*>>()
    var index = 0
    while (index < components.size) {
        currentCoroutineContext().ensureActive()
        val component = components[index]
        if (component is SimpleTextComponent) {
            val sources = mutableListOf<ReaderTextSource>()
            while (index < components.size && components[index] is SimpleTextComponent) {
                sources += ReaderTextSource(index, (components[index] as SimpleTextComponent).data.text)
                index++
            }
            val cancellation = currentCoroutineContext()
            val textPages = layoutReaderText(sources, width, height, layout.paragraphSpacingPx) { text, maxWidth ->
                cancellation.ensureActive()
                layout.measurer.measure(text, layout.style, constraints = Constraints(maxWidth = maxWidth))
            }
            for (fragments in textPages) {
                currentCoroutineContext().ensureActive()
                pages += ReaderPage(component, fragments.map { ReaderContentRange(it.componentIndex, it.start, it.end) }) { modifier ->
                    val colors = LocalReaderStyle.current
                    ReaderTextFragments(fragments, layout.style, readerTextColor(colors.textColor, colors.textDarkColor), modifier)
                }
            }
        } else {
            val parts = if (component is AbstractDivisibleContentComponent<*, *>) component.split(height, width)
                else listOf(component)
            parts.forEachIndexed { partIndex, part ->
                pages += ReaderPage(part, listOf(ReaderContentRange(index, partIndex, partIndex + 1))) { part.Content(it) }
            }
            index++
        }
    }
    return pages
}
