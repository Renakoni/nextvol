package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.net.Uri
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import indi.renakoni.nextvol.ui.book.reader.ReaderBodyGeometry
import indi.renakoni.nextvol.ui.book.reader.ReaderTextLayoutInput
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class FlipPaginationInput(
    val chapterId: String,
    val content: List<AbstractContentComponent<*>>,
    val geometry: ReaderBodyGeometry?,
    val density: Density,
    val layoutDirection: LayoutDirection,
    val fontSize: Float,
    val fontLineHeight: Float,
    val fontWeight: Float,
    val fontFamilyUri: Uri,
    val textLayout: ReaderTextLayoutInput? = null,
)

/** Runs one cancellable pagination request and publishes only its newest result. */
internal class FlipPaginationCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val paginate: suspend (
        List<AbstractContentComponent<*>>,
        Int,
        Int,
    ) -> List<AbstractContentComponent<*>> = ::paginateComponents,
) {
    private var paginationJob: Job? = null
    private var requestId = 0L
    private var latestInput: Any? = null

    /** Invalidates a request when Compose has observed a new pagination input. */
    fun syncInput(input: Any) {
        if (latestInput == input) return
        latestInput = input
        requestId++
        paginationJob?.cancel()
        paginationJob = null
    }

    fun submit(
        input: Any,
        components: List<AbstractContentComponent<*>>,
        height: Int,
        width: Int,
        onError: (Exception) -> Unit = { throw it },
        onComplete: (List<AbstractContentComponent<*>>) -> Unit,
    ) {
        syncInput(input)
        val request = ++requestId
        paginationJob?.cancel()
        paginationJob = scope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    val layout = (input as? FlipPaginationInput)?.textLayout
                    if (layout != null) paginateReaderComponents(components, height, width, layout)
                    else paginate(components, height, width)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == requestId && latestInput == input) onError(error)
                return@launch
            }
            if (request == requestId && latestInput == input) onComplete(result)
        }
    }

    fun close() {
        cancelPending()
    }

    /** Invalidates an in-flight request while keeping the coordinator reusable. */
    fun cancelPending() {
        requestId++
        paginationJob?.cancel()
        paginationJob = null
    }
}
