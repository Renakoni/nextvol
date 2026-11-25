package indi.renakoni.nextvol.ui.book.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdateArtifactDownloader
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Published filenames are immutable. Measurement and previews can reuse the parsed face.
internal val readerFontFamilies = object : LruCache<File, FontFamily>(2) {
    override fun create(key: File) = FontFamily(loadReaderTypeface(key))
}

internal enum class ReaderFont(val title: Int, val filename: String? = null, val preview: Int? = null) {
    System(R.string.reader_font_system),
    SourceHanSerif(R.string.reader_font_serif, "source_han_serif_regular.otf", R.drawable.reader_sample_source_han_serif),
    WenKai(R.string.reader_font_wenkai, "lxgw_wenkai_regular.ttf", R.drawable.reader_sample_wenkai),
}

internal class ReaderFonts(private val context: Context) : ViewModel() {
    private val directory = File(context.noBackupFilesDir, "reader-typefaces")
    private val importDirectory = File(context.filesDir, "reader-fonts")
    private val temporary = File(context.cacheDir, "reader-font.part")
    private val downloader = UpdateArtifactDownloader(context)
    private var job: Job? = null

    val presets = ReaderFont.entries.associateWith { font -> font.filename?.let { File(directory, it).toUri() } ?: Uri.EMPTY }
    var installed by mutableStateOf(ReaderFont.entries.filter { font ->
        font.filename == null || File(directory, font.filename).isFile
    }.toSet())
        private set
    var imported by mutableStateOf(emptyList<File>())
        private set
    var pending by mutableStateOf<ReaderFont?>(null)
        private set
    var progress by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    init {
        viewModelScope.launch {
            staging.withLock {
                imported = withContext(Dispatchers.IO) {
                    temporary.delete()
                    importDirectory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.substringAfter("--") }
                }
            }
        }
    }

    fun select(uri: Uri, selection: UriUserData) {
        job?.cancel()
        pending = null
        selection.asynchronousSet(uri)
    }

    fun download(font: ReaderFont, selection: UriUserData) = install(font, selection) {
        val target = File(directory, requireNotNull(font.filename))
        val catalog = context.assets.open("reader-fonts.json").bufferedReader().use { JSONObject(it.readText()) }
        val asset = "${catalog.getString("tag")}/${target.name}"
        val url = checkNotNull(downloader.selectDownloadUrl(listOf(
            "${ProjectLinks.GITHUB}/releases/download/$asset",
            "${ProjectLinks.DOWNLOADS}/releases/$asset",
        )))
        val digest = catalog.getJSONObject("files").getString(target.name)
        var lastStep = -1L
        check(downloader.downloadFile(url, temporary, digest) { copied, total ->
            val step = copied / 100_000
            if (step != lastStep || copied == total) {
                withContext(Dispatchers.Main.immediate) { progress = copied to total }
                lastStep = step
            }
        })
        target
    }

    fun importFont(uri: Uri, selection: UriUserData) = install(null, selection) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "font.ttf"
        val safeName = name.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").take(80)
        requireNotNull(resolver.openInputStream(uri)).use { input ->
            temporary.outputStream().use { output -> runInterruptible { input.copyTo(output) } }
        }
        File(importDirectory, "${UUID.randomUUID()}--$safeName")
    }

    private fun install(font: ReaderFont?, selection: UriUserData, prepare: suspend () -> File) {
        job?.cancel()
        pending = font
        progress = null
        job = viewModelScope.launch {
            try {
                staging.withLock {
                    try {
                        val (target, family) = withContext(Dispatchers.IO) {
                            val file = prepare()
                            currentCoroutineContext().ensureActive()
                            file.parentFile!!.mkdirs()
                            file to FontFamily(loadReaderTypeface(temporary))
                        }
                        publish(target, family, font, selection)
                    } finally {
                        temporary.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isActive) {
                    val message = if (font == null) R.string.font_file_error else R.string.reader_font_download_failed
                    textToast(context, message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (isActive) pending = null
            }
        }
    }

    // Called on Main: publishing the file and submitting its selection precede the next tap.
    private suspend fun publish(target: File, family: FontFamily, font: ReaderFont?, selection: UriUserData) {
        check(temporary.renameTo(target))
        readerFontFamilies.put(target, family)
        if (font != null) {
            installed += font
            selection.setAndAwait(target.toUri())
            return
        }
        withContext(NonCancellable) {
            try {
                selection.setAndAwait(target.toUri())
            } catch (e: Exception) {
                readerFontFamilies.remove(target)
                target.delete()
                throw e
            }
            imported = (imported + target).sortedBy { it.name.substringAfter("--") }
        }
    }

    private companion object {
        // Activity recreation can overlap a cancelled writer finishing its cleanup.
        val staging = Mutex()
    }
}
