package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import indi.renakoni.nextvol.ui.book.reader.ReaderBodyGeometry
import indi.renakoni.nextvol.ui.book.reader.readerBodyGeometry
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderImageScale
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderChapterImages
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderImageResource
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlin.math.roundToInt

/** A page is a real leaf; a pager item is a same-chapter screen of one or two leaves. */
internal data class ReaderLeafMapping(val leafCount: Int, val columns: Int) {
    init { require(leafCount >= 0 && columns in 1..2) }
    val screenCount: Int get() = leafCount / columns + if (leafCount % columns == 0) 0 else 1
    fun leavesOnScreen(screen: Int): IntRange =
        if (screen !in 0 until screenCount) IntRange.EMPTY
        else screen * columns..minOf((screen + 1) * columns, leafCount) - 1
    fun screenForLeaf(leaf: Int): Int = if (leaf in 0 until leafCount) leaf / columns else -1
    fun progress(screen: Int): Float? = leavesOnScreen(screen).takeUnless { it.isEmpty() }
        ?.let { (it.last + 1) / leafCount.toFloat() }
    fun screenForProgress(progress: Float): Int = if (leafCount == 0) 0 else
        screenForLeaf(((leafCount * progress.coerceIn(0f, 1f)).roundToInt() - 1).coerceIn(0, leafCount - 1))
}

/** Mapping and pager are published together, including prepared adjacent chapters. */
internal class ReaderSpreadPagerState(
    val leaves: ReaderLeafMapping,
    initialScreen: Int = 0,
) : PagerState(initialScreen, 0f) {
    override val pageCount: Int get() = leaves.screenCount
}

internal val PagerState.readerLeaves: ReaderLeafMapping
    get() = (this as? ReaderSpreadPagerState)?.leaves ?: ReaderLeafMapping(pageCount, 1)

@Composable
internal fun ReaderSpreadContent(
    pages: List<AbstractContentComponent<*>>,
    mapping: ReaderLeafMapping,
    screen: Int,
    geometry: ReaderBodyGeometry,
) {
    Layout(
        modifier = Modifier.fillMaxSize().readerBodyGeometry(geometry),
        content = {
            mapping.leavesOnScreen(screen).forEachIndexed { order, leaf ->
                key(leaf) {
                    Box(Modifier.testTag("reader-leaf-$leaf").semantics { isTraversalGroup = true; traversalIndex = order.toFloat() }) {
                        val component = (pages.getOrNull(leaf) as? ReaderPage)?.anchor?.componentIndex
                        CompositionLocalProvider(LocalReaderImageScale provides ContentScale.Fit,
                            LocalReaderImageResource provides LocalReaderChapterImages.current[component]) {
                            pages.getOrNull(leaf)?.Content(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        },
    ) { measurables, _ ->
        val leaves = measurables.map { it.measure(Constraints.fixed(geometry.leafSize.width, geometry.leafSize.height)) }
        layout(geometry.widthPx, geometry.leafSize.height) {
            leaves.forEachIndexed { index, leaf ->
                leaf.placeRelative(index * (geometry.leafSize.width + geometry.gutterPx), 0)
            }
        }
    }
}
