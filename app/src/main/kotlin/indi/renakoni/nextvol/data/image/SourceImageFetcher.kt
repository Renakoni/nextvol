package indi.renakoni.nextvol.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.data.web.SourceRuntime
import io.nightfish.lightnovelreader.api.error.WebRequestError
import okio.Buffer
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A missing runtime is an explicit cache-only request, never an HTTP URL fallback. */
internal data class BoundSourceImage(val image: SourceImage, val key: String, val runtime: SourceRuntime?)

/** Preserve actionable categories through Coil without carrying private source error text. */
internal class SourceImageRequestException(error: WebRequestError) : IOException("Source image request failed") {
    val kind = error.kind
    val networkFailure = error.throwable is IOException && error.throwable !is SourceImageHttpException
    val storageFailure = (error.throwable as? hnovel.content.SourceContentException)?.storageFailure
    val contentError = (error.throwable as? hnovel.content.SourceContentException)?.code
    val retry = (error.throwable as? hnovel.content.SourceContentException)?.retry
        ?: (error.throwable as? SourceImageHttpException)?.retry
    val httpStatus = (error.throwable as? hnovel.content.SourceContentException)?.httpStatus
        ?: (error.throwable as? SourceImageHttpException)?.httpStatus
}

internal class SourceImageFetcher(private val request: BoundSourceImage, private val options: Options,
    private val cache: DiskCache?) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        if (options.diskCachePolicy.readEnabled) cache?.openSnapshot(request.key)?.let { snapshot ->
            return SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, request.key, snapshot,
                ValidatedSourceImage), null, DataSource.DISK)
        }
        val runtime = request.runtime ?: throw IOException("Source image is not cached")
        val bytes = runtime.execute {
            runtime.imageRequests.get(request.key, force = !options.diskCachePolicy.readEnabled) {
                // A previous fetch may have committed between the first lookup and joining.
                if (options.diskCachePolicy.readEnabled) cache?.openSnapshot(request.key)?.use { snapshot ->
                    return@get SourceImageRequests.Bytes(cache.fileSystem.read(snapshot.data) { readByteArray() }, DataSource.DISK)
                }
                SourceImageRequests.Bytes(fetchAndStore(runtime), DataSource.NETWORK)
            }
        }
        return SourceFetchResult(ImageSource(Buffer().write(bytes.value), options.fileSystem, ValidatedSourceImage), null, bytes.dataSource)
    }

    private suspend fun fetchAndStore(runtime: SourceRuntime): ByteArray {
        val result = runtime.imageBytes(request.image.book.remoteId, request.image.uri, request.image.cover)
        val bytes = result.getOrElse { throw SourceImageRequestException(it) }
        validateOriginalImage(bytes)
        if (options.diskCachePolicy.writeEnabled && cache != null) {
            cache.openEditor(request.key)?.let { editor ->
                try {
                    cache.fileSystem.write(editor.metadata) { }
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    currentCoroutineContext().ensureActive()
                    runtime.checkAvailable()
                    editor.commit()
                } catch (failure: Throwable) {
                    editor.abort()
                    if (failure !is IOException) throw failure
                }
            }
        }
        return bytes
    }

    class Factory : Fetcher.Factory<BoundSourceImage> {
        override fun create(data: BoundSourceImage, options: Options, imageLoader: ImageLoader): Fetcher =
            SourceImageFetcher(data, options, imageLoader.diskCache)
    }
}
