package indi.renakoni.nextvol.data.image

import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import java.security.MessageDigest
import javax.inject.Inject

/** Safe to pass through UI/navigation. Credentials are resolved only during execution. */
data class SourceImage(val book: SourceBookId, val uri: String, val cover: Boolean = false,
    val preferDownloaded: Boolean = true, val chapterId: String? = null)

/** Runs before Coil's memory/disk lookup, so URL equality never implies source equality. */
class SourceImageInterceptor @Inject constructor(
    private val registry: WebSourceRegistry,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    private val downloads: BookDownloadStore,
) : Interceptor {
    // Only opaque cache keys are persisted. A removed source may read its last cached image,
    // but cannot start a network request or borrow another source's credentials.
    private val cacheKeys = context.getSharedPreferences("source_image_cache_keys", android.content.Context.MODE_PRIVATE)

    internal fun coverVersion(image: SourceImage): Pair<String?, Long> =
        registry.sources.value.firstOrNull { it.metadata.id == image.book.sourceId }?.metadata?.revision to
            (if (image.preferDownloaded) downloads.coverWriteGeneration(image.book) else 0L)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val image = chain.request.data as? SourceImage ?: return chain.proceed()
        if (image.preferDownloaded) downloads.image(image)?.let { file ->
            val key = sourceImageCacheKey(image, "download:${file.absolutePath}:${file.lastModified()}:${file.length()}")
            return chain.withRequest(chain.request.newBuilder().data(file)
                .memoryCacheKey(key).diskCacheKey(key).build()).proceed()
        }
        if (android.net.Uri.parse(image.uri).scheme in setOf("file", "content", "android.resource")) {
            return chain.withRequest(chain.request.newBuilder().data(image.uri).build()).proceed()
        }
        val indexKey = sourceImageCacheKey(image, "")
        val runtime = when (val result = registry.resolve(image.book.sourceId)) {
            is SourceResolution.Ready -> result.runtime
            is SourceResolution.Missing -> {
                val cachedKey = cacheKeys.getString(indexKey, null) ?: error("Image source is not registered")
                // A dedicated fetcher reads the recorded disk entry and fails on cache miss.
                val cached = chain.withRequest(chain.request.newBuilder().data(BoundSourceImage(image, cachedKey, null))
                    .memoryCacheKey(cachedKey).diskCacheKey(cachedKey)
                    .networkCachePolicy(CachePolicy.DISABLED).build()).proceed()
                return cached
            }
            is SourceResolution.Unavailable -> throw result.cause
        }
        val result = runtime.execute {
            val headers = runtime.imageHeaders()
            val key = sourceImageCacheKey(image, runtime.metadata.revision)
            val result = chain.withRequest(chain.request.newBuilder()
                .data(if (runtime.hasImageProvider) BoundSourceImage(image, key, runtime) else image.uri)
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .httpHeaders(NetworkHeaders.Builder().apply {
                    headers.forEach { (name, value) -> add(name, value) }
                }.build())
                .build()).proceed()
            result to key
        }
        if (result.first is SuccessResult) cacheKeys.edit().putString(indexKey, result.second).apply()
        return result.first
    }
}

/** Images belong to a source/book/revision, never an account or its request credentials. */
internal fun sourceImageCacheKey(image: SourceImage, revision: String): String {
    val fields = listOf(image.book.sourceId.namespace, image.book.sourceId.id, image.book.remoteId,
        image.cover.toString(), revision, image.uri)
    val bytes = BookIdentity.encode("image", fields).toByteArray(Charsets.UTF_8)
    return "source-image-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
