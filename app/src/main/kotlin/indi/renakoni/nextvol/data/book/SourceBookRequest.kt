package indi.renakoni.nextvol.data.book

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.SourceRuntime
import indi.renakoni.nextvol.data.web.SourceUnavailableException
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** Run after a successful detail/TOC/content parse, before any host write. */
internal suspend fun SourceRuntime.persistCanonicalBook(
    book: SourceBookId, local: LocalBookDataSource, downloads: BookDownloadStore,
): Result<SourceBookId, WebRequestError> = execute {
    var canonical = SourceBookId(book.sourceId, canonicalBookId(book.remoteId))
    if (canonical == book) return@execute Ok(local.aliases.resolve(book))
    try {
        coroutineBinding {
            repeat(16) {
                val information = canonical.bind(getBookInformation(canonical.remoteId).bind())
                val volumes = canonical.bind(getBookVolumes(canonical.remoteId).bind())
                // Parsing the target can propose another identity. Persist only its final details.
                val resolved = SourceBookId(book.sourceId, canonicalBookId(canonical.remoteId))
                if (resolved != canonical) {
                    canonical = resolved
                    return@repeat
                }
                checkAvailable()
                downloads.mergeIdentity(book, canonical, volumes) {
                    checkAvailable()
                    local.aliases.merge(book, canonical, information, volumes)
                    checkAvailable()
                }
                return@coroutineBinding canonical
            }
            error("Book identity did not stabilize")
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure !is IllegalStateException && failure !is IllegalArgumentException && failure !is IOException) throw failure
        Err(WebRequestError("Book identity could not be merged",
            "The original reading data was retained. Refresh the source directory before retrying.", failure))
    }
}

/** Missing sources retain their local namespace; requests never fall back to another source. */
internal suspend fun <T> WebSourceRegistry.request(
    book: SourceBookId,
    block: suspend (SourceRuntime) -> Result<T, WebRequestError>,
): Result<T, WebRequestError> = when (val resolution = resolve(book.sourceId)) {
    is SourceResolution.Ready -> try {
        block(resolution.runtime)
    } catch (failure: Exception) {
        // Caller lifecycle cancellation always wins, even if this registration retired too.
        currentCoroutineContext().ensureActive()
        // Only a retired runtime's cancellation/unavailability becomes SourceUnavailable.
        // IOException and other provider failures must propagate even after retirement:
        // relabeling them would hide the actual fault and give callers the wrong recovery path.
        if (resolution.runtime.isAvailable ||
            (failure !is CancellationException && failure !is SourceUnavailableException)) throw failure
        Err(WebRequestError("Data source unavailable", "Source was removed or replaced (${book.sourceId})", failure, WebRequestErrorKind.SourceUnavailable))
    }
    is SourceResolution.Missing -> Err(WebRequestError("Data source not found", "Source is not registered (${book.sourceId})", kind = WebRequestErrorKind.SourceUnavailable))
    is SourceResolution.Unavailable -> Err(WebRequestError("Data source unavailable", "Source initialization failed (${book.sourceId})", resolution.cause, WebRequestErrorKind.SourceUnavailable))
}
