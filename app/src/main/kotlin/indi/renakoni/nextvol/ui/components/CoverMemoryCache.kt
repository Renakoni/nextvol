package indi.renakoni.nextvol.ui.components

import android.net.Uri
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageInterceptor
import java.io.File

private const val COVER_REFERENCE = "nextvol#cover_reference"

/** Only identity metadata lives beside Coil's image; eviction also drops this reference. */
internal data class CoverMemoryIdentity(
    val bookId: String,
    val data: Any,
    val key: String?,
    val extras: Map<String, String>,
    val transformations: List<String>,
    val sourceVersion: Pair<String?, Long>?,
    val localVersion: Pair<Long, Long>?,
)

private data class CoverMemoryReference(val identity: CoverMemoryIdentity, val file: File?, val version: Pair<Long, Long>?)

internal fun ImageLoader.coverMemoryIdentity(request: ImageRequest, bookId: String): CoverMemoryIdentity {
    val image = request.data as? SourceImage
    val file = localCoverFile(image?.uri ?: request.data)
    return CoverMemoryIdentity(bookId, request.data, request.memoryCacheKey, request.memoryCacheKeyExtras,
        request.transformations.map { it.cacheKey },
        image?.let { source -> components.interceptors.filterIsInstance<SourceImageInterceptor>()
            .firstOrNull()?.coverVersion(source) }, file?.version())
}

internal fun ImageLoader.coverPlaceholder(identity: CoverMemoryIdentity): MemoryCache.Key? {
    val cache = memoryCache ?: return null
    return cache.keys.firstOrNull { key ->
        val reference = cache[key]?.extras?.get(COVER_REFERENCE) as? CoverMemoryReference
        reference != null && reference.identity == identity && reference.file?.version() == reference.version
    }
}

internal fun ImageLoader.rememberCover(identity: CoverMemoryIdentity, result: SuccessResult) {
    val key = result.memoryCacheKey ?: return
    val cache = memoryCache ?: return
    val value = cache[key] ?: return
    val file = localCoverFile(result.request.data)
    cache[key] = MemoryCache.Value(value.image, value.extras +
        (COVER_REFERENCE to CoverMemoryReference(identity, file, file?.version())))
}

private fun File.version() = lastModified() to length()

private fun localCoverFile(data: Any): File? = when (data) {
    is File -> data
    is Uri -> data.takeIf { it.scheme == "file" }?.path?.let(::File)
    is String -> Uri.parse(data).takeIf { it.scheme == "file" }?.path?.let(::File)
    else -> null
}
