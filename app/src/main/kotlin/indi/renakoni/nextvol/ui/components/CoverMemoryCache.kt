package indi.renakoni.nextvol.ui.components

import android.net.Uri
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageInterceptor
import java.io.File
import java.util.WeakHashMap

private val coverReferences = WeakHashMap<ImageLoader, MutableMap<CoverMemoryIdentity, CoverMemoryReference>>()

/** The index retains keys only; images and their eviction remain owned by Coil. */
internal data class CoverMemoryIdentity(
    val image: SourceImage,
    val extras: Map<String, String>,
    val sourceVersion: Pair<String?, Long>?,
)

private data class CoverMemoryReference(val key: MemoryCache.Key, val file: File?, val version: Pair<Long, Long>?)

internal fun ImageLoader.coverMemoryIdentity(request: ImageRequest): CoverMemoryIdentity? {
    val image = request.data as? SourceImage ?: return null
    val interceptor = components.interceptors.firstOrNull { it is SourceImageInterceptor } as? SourceImageInterceptor
    return CoverMemoryIdentity(image, request.memoryCacheKeyExtras, interceptor?.coverVersion(image))
}

internal fun ImageLoader.coverPlaceholder(identity: CoverMemoryIdentity?): MemoryCache.Key? {
    val reference = synchronized(coverReferences) { coverReferences[this]?.get(identity) } ?: return null
    // Coil performs the cache lookup; an evicted key simply provides no placeholder.
    return reference.key.takeIf { reference.file?.version() == reference.version }
}

internal fun ImageLoader.rememberCover(identity: CoverMemoryIdentity?, result: SuccessResult) {
    if (identity == null) return
    val key = result.memoryCacheKey ?: return
    val cache = memoryCache ?: return
    val file = localCoverFile(result.request.data)
    synchronized(coverReferences) {
        val references = coverReferences.getOrPut(this) { mutableMapOf() }
        if (identity !in references) {
            val keys = cache.keys
            references.entries.removeAll { it.value.key == key || it.value.key !in keys }
        }
        references[identity] = CoverMemoryReference(key, file, file?.version())
    }
}

private fun File.version() = lastModified() to length()

private fun localCoverFile(data: Any): File? = when (data) {
    is File -> data
    is Uri -> data.takeIf { it.scheme == "file" }?.path?.let(::File)
    is String -> Uri.parse(data).takeIf { it.scheme == "file" }?.path?.let(::File)
    else -> null
}
