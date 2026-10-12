package indi.renakoni.nextvol.ui.book.reader.content.componet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSelectionState
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechRanges
import indi.renakoni.nextvol.ui.book.reader.LocalReaderSpeechHighlight
import androidx.compose.ui.graphics.isSpecified
import indi.renakoni.nextvol.ui.book.reader.content.readerTrace
import kotlin.math.ceil

internal data class ReaderTextSource(val componentIndex: Int, val text: String)

/** The benchmark host observes actual draws; normal readers have no observer or extra modifiers. */
internal val LocalReaderTextDrawObserver = staticCompositionLocalOf<
    ((ReaderTextFragment, TextLayoutResult, LayoutCoordinates) -> Unit)?
> { null }

/** Optional benchmark counters; normal readers have no observer or extra layout modifier. */
internal val LocalReaderTextWorkObserver = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal data class ReaderTextFragment(
    val componentIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val spacingBefore: Int,
    val height: Int,
    val lineStarts: List<Int> = emptyList(),
    val lineTops: List<Int> = emptyList(),
)

/** Immutable line geometry, independent of page height, paragraph gaps and screen coordinates. */
internal class MeasuredReaderParagraph(
    val componentIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val lineStarts: IntArray,
    val lineTops: FloatArray,
    val lineBottoms: FloatArray,
)

internal fun measureReaderText(
    sources: List<ReaderTextSource>,
    width: Int,
    measure: (String, Int) -> TextLayoutResult,
): List<MeasuredReaderParagraph> = buildList {
    sources.forEach { source ->
        var start = 0
        while (start < source.text.length) {
            val newline = source.text.indexOf('\n', start)
            val end = if (newline < 0) source.text.length else newline
            val contentEnd = if (newline >= 0 && end > start && source.text[end - 1] == '\r') end - 1 else end
            val nextStart = if (newline < 0) source.text.length else newline + 1
            val text = source.text.substring(start, contentEnd)
            val measured = measure(text, width.coerceAtLeast(1))
            add(MeasuredReaderParagraph(source.componentIndex, start, nextStart, text,
                IntArray(measured.lineCount) { measured.getLineStart(it) },
                FloatArray(measured.lineCount) { measured.getLineTop(it) },
                FloatArray(measured.lineCount) { measured.getLineBottom(it) }))
            start = nextStart
        }
    }
}

/** Legacy simple_text uses LF/CRLF as paragraph boundaries; U+2028 remains a soft break.
 * Empty paragraphs are explicit blank lines, and a terminal separator adds no phantom paragraph.
 */
internal fun layoutReaderText(
    sources: List<ReaderTextSource>,
    width: Int,
    height: Int,
    paragraphSpacing: Int,
    keepParagraphSpacingAtPageBreaks: Boolean = false,
    measure: (String, Int) -> TextLayoutResult,
): List<List<ReaderTextFragment>> = readerTrace("reader.layout") {
    splitReaderText(measureReaderText(sources, width, measure), height, paragraphSpacing, keepParagraphSpacingAtPageBreaks)
}

internal fun splitReaderText(
    paragraphs: List<MeasuredReaderParagraph>,
    height: Int,
    paragraphSpacing: Int,
    keepParagraphSpacingAtPageBreaks: Boolean = false,
): List<List<ReaderTextFragment>> {
    val pages = mutableListOf<List<ReaderTextFragment>>()
    var page = mutableListOf<ReaderTextFragment>()
    var usedHeight = 0
    val safeHeight = height.coerceAtLeast(1)
    fun nextPage() {
        if (page.isNotEmpty()) pages += page.toList()
        page = mutableListOf()
        usedHeight = 0
    }
    paragraphs.forEach { paragraph ->
        val lineCount = paragraph.lineStarts.size
        var firstLine = 0
        while (firstLine < lineCount) {
            var spacing = if (firstLine == 0 && (page.isNotEmpty() ||
                keepParagraphSpacingAtPageBreaks && pages.isNotEmpty())) paragraphSpacing else 0
            val top = paragraph.lineTops[firstLine]
            fun lineHeight(lastLine: Int) = ceil(paragraph.lineBottoms[lastLine] - top).toInt().coerceAtLeast(1)
            if (page.isNotEmpty() && lineHeight(firstLine) + spacing > safeHeight - usedHeight) {
                nextPage()
                if (!keepParagraphSpacingAtPageBreaks) spacing = 0
            }
            var lastLine = firstLine
            while (lastLine + 1 < lineCount &&
                lineHeight(lastLine + 1) <= safeHeight - usedHeight - spacing
            ) lastLine++
            // Even a viewport shorter than one line consumes a complete line.
            val start = paragraph.lineStarts[firstLine]
            val end = if (lastLine == lineCount - 1) paragraph.text.length
                else paragraph.lineStarts[lastLine + 1]
            val fragmentHeight = lineHeight(lastLine)
            page += ReaderTextFragment(
                paragraph.componentIndex,
                paragraph.start + start,
                if (lastLine == lineCount - 1) paragraph.end else paragraph.start + end,
                paragraph.text.substring(start, end).let {
                    if (lastLine < lineCount - 1) it.removeSuffix("\u2028") else it
                }, spacing, fragmentHeight,
                (firstLine..lastLine).map { paragraph.start + paragraph.lineStarts[it] },
                (firstLine..lastLine).map { (paragraph.lineTops[it] - top).toInt() },
            )
            usedHeight += spacing + fragmentHeight
            firstLine = lastLine + 1
            if (firstLine < lineCount) nextPage()
        }
    }
    nextPage()
    return pages
}

