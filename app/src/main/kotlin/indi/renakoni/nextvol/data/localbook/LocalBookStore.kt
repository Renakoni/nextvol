package indi.renakoni.nextvol.data.localbook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.local.room.entity.ImportedBookEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.StringListUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class LocalBookFormat { TXT, EPUB }

class LocalBookDraft internal constructor(
    val book: SourceBookId,
    val originalName: String,
    val format: LocalBookFormat,
    internal val directory: File,
) {
    internal var rule: String? = null
    internal var encoding: String? = null
    internal val original get() = File(directory, "original.${format.name.lowercase()}")
}

@Serializable
private data class LocalChapterIndex(val title: String, val volume: String)

@Serializable
private data class LocalBookIndex(
    val title: String,
    val author: String,
    val description: String,
    val publisher: String,
    val cover: String?,
    val importedAt: String,
    val wordCount: Int,
    val chapters: List<LocalChapterIndex>,
    val manifest: LocalBookFileManifest? = null,
)

/** Imported originals and parsed files are never reading cache or network source data. */
@Singleton
class LocalBookStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: NextVolDatabase,
) {
    private val root = File(context.filesDir, "local-books")
    private val lock = Mutex()
    private var recovered = false
    private val drafts = mutableSetOf<File>()

    companion object {
        val SOURCE = Identifier("nextvol", "local")
        fun isLocal(book: SourceBookId) = book.sourceId == SOURCE
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }

    suspend fun stage(uri: Uri, displayName: String? = null, knownFormat: LocalBookFormat? = null): LocalBookDraft {
        var staged: LocalBookDraft? = null
        try {
            return withContext(Dispatchers.IO) { lock.withLock {
                recoverLocked()
                val name = (displayName ?: context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringAfterLast('\\')
                val mime = if (knownFormat == null) context.contentResolver.getType(uri) else null
                val format = knownFormat ?: when {
                    name.endsWith(".epub", true) || mime == "application/epub+zip" -> LocalBookFormat.EPUB
                    name.endsWith(".txt", true) || mime == "text/plain" -> LocalBookFormat.TXT
                    else -> throw LocalBookImportException(LocalBookImportReason.UnsupportedFormat, "Only EPUB and TXT files are supported.")
                }
                val book = SourceBookId(SOURCE, UUID.randomUUID().toString())
                val directory = File(root, "pending-${book.remoteId}")
                requireImport(directory.mkdirs(), LocalBookImportReason.Storage) { "Cannot create the import directory." }
                val draft = LocalBookDraft(book, name, format, directory)
                staged = draft
                drafts += directory
                requireImportNotNull(context.contentResolver.openInputStream(uri), LocalBookImportReason.FileAccess) { "Cannot open the selected file." }.use { input ->
                    draft.original.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            requireImport(size <= MAX_FILE_BYTES && (format != LocalBookFormat.TXT || size <= 16L * 1024 * 1024), LocalBookImportReason.FileTooLarge) {
                                "The selected file exceeds the import size limit."
                            }
                            output.write(buffer, 0, count)
                        }
                        requireImport(size > 0, LocalBookImportReason.EmptyFile) { "The selected file is empty." }
                    }
                }
                draft
            } }
        } catch (failure: Exception) {
            staged?.let { discard(it) }
            throw failure
        }
    }

    suspend fun preview(draft: LocalBookDraft, encoding: String? = null, rule: String = TxtBookParser.DEFAULT_RULE, fallbackTitle: String? = null): ParsedLocalBook =
        withContext(Dispatchers.IO) { lock.withLock {
            requireImport(draft.directory in drafts, LocalBookImportReason.SessionExpired) { "This import session is no longer available." }
            currentCoroutineContext().ensureActive()
            val title = (fallbackTitle ?: draft.originalName.substringBeforeLast('.')).ifBlank { "Local book" }
            val parsed = when (draft.format) {
                LocalBookFormat.TXT -> TxtBookParser.parse(draft.original.readBytes(), title, encoding, rule)
                LocalBookFormat.EPUB -> EpubBookParser.parse(draft.original, File(draft.directory, "assets"), title)
            }
            currentCoroutineContext().ensureActive()
            draft.rule = rule.takeIf { draft.format == LocalBookFormat.TXT }
            draft.encoding = parsed.encoding
            parsed
        } }

    suspend fun discard(draft: LocalBookDraft) = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        if (drafts.remove(draft.directory)) deleteDirectory(draft.directory)
    } }

    /** Confirmation cannot be cancelled halfway through publishing files and the Room transaction. */
    suspend fun publish(draft: LocalBookDraft, parsed: ParsedLocalBook, title: String, shelfId: Int?,
        localShelfName: String? = null): Pair<SourceBookId, Int> =
        withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
            requireImport(draft.directory in drafts, LocalBookImportReason.SessionExpired) { "This import session is no longer available." }
            requireImport(title.isNotBlank() && title.length <= 200, LocalBookImportReason.InvalidTitle) { "Enter a book title of at most 200 characters." }
            val index = LocalBookIndex(title.trim(), parsed.author, parsed.description, parsed.publishingHouse,
                parsed.coverPath, LocalDateTime.now().toString(),
                parsed.chapters.sumOf { chapter -> chapter.blocks.filterIsInstance<LocalBookBlock.Text>().sumOf { it.value.length } },
                parsed.chapters.map { LocalChapterIndex(it.title, it.volume) }, manifest(draft, parsed, draft.book))
            val directory = File(root, draft.book.fileKey)
            var moved = false
            try {
                parsed.chapters.forEachIndexed { number, chapter ->
                    File(draft.directory, "$number.json").writeText(Json.encodeToString(chapter))
                }
                File(draft.directory, "index.json").writeText(Json.encodeToString(index))
                requireImport(draft.directory.renameTo(directory), LocalBookImportReason.Storage) { "Cannot publish the imported files." }
                moved = true
                val target = database.withTransaction {
                    val shelves = database.bookshelfDao()
                    val shelf = if (localShelfName != null) {
                        val existing = shelves.getAllBookshelves()
                        existing.firstOrNull { it.name == localShelfName } ?: BookshelfEntity(
                            id = generateSequence(0) { it + 1 }.first { id -> existing.none { it.id == id } },
                            name = localShelfName, sortType = BookshelfSortType.Default.key,
                            autoCache = false, systemUpdateReminder = false, allBookIds = emptyList(),
                            pinnedBookIds = emptyList(), updatedBookIds = emptyList())
                    } else if (shelfId == null) {
                        requireImport(shelves.getAllBookshelfIds().isEmpty(), LocalBookImportReason.ShelfChanged) { "The selected bookshelf is no longer available." }
                        BookshelfEntity(0, context.getString(R.string.local_bookshelf_name), BookshelfSortType.Default.key,
                            autoCache = false, systemUpdateReminder = false, allBookIds = emptyList(),
                            pinnedBookIds = emptyList(), updatedBookIds = emptyList())
                    } else requireImportNotNull(shelves.getBookshelf(shelfId), LocalBookImportReason.ShelfChanged) { "The selected bookshelf is no longer available." }
                    database.importedBookDao().insert(ImportedBookEntity(draft.book.storageKey))
                    database.localBookFileManifestDao().save(requireNotNull(index.manifest))
                    val info = information(draft.book, index, directory)
                    database.bookInformationDao().insert(info)
                    database.bookVolumesDao().insertVolume(info.id, volumes(draft.book, index))
                    parsed.chapters.forEachIndexed { number, chapter ->
                        database.chapterContentDao().cache(content(draft.book, index, directory, number, chapter))
                    }
                    shelves.insertBookshelf(shelf.copy(allBookIds = shelf.allBookIds + info.id))
                    shelves.addBookshelfMetadata(info.id, info.lastUpdated, listOf(shelf.id))
                    shelf.id
                }
                drafts.remove(draft.directory)
                draft.book to target
            } catch (failure: Exception) {
                if (moved) {
                    deleteDirectory(directory)
                    drafts.remove(draft.directory)
                }
                throw failure
            }
        } }

    suspend fun relinkSettings(book: SourceBookId) = database.localBookFileManifestDao().get(book.storageKey)

    suspend fun previewRelink(book: SourceBookId, draft: LocalBookDraft, encoding: String? = null,
        rule: String = TxtBookParser.DEFAULT_RULE): LocalBookRelinkPreview {
        require(isLocal(book))
        val expected = relinkSettings(book)
        // A renamed file must retain the original fallback chapter title and therefore chapter mapping.
        val parsed = preview(draft, encoding, rule, expected?.originalName?.substringBeforeLast('.'))
        return withContext(Dispatchers.IO) { lock.withLock {
            val candidate = manifest(draft, parsed, book).let {
                it.copy(originalName = expected?.originalName ?: it.originalName)
            }
            database.withTransaction {
                val info = database.bookInformationDao().get(book.storageKey)
                val catalog = database.bookVolumesDao().getBookVolumes(book.storageKey)
                val match = if (expected != null) when {
                    expected.format != candidate.format || expected.originalDigest != candidate.originalDigest -> LocalBookRelinkMatch.DifferentFile
                    expected.mappingDigest != candidate.mappingDigest -> LocalBookRelinkMatch.DifferentMapping
                    else -> LocalBookRelinkMatch.Exact
                } else {
                    val chapters = catalog?.volumes.orEmpty().flatMap { volume -> volume.chapters.map { volume.volumeTitle to it } }
                    when {
                        chapters.isEmpty() -> LocalBookRelinkMatch.MissingMapping
                        chapters.size != parsed.chapters.size || chapters.indices.any { number ->
                            val (volume, chapter) = chapters[number]
                            val parsedChapter = parsed.chapters[number]
                            // Older catalogs used the book title for chapters without an explicit volume.
                            val sameVolume = volume == parsedChapter.volume ||
                                (parsedChapter.volume.isBlank() && volume == (info?.title ?: parsed.title))
                            chapter.id != SourceChapterId(book, number.toString()).storageKey ||
                                chapter.title != parsedChapter.title || !sameVolume
                        } -> LocalBookRelinkMatch.DifferentMapping
                        else -> LocalBookRelinkMatch.Legacy
                    }
                }
                LocalBookRelinkPreview(book, parsed, match, draft, candidate, expected,
                    database.importedBookDao().get(book.storageKey), info, catalog)
            }
        } }
    }

    /** Complete a new file generation before atomically switching its owner. Reading data is untouched. */
    suspend fun relink(preview: LocalBookRelinkPreview, confirmLegacy: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val draft = preview.draft
            requireImport(draft.directory in drafts, LocalBookImportReason.SessionExpired) { "This relink session is no longer available." }
            require(preview.canRelink && (preview.match != LocalBookRelinkMatch.Legacy || confirmLegacy)) { "The original file must be verified before relinking." }
            val book = preview.book
            val parsed = preview.parsed
            val oldDirectory = bookDirectory(book)
            val directory = File(root, "linked-${book.fileKey}-${UUID.randomUUID()}")
            val index = LocalBookIndex(preview.information?.title ?: parsed.title, parsed.author, parsed.description,
                parsed.publishingHouse, parsed.coverPath, LocalDateTime.now().toString(),
                parsed.chapters.sumOf { chapter -> chapter.blocks.filterIsInstance<LocalBookBlock.Text>().sumOf { it.value.length } },
                parsed.chapters.map { LocalChapterIndex(it.title, it.volume) }, preview.manifest)
            parsed.chapters.forEachIndexed { number, chapter ->
                currentCoroutineContext().ensureActive()
                File(draft.directory, "$number.json").writeText(Json.encodeToString(chapter))
            }
            File(draft.directory, "index.json").writeText(Json.encodeToString(index))
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                var moved = false
                var committed = false
                try {
                    requireImport(draft.directory.renameTo(directory), LocalBookImportReason.Storage) { "Cannot publish the relinked files." }
                    moved = true
                    database.withTransaction {
                        requireImport(database.localBookFileManifestDao().get(book.storageKey) == preview.expectedManifest &&
                            database.importedBookDao().get(book.storageKey) == preview.expectedOwner &&
                            database.bookInformationDao().get(book.storageKey) == preview.information &&
                            database.bookVolumesDao().getBookVolumes(book.storageKey) == preview.volumes,
                            LocalBookImportReason.SessionExpired) { "The restored book changed. Select the file again." }
                        database.importedBookDao().replace(ImportedBookEntity(book.storageKey, directory.name))
                        database.localBookFileManifestDao().save(preview.manifest)
                        database.bookInformationDao().insert(information(book, index, directory))
                        database.bookVolumesDao().insertVolume(book.storageKey, volumes(book, index))
                        parsed.chapters.forEachIndexed { number, chapter ->
                            database.chapterContentDao().cache(content(book, index, directory, number, chapter))
                        }
                    }
                    committed = true
                    drafts.remove(draft.directory)
                    runCatching { deleteDirectory(oldDirectory) }.onFailure {
                        android.util.Log.e("LocalBookStore", "Cannot remove the previous local file generation", it)
                    }
                } catch (failure: Exception) {
                    if (moved && !committed) {
                        drafts.remove(draft.directory)
                        runCatching { deleteDirectory(directory) }.onFailure(failure::addSuppressed)
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun contains(book: SourceBookId): Boolean = withContext(Dispatchers.IO) {
        isLocal(book) && database.importedBookDao().contains(book.storageKey) && File(bookDirectory(book), "index.json").isFile
    }

    fun observeAvailability(book: SourceBookId) = database.importedBookDao().observe(book.storageKey).map { contains(book) }

    private suspend fun bookDirectory(book: SourceBookId): File {
        val name = database.importedBookDao().get(book.storageKey)?.directoryName?.takeIf { it.isNotEmpty() } ?: book.fileKey
        val directory = File(root, name).canonicalFile
        require(directory.parentFile == root.canonicalFile) { "Invalid local book directory." }
        return directory
    }

    private fun manifest(draft: LocalBookDraft, parsed: ParsedLocalBook, book: SourceBookId) = LocalBookFileManifest(
        book.storageKey, draft.format.name, draft.originalName, draft.original.originalDigest(),
        parsed.chapters.mappingDigest(), draft.encoding, draft.rule)

    suspend fun readInformation(book: SourceBookId) = read(book) { index, directory -> information(book, index, directory) }
    suspend fun readVolumes(book: SourceBookId) = read(book) { index, _ -> volumes(book, index) }
    suspend fun readChapter(chapter: SourceChapterId) = read(chapter.book) { index, directory ->
        val number = chapter.remoteId.toIntOrNull()
        require(number != null && number.toString() == chapter.remoteId && number in index.chapters.indices) { "Unknown local chapter." }
        content(chapter.book, index, directory, number, Json.decodeFromString<LocalBookChapter>(File(directory, "$number.json").readText()))
    }

    private suspend fun <T> read(book: SourceBookId, block: (LocalBookIndex, File) -> T): Result<T, WebRequestError> =
        withContext(Dispatchers.IO) { lock.withLock {
            try {
                recoverLocked()
                require(isLocal(book) && database.importedBookDao().contains(book.storageKey)) { "The original local file is not on this device. Import the EPUB or TXT file again." }
                val directory = bookDirectory(book)
                Ok(block(Json.decodeFromString(File(directory, "index.json").readText()), directory))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Err(WebRequestError(context.getString(R.string.local_book_unavailable),
                    context.getString(R.string.local_book_reimport), failure))
            }
        } }

    /** A deliberate deletion from Book Manager removes this copy from every shelf, never other copies. */
    suspend fun delete(book: SourceBookId) = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        require(isLocal(book))
        recoverLocked()
        val key = book.storageKey
        val ownedDirectory = bookDirectory(book)
        val savedIndex = runCatching {
            Json.decodeFromString<LocalBookIndex>(File(bookDirectory(book), "index.json").readText())
        }.getOrNull()
        database.withTransaction {
            val chapterIds = (database.bookVolumesDao().getVolumeEntitiesByBookId(key).flatMap { it.chapterIds } +
                savedIndex?.chapters.orEmpty().indices.map { SourceChapterId(book, it.toString()).storageKey }).distinct()
            chapterIds.chunked(500).forEach {
                database.chapterContentDao().deleteByIds(it)
                database.bookVolumesDao().deleteChapterInformationByIds(it)
            }
            database.bookVolumesDao().deleteByBookIds(listOf(key))
            database.bookInformationDao().deleteByIds(listOf(key))
            database.userReadingDataDao().deleteByIds(listOf(key))
            database.readingBookmarkDao().deleteBooks(listOf(key))
            StringListUserData(UserDataPath.ReadingBooks.path, database.userDataDao()).update { it - key }
            for (shelf in database.bookshelfDao().getAllBookshelves()) if (key in shelf.allBookIds) {
                database.bookshelfDao().insertBookshelf(shelf.copy(allBookIds = shelf.allBookIds - key,
                    pinnedBookIds = shelf.pinnedBookIds - key, updatedBookIds = shelf.updatedBookIds - key))
            }
            database.bookshelfDao().deleteBookshelfBookMetadata(key)
            database.importedBookDao().delete(key)
            database.localBookFileManifestDao().delete(key)
        }
        deleteDirectory(ownedDirectory)
    } }

    /** Overwrite-restoring a legacy backup can clear cache metadata, but cannot replace local originals. */
    suspend fun restoreMetadata() = withContext(Dispatchers.IO) { lock.withLock {
        recoverLocked()
        for (key in database.importedBookDao().allIds()) if (!database.bookInformationDao().has(key)) {
            try {
                val book = BookIdentity.book(key)
                val directory = bookDirectory(book)
                val index = Json.decodeFromString<LocalBookIndex>(File(directory, "index.json").readText())
                database.bookInformationDao().insert(information(book, index, directory))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                android.util.Log.e("LocalBookStore", "Cannot restore imported book metadata", failure)
                // Keep damaged copies visible in Book Manager so their owned files can be deleted.
                database.bookInformationDao().insert(BookInformation(id = key,
                    title = context.getString(R.string.local_book_unavailable), author = "",
                    description = context.getString(R.string.local_book_reimport), publishingHouse = "",
                    wordCount = WordCount(0), lastUpdated = LocalDateTime.now(), isComplete = true))
            }
        }
    } }

    suspend fun storedBytes(book: SourceBookId): Long = withContext(Dispatchers.IO) {
        if (isLocal(book)) bookDirectory(book).walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    private suspend fun recoverLocked() {
        if (recovered) return
        requireImport(root.isDirectory || root.mkdirs(), LocalBookImportReason.Storage) { "Cannot open the local library." }
        val owned = database.importedBookDao().all().map { it.directoryName.ifEmpty { BookIdentity.book(it.bookId).fileKey } }.toSet()
        root.listFiles()?.filter { it.isDirectory && it.name !in owned && it !in drafts }?.forEach(::deleteDirectory)
        recovered = true
    }

    private fun deleteDirectory(directory: File) {
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "Invalid local book directory." }
        requireImport(!directory.exists() || directory.deleteRecursively(), LocalBookImportReason.Storage) { "Cannot remove the local book files." }
    }

    private fun asset(directory: File, path: String): Uri {
        val file = File(directory, path).canonicalFile
        require(file.parentFile == File(directory, "assets").canonicalFile && file.isFile) { "A local book image is missing." }
        return file.toUri()
    }

    private fun information(book: SourceBookId, index: LocalBookIndex, directory: File) = BookInformation(
        id = book.storageKey, title = index.title, author = index.author, description = index.description,
        coverUri = index.cover?.let { asset(directory, it) } ?: Uri.EMPTY,
        publishingHouse = index.publisher, wordCount = WordCount(index.wordCount),
        lastUpdated = LocalDateTime.parse(index.importedAt), isComplete = true,
    )

    private fun volumes(book: SourceBookId, index: LocalBookIndex): BookVolumes {
        val volumes = mutableListOf<Volume>()
        index.chapters.forEachIndexed { number, chapter ->
            val volumeTitle = chapter.volume
            val info = ChapterInformation(SourceChapterId(book, number.toString()).storageKey, chapter.title)
            if (volumes.lastOrNull()?.volumeTitle == volumeTitle) {
                volumes[volumes.lastIndex] = volumes.last().copy(chapters = volumes.last().chapters + info)
            } else volumes += Volume(BookIdentity.volumeKey(book, volumes.size.toString()), volumeTitle, listOf(info))
        }
        return BookVolumes(book.storageKey, volumes)
    }

    private fun content(book: SourceBookId, index: LocalBookIndex, directory: File, number: Int, chapter: LocalBookChapter): ChapterContent {
        val builder = ContentBuilder()
        if (chapter.blocks.isEmpty()) builder.simpleText(chapter.title)
        for (block in chapter.blocks) when (block) {
            is LocalBookBlock.Text -> builder.simpleText(block.value)
            is LocalBookBlock.Image -> builder.image(asset(directory, block.path))
        }
        return ChapterContent(SourceChapterId(book, number.toString()).storageKey, chapter.title, builder.build(),
            (number - 1).takeIf { it >= 0 }?.let { SourceChapterId(book, it.toString()).storageKey },
            (number + 1).takeIf { it < index.chapters.size }?.let { SourceChapterId(book, it.toString()).storageKey })
    }
}
