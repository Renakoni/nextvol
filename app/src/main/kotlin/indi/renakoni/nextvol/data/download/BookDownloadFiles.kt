package indi.renakoni.nextvol.data.download

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import coil3.SingletonImageLoader
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.sourceImageCacheKey
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Resource file operations; callers own locking, cancellation and catalog decisions. */
internal class BookDownloadFiles(private val context: Context) {
    private val root = File(context.filesDir, "book-downloads")
    private val imageKeys = context.getSharedPreferences("source_image_cache_keys", Context.MODE_PRIVATE)

    fun imageFile(book: SourceBookId, generation: Long, uri: String, cover: Boolean, version: String = ""): File {
        val directory = if (version.isEmpty()) "" else "resources/${downloadHash(version)}/"
        return File(root, "$generation/${book.fileKey}/$directory${downloadHash(BookIdentity.encode("download-image", listOf(cover.toString(), uri)))}")
    }

    private fun staleMarker(file: File) = File(file.parentFile, "${file.name}.stale")

    fun isStale(file: File): Boolean = staleMarker(file).exists()

    fun markImagesStale(book: SourceBookId, generation: Long) {
        val directory = imageFile(book, generation, "", false).parentFile!!
        directory.listFiles()?.filter { file -> file.isFile && file.name.matches(Regex("[0-9a-f]{64}")) }
            ?.forEach { file -> check(staleMarker(file).isFile || staleMarker(file).createNewFile()) }
    }

    fun copyImage(from: SourceBookId, fromGeneration: Long, to: SourceBookId, generation: Long,
        uri: String, version: String) {
        val source = imageFile(from, fromGeneration, uri, false, version)
        val destination = imageFile(to, generation, uri, false, version)
        if (!source.isFile || destination.isFile) return
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            source.inputStream().use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        // A migration preserves offline bytes, not proof of the new directory's version.
        check(staleMarker(destination).isFile || staleMarker(destination).createNewFile())
    }

    fun writeImage(target: File, bytes: ByteArray) {
        val temporary = prepareImage(target, bytes)
        try { publishImage(temporary, target) } finally { temporary.delete() }
    }

    fun prepareImage(target: File, bytes: ByteArray): File {
        val parent = target.parentFile!!
        parent.mkdirs()
        val temporary = File.createTempFile("${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            return temporary
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    fun publishImage(temporary: File, target: File) {
        val previousTime = target.lastModified()
        // File mtime participates in Coil's key; two same-sized updates in one tick must differ.
        if (temporary.lastModified() <= previousTime)
            check(temporary.setLastModified(previousTime + 1)) { "Could not update downloaded image version" }
        // Same-directory rename publishes atomically; unlike AtomicFile.finishWrite it reports failure.
        if (!temporary.renameTo(target)) throw IOException("Could not publish downloaded image")
        val stale = staleMarker(target)
        check(!stale.exists() || stale.delete()) { "Could not commit downloaded image version" }
    }

    fun cachedImageBytes(image: SourceImage, cacheKey: String?): ByteArray? {
        val uri = Uri.parse(image.uri)
        if (uri.scheme in setOf("file", "content", "android.resource"))
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val key = cacheKey ?: imageKeys.getString(sourceImageCacheKey(image, ""), null) ?: return null
        val cache = SingletonImageLoader.get(context).diskCache ?: return null
        return cache.openSnapshot(key)?.use { snapshot -> cache.fileSystem.read(snapshot.data) { readByteArray() } }
    }

    fun pruneResources(book: SourceBookId, generation: Long, versions: Set<String>) {
        val retained = versions.map(::downloadHash).toSet()
        File(root, "$generation/${book.fileKey}/resources").listFiles()?.filter { it.name !in retained }
            ?.forEach { it.deleteRecursively() }
    }

    fun clearReadingCache() {
        SingletonImageLoader.get(context).apply { memoryCache?.clear(); diskCache?.clear() }
    }

    fun clearCachedImages(images: List<SourceImage>) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.clear()
        images.forEach { image ->
            imageKeys.getString(sourceImageCacheKey(image, ""), null)?.let { loader.diskCache?.remove(it) }
        }
    }

    fun clearDownloads() {
        check(!root.exists() || root.deleteRecursively()) { "Could not remove downloaded images" }
    }

    fun removeBook(book: SourceBookId, generation: Long) {
        val directory = File(root, "$generation/${book.fileKey}")
        check(!directory.exists() || directory.deleteRecursively()) { "Could not remove downloaded images" }
    }

    fun generationExists(generation: Long): Boolean = File(root, generation.toString()).exists()

    fun retiredGenerations(keep: Set<Long>): List<File> = root.listFiles().orEmpty().filter { directory ->
        val generation = directory.name.toLongOrNull()
        generation != null && generation !in keep
    }

    fun removeRetiredGeneration(directory: File) {
        check(directory.deleteRecursively()) { "Could not remove retired download generation" }
    }
}