internal fun layoutReaderText(
    sources: List<ReaderTextSource>, width: Int, height: Int, paragraphSpacing: Int,
    style: TextStyle, measurer: TextMeasurer,
): List<List<ReaderTextFragment>> = layoutReaderText(sources, width, height, paragraphSpacing) { text, maxWidth ->
    // Fragments fill this width when drawn; no intrinsic shrink-to-fit measurement is needed.
    measurer.measure(text, style, constraints = Constraints.fixedWidth(maxWidth))
}

@Composable
internal fun ReaderTextFragments(
    fragments: List<ReaderTextFragment>, style: TextStyle, color: Color, modifier: Modifier,
) {
    val density = LocalDensity.current
    ReaderTextSelection {
        Column(modifier) {
            fragments.forEach { fragment ->
                key(fragment.componentIndex, fragment.start) {
                    if (fragment.spacingBefore > 0) Spacer(Modifier.height(with(density) { fragment.spacingBefore.toDp() }))
                    ReaderTextFragmentContent(fragment, style, color)
                }
            }
        }
    }
}

/** One selection scope also covers fragments hosted in separate scroll subcomposition slots. */
@Composable
internal fun ReaderTextSelection(content: @Composable () -> Unit) {
    val readerSelection = LocalReaderSelectionState.current
    val selectionState = rememberSelectionState()
    DisposableEffect(readerSelection, selectionState) {
        readerSelection.register(selectionState)
        onDispose { readerSelection.unregister(selectionState) }
    }
    SelectionContainer(state = selectionState, content = content)
}

@Composable
internal fun ReaderTextFragmentContent(fragment: ReaderTextFragment, style: TextStyle, color: Color) {
    val readerSelection = LocalReaderSelectionState.current
    val drawObserver = LocalReaderTextDrawObserver.current
    val workObserver = LocalReaderTextWorkObserver.current
    if (workObserver != null) SideEffect { workObserver("compose") }
    val speechRanges = LocalReaderSpeechRanges.current
    val paperHighlight = LocalReaderSpeechHighlight.current
    val highlight = if (paperHighlight.isSpecified) paperHighlight else color.copy(alpha = 0.13f)
    var measured by remember { mutableStateOf<TextLayoutResult?>(null) }
    val observation = if (drawObserver == null) Modifier else {
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Modifier.onGloballyPositioned { coordinates = it }
            .drawWithContent {
                drawContent()
                val positioned = coordinates
                val textLayout = measured
                if (positioned?.isAttached == true && textLayout != null) {
                    drawObserver(fragment, textLayout, positioned)
                }
            }
    }
    val range = speechRanges.firstOrNull { it.componentIndex == fragment.componentIndex }
    val start = ((range?.start ?: fragment.end) - fragment.start).coerceIn(0, fragment.text.length)
    val end = ((range?.end ?: fragment.start) - fragment.start).coerceIn(0, fragment.text.length)
    Text(
        text = fragment.text, style = style, color = color,
        modifier = Modifier.fillMaxWidth().then(observation)
            .then(if (workObserver == null) Modifier else Modifier.layout { measurable, constraints ->
                workObserver("measure")
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }).drawBehind {
                if (start < end && !readerSelection.hasSelection) measured?.let {
                    drawPath(it.getPathForRange(start, end), highlight)
                }
            },
        onTextLayout = { measured = it },
    )
}
