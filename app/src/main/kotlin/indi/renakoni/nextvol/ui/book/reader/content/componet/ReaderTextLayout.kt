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
    val pages = mutableListOf<List<ReaderTextFragment>>()
    var page = mutableListOf<ReaderTextFragment>()
    var usedHeight = 0
    val safeHeight = height.coerceAtLeast(1)
    fun nextPage() {
        if (page.isNotEmpty()) pages += page.toList()
        page = mutableListOf()
        usedHeight = 0
    }
    sources.forEach { source ->
        var paragraphStart = 0
        while (paragraphStart < source.text.length) {
            val newline = source.text.indexOf('\n', paragraphStart)
            val paragraphEnd = if (newline < 0) source.text.length else newline
            val contentEnd = if (newline >= 0 && paragraphEnd > paragraphStart && source.text[paragraphEnd - 1] == '\r')
                paragraphEnd - 1 else paragraphEnd
            val nextStart = if (newline < 0) source.text.length else newline + 1
            val paragraph = source.text.substring(paragraphStart, contentEnd)
            val measured = measure(paragraph, width.coerceAtLeast(1))
            var firstLine = 0
            while (firstLine < measured.lineCount) {
                var spacing = if (firstLine == 0 && (page.isNotEmpty() ||
                    keepParagraphSpacingAtPageBreaks && pages.isNotEmpty())) paragraphSpacing else 0
                val top = measured.getLineTop(firstLine)
                fun lineHeight(lastLine: Int) = ceil(measured.getLineBottom(lastLine) - top).toInt().coerceAtLeast(1)
                if (page.isNotEmpty() && lineHeight(firstLine) + spacing > safeHeight - usedHeight) {
                    nextPage()
                    if (!keepParagraphSpacingAtPageBreaks) spacing = 0
                }
                var lastLine = firstLine
                while (lastLine + 1 < measured.lineCount &&
                    lineHeight(lastLine + 1) <= safeHeight - usedHeight - spacing
                ) lastLine++
                // Even a viewport shorter than one line consumes a complete line.
                val start = measured.getLineStart(firstLine)
                val end = if (lastLine == measured.lineCount - 1) paragraph.length
                    else measured.getLineStart(lastLine + 1)
                val fragmentHeight = lineHeight(lastLine)
                page += ReaderTextFragment(
                    source.componentIndex,
                    paragraphStart + start,
                    if (lastLine == measured.lineCount - 1) nextStart else paragraphStart + end,
                    paragraph.substring(start, end).let {
                        if (lastLine < measured.lineCount - 1) it.removeSuffix("\u2028") else it
                    }, spacing, fragmentHeight,
                    (firstLine..lastLine).map { paragraphStart + measured.getLineStart(it) },
                    (firstLine..lastLine).map { (measured.getLineTop(it) - top).toInt() },
                )
                usedHeight += spacing + fragmentHeight
                firstLine = lastLine + 1
                if (firstLine < measured.lineCount) nextPage()
            }
            paragraphStart = nextStart
        }
    }
    nextPage()
    pages
}

internal fun layoutReaderText(
    sources: List<ReaderTextSource>, width: Int, height: Int, paragraphSpacing: Int,
    style: TextStyle, measurer: TextMeasurer,
): List<List<ReaderTextFragment>> = layoutReaderText(sources, width, height, paragraphSpacing) { text, maxWidth ->
    measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth))
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
