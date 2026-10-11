package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderTextLayoutInput
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragment
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragmentContent
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextSelection
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextSource
import indi.renakoni.nextvol.ui.book.reader.content.componet.MeasuredReaderParagraph
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextWorkObserver
import indi.renakoni.nextvol.ui.book.reader.content.componet.measureReaderText
import indi.renakoni.nextvol.ui.book.reader.content.componet.splitReaderText
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/** Full chapter geometry is cheap to scroll; only nearby fragments become Compose text nodes. */
internal class ScrollTextLayout(val fragments: List<ReaderTextFragment>, val style: TextStyle) {
    val offsets = IntArray(fragments.size + 1).also { offsets ->
        fragments.forEachIndexed { index, fragment ->
            offsets[index + 1] = offsets[index] + fragment.spacingBefore + fragment.height
        }
    }
    val height: Int get() = offsets.last()

    fun offsetFor(anchor: ReaderContentAnchor): Int? {
        val index = fragments.binarySearch { fragment ->
            when {
                fragment.componentIndex != anchor.componentIndex -> fragment.componentIndex.compareTo(anchor.componentIndex)
                anchor.offset < fragment.start -> 1
                anchor.offset >= fragment.end -> -1
                else -> 0
            }
        }
        if (index < 0) return null
        val fragment = fragments[index]
        val line = fragment.lineStarts.indexOfLast { it <= anchor.offset }.coerceAtLeast(0)
        return offsets[index] + fragment.spacingBefore + (fragment.lineTops.getOrNull(line) ?: 0)
    }

    fun anchorAt(y: Int): ReaderContentAnchor? {
        if (fragments.isEmpty()) return null
        val index = (offsets.binarySearch(y.coerceIn(0, (height - 1).coerceAtLeast(0))).let {
            if (it >= 0) it else -it - 2
        }).coerceIn(fragments.indices)
        val fragment = fragments[index]
        val local = y - offsets[index] - fragment.spacingBefore
        val line = fragment.lineTops.indexOfLast { it <= local }.coerceAtLeast(0)
        return ReaderContentAnchor(fragment.componentIndex, fragment.lineStarts.getOrElse(line) { fragment.start })
    }

    fun visibleRange(top: Int, bottom: Int): IntRange {
        if (bottom <= top || bottom <= 0 || top >= height || fragments.isEmpty()) return IntRange.EMPTY
        fun indexAt(y: Int): Int {
            val found = offsets.binarySearch(y.coerceIn(0, height - 1))
            return (if (found >= 0) found else -found - 2).coerceIn(fragments.indices)
        }
        return indexAt(top)..indexAt(bottom - 1)
    }
}

/** Adjacent text shares one viewport; images and extension components retain their own layout. */
internal class ScrollContentBlock(val componentIndex: Int, val text: ScrollTextLayout?) {
    // Chapter-local geometry must not become a per-frame, whole-chapter SnapshotStateMap write.
    var top by mutableIntStateOf(-1)
}

internal class PreparedScrollChapter(
    val content: ChapterContentUiState,
    text: Map<Int, List<ReaderTextFragment>>,
    val layout: ReaderTextLayoutInput? = null,
    val size: IntSize = IntSize.Zero,
) {
    val blocks = buildList {
        val components = content.content.withIndex()
            .filterNot { (it.value as? SimpleTextComponent)?.data?.text?.isEmpty() == true }
        var cursor = 0
        while (cursor < components.size) {
            val first = components[cursor].index
            if (text[first] == null) {
                add(ScrollContentBlock(first, null))
                cursor++
                continue
            }
            val fragments = mutableListOf<ReaderTextFragment>()
            while (cursor < components.size) {
                val next = text[components[cursor].index] ?: break
                next.forEachIndexed { index, fragment ->
                    // Preserve the existing per-component line cuts and inter-component Spacer.
                    fragments += if (index == 0 && fragments.isNotEmpty())
                        fragment.copy(spacingBefore = layout?.paragraphSpacingPx ?: 0) else fragment
                }
                cursor++
            }
            add(ScrollContentBlock(first, ScrollTextLayout(fragments, layout?.style ?: TextStyle.Default)))
        }
    }
    private val blockByComponent = IntArray(content.content.size) { -1 }.also { indices ->
        blocks.forEachIndexed { index, block ->
            indices[block.componentIndex] = index
            block.text?.fragments?.forEach { indices[it.componentIndex] = index }
        }
    }

    private fun blockFor(componentIndex: Int): ScrollContentBlock? =
        blockByComponent.getOrNull(componentIndex)?.let { blocks.getOrNull(it) }

    fun isPositioned(componentIndex: Int): Boolean = blockFor(componentIndex)?.top?.let { it >= 0 } == true

    fun offsetFor(anchor: ReaderContentAnchor): Int? {
        val block = blockFor(anchor.componentIndex)?.takeIf { it.top >= 0 } ?: return null
        return block.text?.offsetFor(anchor)?.plus(block.top)
            ?: block.top.takeIf { content.content.getOrNull(anchor.componentIndex) !is SimpleTextComponent }
    }

    fun anchorAt(y: Int): ReaderContentAnchor? {
        // Blocks are ordered by chapter position; pure text chapters have just one block.
        var current: ScrollContentBlock? = null
        for (block in blocks) {
            if (block.top < 0) continue
            if (block.top > y && current != null) break
            current = block
            if (block.top > y) break
        }
        val block = current ?: return null
        return block.text?.anchorAt(y - block.top) ?: ReaderContentAnchor(block.componentIndex, 0)
    }
}

