package indi.renakoni.nextvol.data.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.data.web.SourceUnavailableException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        registry.awaitSourceRegistration(image.book.sourceId)
        val listing = registry.sources.value.firstOrNull { it.metadata.id == image.book.sourceId }
        if (listing?.status == SourceStatus.Failed) {
            val failure = registry.resolve(image.book.sourceId)
            if (failure is SourceResolution.Unavailable) throw failure.cause
        }
        val key = listing?.let { sourceImageCacheKey(image, it.metadata.revision) }
            ?: cacheKeys.getString(indexKey, null) ?: error("Image source is not registered")
        val data = if (listing == null) BoundSourceImage(image, key, null) else PendingSourceImage(
            image, key, listing.generation, registry, cacheKeys.getBoolean("$key#provider", false),
        ) { provider -> cacheKeys.edit().putBoolean("$key#provider", provider).apply() }
        val retirement = listing?.let { registry.cancelOnRetirement(it.metadata.id, it.generation,
            requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])) }
        try {
            val result = chain.withRequest(chain.request.newBuilder().data(data)
                .memoryCacheKey(key).diskCacheKey(key).build()).proceed()
            currentCoroutineContext().ensureActive()
            if (listing != null && registry.sources.value.none {
                it.metadata.id == listing.metadata.id && it.generation == listing.generation && it.status != SourceStatus.Failed
            }) throw SourceUnavailableException(image.book.sourceId)
            if (result is SuccessResult) cacheKeys.edit().putString(indexKey, key).apply()
            return result
        } finally { retirement?.dispose() }
    }
}

/** Images belong to a source/book/revision, never an account or its request credentials. */
internal fun sourceImageCacheKey(image: SourceImage, revision: String): String {
    val fields = listOf(image.book.sourceId.namespace, image.book.sourceId.id, image.book.remoteId,
        image.cover.toString(), revision, image.uri)
    val bytes = BookIdentity.encode("image", fields).toByteArray(Charsets.UTF_8)
    return "source-image-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
