package indi.renakoni.nextvol.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.bookRequestFailureReason
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.download.MutableDownloadItem
import indi.renakoni.nextvol.data.download.downloadChapterSignature
import indi.renakoni.nextvol.data.download.downloadHash
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import indi.renakoni.nextvol.utils.network.ImageDownloader
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.potatoepub.builder.ChapterBuilder
import io.nightfish.potatoepub.builder.EpubBuilder
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.dom4j.Element

/**
 * Owns each request's staging/published files, download attempt and progress item.
 * Preparation keeps the existing book-operation lock and source/generation checks;
 * only complete archives reach EpubShareFiles' atomic publication boundary.
 * Mutable state belongs to an execution, so reusing this use case cannot mix requests.
 */
class ExportBookToEpubUseCase @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bookRepository: BookRepository,
    private val downloadProgressRepository: DownloadProgressRepository,
    private val contentJsonDecoder: ContentJsonDecoder,
    private val downloads: BookDownloadStore,
) {
    private companion object { const val TAG = "ExportEPUB" }

    suspend fun execute(
        request: EpubExportRequest,
        onProgress: (EpubExportProgress) -> Unit = {},
    ): EpubExportResult = ExportAttempt(request, onProgress).run()

    /** Also safe before execution starts; published recipient files are never removed here. */
    internal fun clearTemporaryFiles(request: EpubExportRequest) {
        temporaryDirectory(request).deleteRecursively()
    }

    private fun temporaryDirectory(request: EpubExportRequest): File =
        appContext.cacheDir.resolve("epub/${request.book.fileKey}/${request.id}")

    private data class PreparedBook(
        val information: BookInformation,
        val volumes: List<IndexedValue<Volume>>,
        val contents: Map<String, File>,
        val images: Map<String, File>,
        val covers: Map<String, File>,
    )

    private inner class ExportAttempt(
        private val request: EpubExportRequest,
        private val onProgress: (EpubExportProgress) -> Unit,
    ) {
        private val id = request.id
        private val includeImages = request.includeImages
        private var activeDownloadItem: MutableDownloadItem? = null
        private var totalChapters = 0
        private var processedChapters = 0
        private var currentVolumeTitle = ""
        private var currentChapterTitle = ""
        private var stage = "preparing"
        private var failureReason = "export_failed"
        private var delivered = 0
        private var planned = 1

        suspend fun run(): EpubExportResult {
            val requested = request.book
            var book = requested
            val type = request.type
            val selected = request.selectedVolumeIds
            val tempDir = temporaryDirectory(request)
            var complete = false
            try {
                // A restarted request owns only its own temporary directory.
                check(!tempDir.exists() || tempDir.deleteRecursively()) { "Cannot clear interrupted export" }
                val published = EpubShareFiles.files(appContext, id)
                if (published.isNotEmpty()) {
                    // Reuse published recipient files if the process died before recording success.
                    delivered = published.size
                    planned = delivered
                    complete = true
                    return EpubExportResult.Success(id, book, delivered, planned)
                }
                check(tempDir.isDirectory || tempDir.mkdirs()) { "Cannot create export directory" }
                val item = MutableDownloadItem(DownloadType.EPUB_EXPORT, book.storageKey,
                    bookRepository.getBookInformationFlow(book.storageKey))
                activeDownloadItem = item
                downloadProgressRepository.addExportItem(item)

                withContext(Dispatchers.IO) {
                    // A missing offline snapshot can discover a series while resolving details or TOC.
                    bookRepository.exportInformation(book).value()
                    bookRepository.exportVolumes(book).value()
                    book = bookRepository.canonicalBook(book)
                    val canonicalSelection = selected.map { BookIdentity.volumeKey(book, BookIdentity.volumeRemoteId(it, requested)) }.toSet()
                    val prepared = downloads.withBookOperation(book) { prepare(book, type, tempDir, canonicalSelection) }
                    stage = "build"
                    failureReason = "build_failed"
                    val outputs = tempDir.resolve("outputs").apply { check(mkdirs()) }
                    val groups = if (type == ExportType.BOOK) listOf(prepared.volumes) else prepared.volumes.map { listOf(it) }
                    groups.forEachIndexed { index, volumes ->
                        currentCoroutineContext().ensureActive()
                        currentVolumeTitle = if (type == ExportType.BOOK) "" else volumes.single().value.volumeTitle
                        currentChapterTitle = ""
                        val title = if (type == ExportType.BOOK) prepared.information.title else currentVolumeTitle
                        val context = currentCoroutineContext()
                        val epub = EpubBuilder().apply {
                            this.title = title
                            this.id = "urn:nextvol:${book.fileKey}:${if (type == ExportType.BOOK) "book" else volumes.single().index}"
                            modifier = LocalDateTime.now(java.time.ZoneOffset.UTC)
                            creator = prepared.information.author
                            description = prepared.information.description
                            publisher = prepared.information.publishingHouse
                            cover(prepared.covers.getValue(volumes.first().value.volumeId))
                            volumes.forEach { (_, volume) ->
                                currentVolumeTitle = volume.volumeTitle
                                if (type == ExportType.BOOK) chapter {
                                    title(volume.volumeTitle)
                                    volume.chapters.forEach { info -> chapter { context.ensureActive(); packChapter(info, prepared, this@apply) } }
                                } else volume.chapters.forEach { info -> chapter { context.ensureActive(); packChapter(info, prepared, this@apply) } }
                            }
                        }
                        val label = if (type == ExportType.BOOK) title else "${prepared.information.title} $title"
                        val cleanTitle = label.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "Book" }
                        val safeTitle = cleanTitle.substring(0, cleanTitle.offsetByCodePoints(0, minOf(50, cleanTitle.codePointCount(0, cleanTitle.length))))
                        val prefix = if (type == ExportType.BOOK) "" else "${(index + 1).toString().padStart(groups.size.toString().length, '0')} - "
                        epub.build().save(outputs.resolve("$prefix$safeTitle.epub")) { context.ensureActive() }
                        item.progress = .7f + .15f * (index + 1) / groups.size
                    }
                    // Publish one directory atomically only after all selected archives are complete.
                    stage = "share"
                    failureReason = "share_failed"
                    currentCoroutineContext().ensureActive()
                    val context = currentCoroutineContext()
                    EpubShareFiles.publish(appContext, id, outputs) { context.ensureActive() }
                    currentCoroutineContext().ensureActive()
                    delivered = groups.size
                }
                complete = true
                item.progress = 1f
                return EpubExportResult.Success(id, book, delivered, planned)
            } catch (cancelled: CancellationException) {
                failureReason = "cancelled"
                if (!currentCoroutineContext().isActive) throw EpubExportCancelled(failure(book), cancelled)
                failureReason = "source_unavailable"
                return failure(book)
            } catch (problem: Exception) {
                Log.e(TAG, "Export failed for ${book.fileKey}: stage=$stage reason=$failureReason type=${problem.javaClass.simpleName}")
                return failure(book)
            } finally {
                if (!complete) {
                    EpubShareFiles.directory(appContext, id).deleteRecursively()
                    activeDownloadItem?.progress = -1f
                }
                clearTemporaryFiles(request)
            }
        }

        private fun failure(book: SourceBookId) = EpubExportResult.Failure(
            book, failureReason, stage, currentVolumeTitle, currentChapterTitle, delivered, planned,
        )

        private fun <T> com.github.michaelbull.result.Result<T, WebRequestError>.value(): T = getOrElse {
            failureReason = bookRequestFailureReason(it)
            throw IOException("Source data unavailable")
        }

        private suspend fun prepare(book: SourceBookId, type: ExportType, directory: File, selected: Set<String>): PreparedBook {
            val information = bookRepository.exportInformation(book).value()
            val catalog = bookRepository.exportVolumes(book).value()
            val volumes = catalog.volumes.withIndex().filter { type == ExportType.BOOK || it.value.volumeId in selected }
            planned = if (type == ExportType.BOOK) 1 else volumes.size
            failureReason = "missing_volume"
            require(type != ExportType.VOLUMES || selected.isNotEmpty() && selected.size == volumes.size)
            failureReason = "empty_directory"
            require(volumes.isNotEmpty())
            volumes.forEach { (_, volume) ->
                currentVolumeTitle = volume.volumeTitle
                failureReason = "empty_volume"
                require(volume.chapters.isNotEmpty())
            }
            val allChapters = catalog.volumes.flatMap { it.chapters }.distinctBy { it.id }
            val positions = allChapters.mapIndexed { index, chapter -> chapter.id to index }.toMap()
            failureReason = "cache_failed"
            val savedRevision = downloads.revision(book)
            val revision = bookRepository.sourceRevision(book).ifEmpty { savedRevision.orEmpty() }
            val refreshImages = savedRevision != null && savedRevision != revision
            val local = LocalBookStore.isLocal(book)
            val attempt = if (local) null else downloads.begin(book, request.downloadGeneration, id.toString())
            var cached = false
            try {
                attempt?.let { downloads.target(it, catalog, revision, information.coverUri.toString()) }
                val rendered = linkedMapOf<String, File>()
                val chapterImages = mutableMapOf<String, List<String>>()
                val images = linkedMapOf<String, File>()
                val missingImageVersions = mutableMapOf<String, MutableSet<String>>()
                val checkpointImages = mutableMapOf<String, File>()
                val freshImages = mutableSetOf<String>()
                val tasks = linkedMapOf<Pair<String, Boolean>, ImageDownloader.Task>()
                val locations = mutableMapOf<File, Pair<String, String>>()
                fun imageTask(uri: Uri, cover: Boolean = false, optional: Boolean = false): File {
                    val key = uri.toString() to cover
                    return tasks.getOrPut(key) {
                        val hash = downloadHash("$cover:${uri}")
                        val file = directory.resolve("image_$hash.jpg")
                        locations[file] = currentVolumeTitle to currentChapterTitle
                        ImageDownloader.Task(file, uri, cover,
                            if (optional) DefaultBookCoverRenderer.Text(book.storageKey, information.title, information.author) else null,
                            fresh = refreshImages)
                    }.file
                }
                stage = "chapters"
                totalChapters = volumes.sumOf { it.value.chapters.size }
                processedChapters = 0
                for ((_, volume) in volumes) {
                    currentVolumeTitle = volume.volumeTitle
                    for (chapter in volume.chapters) {
                        currentCoroutineContext().ensureActive()
                        currentChapterTitle = chapter.title
                        val signature = downloadChapterSignature(allChapters, positions.getValue(chapter.id), revision)
                        var checkpoint = attempt?.let { downloads.checkpoint(it, chapter.id, signature) }
                        val content = checkpoint?.content
                            ?: if (attempt != null && downloads.hasVersionedChapter(attempt, chapter.id))
                                bookRepository.downloadChapter(book, chapter.id).value()
                            else bookRepository.exportChapter(book, chapter.id).value()
                        failureReason = "invalid_content"
                        require(content.id == chapter.id) { "Chapter identity mismatch" }
                        val references = linkedSetOf<String>()
                        contentJsonDecoder.decodeForExport(content.content) { data ->
                            visitImages(data.toHtmlElement(appContext)) { uri -> references += uri.toString() }
                        }
                        chapterImages[chapter.id] = references.toList()
                        // Keep validated source content even if a later chapter or conversion fails.
                        failureReason = "cache_failed"
                        if (checkpoint == null) checkpoint = attempt?.let { downloads.saveCandidate(it, content, signature) }
                        if (includeImages && attempt != null) checkpoint?.let { saved ->
                            val missing = references.filter { uri ->
                                val versions = missingImageVersions.getOrPut(uri) { linkedSetOf() }
                                val file = downloads.checkpointImage(attempt, saved, uri)
                                if (file != null) checkpointImages.putIfAbsent(uri, file)
                                else versions += saved.resourceVersion
                                file == null
                            }
                            // A newer body must not borrow the old published chapter's images.
                            if (missing.isNotEmpty() && downloads.hasVersionedChapter(attempt, chapter.id))
                                freshImages += missing
                        }
                        failureReason = "invalid_content"
                        val processed = bookRepository.exportContent(book, content)
                        // Validate the processed snapshot too; conversion must not silently omit components.
                        contentJsonDecoder.decodeForExport(processed.content) { data ->
                            if (includeImages) visitImages(data.toHtmlElement(appContext)) { uri ->
                                images[uri.toString()] = imageTask(uri)
                            }
                        }
                        if (includeImages) references.forEach { uri -> images[uri] = imageTask(Uri.parse(uri)) }
                        // Only one converted chapter is resident here; the builder reads it back on demand.
                        failureReason = "cache_failed"
                        rendered[chapter.id] = directory.resolve("chapter_${downloadHash(chapter.id)}.json").also {
                            it.writeText(processed.content.toString())
                        }
                        processedChapters++
                        val progress = (processedChapters * 40 / totalChapters)
                        activeDownloadItem?.progress = progress / 100f
                        onProgress(EpubExportProgress(progress, EpubExportProgress.Phase.CHAPTERS,
                            processedChapters, totalChapters, currentVolumeTitle, currentChapterTitle))
                    }
                }
                currentVolumeTitle = ""
                currentChapterTitle = ""
                val cover = if (information.coverUri.toString().isBlank()) directory.resolve("cover.jpg").also {
                    DefaultBookCoverRenderer.writeTo(appContext, it, information.title, book.storageKey, information.author)
                } else imageTask(information.coverUri, cover = true, optional = true)
                suspend fun acquire(pending: List<ImageDownloader.Task>) {
                    stage = "images"
                    failureReason = "image_failed"
                    val tasks = pending.map { task ->
                        val uri = task.uri.toString()
                        task.copy(sourceFile = if (task.cover || uri in freshImages) null else checkpointImages[uri],
                            fresh = task.fresh || (!task.cover && uri in freshImages) ||
                                (attempt?.let { downloads.isImageStale(it, uri, task.cover) } == true))
                    }
                    val result = ImageDownloader(appContext, book, tasks,
                        onTask = { task ->
                            val location = locations[task.file]
                            currentVolumeTitle = location?.first.orEmpty()
                            currentChapterTitle = location?.second.orEmpty()
                        },
                        onDownloaded = { task ->
                            attempt?.let {
                                val uri = task.uri.toString()
                                val versions = if (task.cover) null else missingImageVersions[uri]
                                if (versions != null) {
                                    val bytes = task.sourceFile?.takeIf { versions.isNotEmpty() }?.readBytes()
                                    for (version in versions) {
                                        if (bytes != null) downloads.saveImage(it, uri, false, bytes, version)
                                        else downloads.retainImage(it, SourceImage(book, uri), null, version)
                                    }
                                } else if (task.fresh || !downloads.hasImage(it, uri, task.cover))
                                    downloads.retainImage(it, SourceImage(book, uri, task.cover), null)
                            }
                        },
                        onProgress = { count, total ->
                            val progress = 40 + (30f * count / total).toInt()
                            activeDownloadItem?.progress = progress / 100f
                            onProgress(EpubExportProgress(progress, EpubExportProgress.Phase.IMAGES,
                                count, total, currentVolumeTitle, currentChapterTitle))
                        }).run()
                    check(result) { "Image preparation failed" }
                }
                acquire(tasks.values.toList())
                val covers = linkedMapOf<String, File>()
                for ((index, volume) in volumes) {
                    currentCoroutineContext().ensureActive()
                    currentVolumeTitle = volume.volumeTitle
                    currentChapterTitle = ""
                    val uri = if (type == ExportType.VOLUMES && index != 0 && includeImages) {
                        // Volume artwork is optional. Prepared body images can also be reused as covers.
                        try {
                            val raw = if (attempt == null) emptyMap() else volume.chapters.associate { chapter ->
                                val signature = downloadChapterSignature(allChapters, positions.getValue(chapter.id), revision)
                                chapter.id to checkNotNull(downloads.checkpoint(attempt, chapter.id, signature)).content
                            }
                            bookRepository.volumeCover(book, volume, raw, appContext).get()
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    } else null
                    val previous = tasks.size
                    covers[volume.volumeId] = uri?.let { images[it.toString()] ?: imageTask(it, optional = true) } ?: cover
                    if (tasks.size > previous) acquire(tasks.values.drop(previous))
                }
                stage = "cache"
                failureReason = "cache_failed"
                attempt?.let { active ->
                    for (chapterId in rendered.keys) {
                        currentCoroutineContext().ensureActive()
                        val signature = downloadChapterSignature(allChapters, positions.getValue(chapterId), revision)
                        val checkpoint = checkNotNull(downloads.checkpoint(active, chapterId, signature))
                        downloads.saveChapter(active, checkpoint.content, signature,
                            chapterImages.getValue(chapterId), requireImages = includeImages,
                            resourceVersion = checkpoint.resourceVersion)
                    }
                    check(bookRepository.sourceRevision(book).let { it.isEmpty() || it == revision }) { "Source changed during preparation" }
                    downloads.finish(active, success = true)
                }
                cached = true
                val processedCatalog = bookRepository.exportCatalog(catalog)
                return PreparedBook(bookRepository.exportMetadata(information),
                    volumes.map { IndexedValue(it.index, processedCatalog.volumes[it.index]) }, rendered, images, covers)
            } finally {
                if (!cached && attempt != null) withContext(NonCancellable) {
                    try { downloads.finish(attempt, success = false) }
                    catch (_: CancellationException) { /* Cleared/replaced: never recreate its download ownership. */ }
                    catch (problem: Exception) { Log.e(TAG, "Could not save download state: ${problem.javaClass.simpleName}") }
                }
            }
        }

        private fun visitImages(element: Element, action: (Uri) -> Unit) {
            element.attribute("src")?.let { src ->
                require(src.value.isNotBlank()) { "Empty image resource" }
                action(Uri.parse(src.value))
            }
            element.elements().forEach { visitImages(it, action) }
        }

        private fun ChapterBuilder.packChapter(info: ChapterInformation, prepared: PreparedBook, epub: EpubBuilder) {
            currentChapterTitle = info.title
            title(info.title)
            content {
                title(info.title)
                val content = Json.parseToJsonElement(prepared.contents.getValue(info.id).readText()).jsonObject
                contentJsonDecoder.decodeForExport(content) { data ->
                    fun localize(element: Element): Element? {
                        if (!includeImages && (element.name in setOf("img", "image") || element.attribute("src") != null)) return null
                        element.attribute("src")?.let { src ->
                            val file = prepared.images.getValue(src.value)
                            src.value = "image/${file.name}"
                            epub.imgRes(src.value, file.nameWithoutExtension, file)
                        }
                        element.elements().toList().forEach { if (localize(it) == null) it.detach() }
                        return element
                    }
                    localize(data.toHtmlElement(appContext))?.let(::addContent)
                }
            }
        }
    }
}