private data class ScrollMeasurementInput(
    val sources: List<ReaderTextSource>,
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val width: Int,
)

/** Each chapter owns only its latest line geometry. Cancellation never publishes stale work. */
@Composable
internal fun rememberPreparedScrollChapter(
    content: ChapterContentUiState?,
    layout: ReaderTextLayoutInput?,
    width: Int,
    viewportHeight: Int,
): PreparedScrollChapter? {
    if (content == null) return null
    if (layout == null) return remember(content) { PreparedScrollChapter(content, emptyMap()) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val workObserver = LocalReaderTextWorkObserver.current
    val sources = remember(content.content) {
        content.content.mapIndexedNotNull { index, component ->
            (component as? SimpleTextComponent)?.let { ReaderTextSource(index, it.data.text) }
        }
    }
    val input = ScrollMeasurementInput(sources, layout.style, density, direction, width)
    var measured by remember(content.id) {
        mutableStateOf<Pair<ScrollMeasurementInput, Map<Int, List<MeasuredReaderParagraph>>>?>(null)
    }
    var prepared by remember(content.id) { mutableStateOf<PreparedScrollChapter?>(null) }
    LaunchedEffect(content, layout, density, direction, width, viewportHeight) {
        if (width <= 0 || viewportHeight <= 0) return@LaunchedEffect
        val reusable = measured?.takeIf { it.first == input }?.second
        val result = withContext(Dispatchers.Default) {
            val cancellation = currentCoroutineContext()
            val paragraphs = reusable ?: run {
                val measurer = TextMeasurer(createFontFamilyResolver(context), density, direction, cacheSize = 0)
                measureReaderText(sources, width) { paragraph, maxWidth ->
                    cancellation.ensureActive()
                    workObserver?.invoke("shape")
                    measurer.measure(paragraph, layout.style, constraints = Constraints.fixedWidth(maxWidth))
                }.groupBy { it.componentIndex }
            }
            val text = paragraphs.mapValues { (_, paragraphs) ->
                cancellation.ensureActive()
                splitReaderText(
                    paragraphs, viewportHeight,
                    layout.paragraphSpacingPx, keepParagraphSpacingAtPageBreaks = true,
                ).flatten()
            }
            paragraphs to PreparedScrollChapter(content, text, layout, IntSize(width, viewportHeight))
        }
        measured = input to result.first
        prepared = result.second
    }
    // Keep the old geometry visible while settings/content are being prepared. The chapter item
    // keeps its identity and offset; first entry remains a loading item until geometry is available.
    return prepared
}

@Composable
internal fun ScrollTextContent(layout: ScrollTextLayout, color: Color, modifier: Modifier) {
    // Retain overlapping paragraphs across both placement-only and full measure passes.
    // Only nearby fragments retain content lambdas; offscreen slots are disposed by Compose.
    val contents = remember(layout, color) { mutableMapOf<Int, @Composable () -> Unit>() }
    ReaderTextSelection {
        SubcomposeLayout(modifier) { constraints ->
            layout(constraints.maxWidth, layout.height) {
                // Reading coordinates here also reruns placement when an ancestor scrolls without
                // remeasurement. Compose supplies coordinates on the real (non-alignment) placement.
                val positioned = coordinates ?: return@layout
                val viewport = positioned.localBoundingBoxOf(positioned.findRootCoordinates(), clipBounds = false)
                val onScreen = layout.visibleRange(floor(viewport.top).toInt(), ceil(viewport.bottom).toInt())
                // Bound overscan by fragments: a whole extra screen eagerly creates dozens of
                // offscreen paragraphs when a previous chapter first re-enters the lazy list.
                val visible = if (onScreen.isEmpty()) onScreen else
                    (onScreen.first - 1).coerceAtLeast(0)..(onScreen.last + 1).coerceAtMost(layout.fragments.lastIndex)
                // Compose and measure in this placement, before drawing. A state write from
                // onGloballyPositioned would leave the current frame with the old/empty text window.
                contents.keys.removeAll { it !in visible }
                for (index in visible) {
                    val fragment = layout.fragments[index]
                    val content = contents.getOrPut(index) {
                        { ReaderTextFragmentContent(fragment, layout.style, color) }
                    }
                    // A slot always identifies the same geometry and paragraph, including
                    // when lookahead and approach have different visible windows.
                    subcompose(layout to index, content).single()
                        .measure(Constraints.fixedWidth(constraints.maxWidth))
                        .placeRelative(0, layout.offsets[index] + fragment.spacingBefore)
                }
            }
        }
    }
}
