package indi.renakoni.nextvol.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.Extras
import coil3.toUri
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.data.web.SourceRuntime
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.SourceUnavailableException
import io.nightfish.lightnovelreader.api.error.WebRequestError
import okio.Buffer
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A missing runtime is an explicit cache-only request, never an HTTP URL fallback. */
internal data class BoundSourceImage(val image: SourceImage, val key: String, val runtime: SourceRuntime?)

/** Resolve credentials only after Coil's normal memory lookup has missed. */
internal data class PendingSourceImage(val image: SourceImage, val key: String, val generation: Long,
    val registry: WebSourceRegistry, val provider: Boolean, val onResolved: (Boolean) -> Unit) {
    suspend fun resolve(): SourceRuntime {
        val result = registry.resolve(image.book.sourceId)
        val runtime = when (result) {
            is SourceResolution.Ready -> result.runtime
            is SourceResolution.Unavailable -> throw result.cause
            is SourceResolution.Missing -> throw SourceUnavailableException(image.book.sourceId)
        }
        if (registry.sources.value.none { it.metadata.id == image.book.sourceId && it.generation == generation })
            throw SourceUnavailableException(image.book.sourceId)
        onResolved(runtime.hasImageProvider)
        return runtime
    }
}

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
        val bytes = runtime.execute { runtime.imageRequests.get(request.key, force = !options.diskCachePolicy.readEnabled) {
            // A previous fetch may have committed between the first lookup and joining.
            if (options.diskCachePolicy.readEnabled) cache?.openSnapshot(request.key)?.use { snapshot ->
                return@get SourceImageRequests.Bytes(cache.fileSystem.read(snapshot.data) { readByteArray() }, DataSource.DISK)
            }
            SourceImageRequests.Bytes(fetchAndStore(runtime), DataSource.NETWORK)
        } }
        return SourceFetchResult(ImageSource(Buffer().write(bytes.value), options.fileSystem, ValidatedSourceImage), null, bytes.dataSource)
    }

    private suspend fun fetchAndStore(runtime: SourceRuntime): ByteArray {
        val result = runtime.imageBytes(request.image.book.remoteId, request.image.uri, request.image.cover)
        val bytes = result.getOrElse { throw SourceImageRequestException(it) }
        val size = validateOriginalImage(bytes)
        if (options.diskCachePolicy.writeEnabled && cache != null) {
            cache.openEditor(request.key)?.let { editor ->
                try {
                    cache.fileSystem.write(editor.metadata) { }
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    currentCoroutineContext().ensureActive()
                    editor.commit()
                } catch (failure: Throwable) {
                    editor.abort()
                    if (failure !is IOException) throw failure
                }
            }
        }
        SourceImageSizes(options.context).put(request.image, size)
        return bytes
    }

    class Factory : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? = when (data) {
            is BoundSourceImage -> SourceImageFetcher(data, options, imageLoader.diskCache)
            is PendingSourceImage -> Fetcher {
                val cache = imageLoader.diskCache
                val snapshot = if (data.provider && options.diskCachePolicy.readEnabled) cache?.openSnapshot(data.key) else null
                if (snapshot != null) SourceFetchResult(ImageSource(snapshot.data, cache!!.fileSystem, data.key, snapshot,
                    ValidatedSourceImage), null, DataSource.DISK)
                else {
                    val runtime = data.resolve()
                    if (runtime.hasImageProvider) SourceImageFetcher(BoundSourceImage(data.image, data.key, runtime),
                        options, imageLoader.diskCache).fetch()
                    else runtime.execute {
                            val headers = NetworkHeaders.Builder().apply {
                                runtime.imageHeaders().forEach { (name, value) -> add(name, value) }
                            }.build()
                            val httpOptions = options.copy(extras = options.extras.newBuilder()
                                .set(Extras.Key.httpHeaders, headers).build())
                            val uri = data.image.uri.toUri()
                            imageLoader.components.newFetcher(uri, httpOptions, imageLoader)?.first?.fetch()
                                ?: throw IOException("No source image fetcher")
                    }
                }
            }
            else -> null
        }
    }
}
