package indi.renakoni.nextvol.ui.book.reader

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdateArtifactDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal const val READER_TYPEFACES = "reader-typefaces"

// The two published faces are immutable. Paragraph measurement and previews share their typefaces.
internal val downloadedFontFamilies = object : LruCache<File, FontFamily>(2) {
    override fun create(key: File) = FontFamily(loadReaderTypeface(key))
}

internal enum class ReaderFont(val title: Int, private val filename: String? = null, val preview: Int? = null) {
    System(R.string.reader_font_system),
    SourceHanSerif(R.string.reader_font_serif, "source_han_serif_regular.otf", R.drawable.reader_sample_source_han_serif),
    WenKai(R.string.reader_font_wenkai, "lxgw_wenkai_regular.ttf", R.drawable.reader_sample_wenkai);

    private val installation = Mutex()

    fun file(context: Context): File? = filename?.let { File(context.noBackupFilesDir, "$READER_TYPEFACES/$it") }
    fun uri(context: Context): Uri = file(context)?.toUri() ?: Uri.EMPTY

    suspend fun download(context: Context, downloader: UpdateArtifactDownloader, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        installation.withLock {
            val target = requireNotNull(file(context))
            if (target.isFile) return@withLock
            val temporary = File(context.cacheDir, "${target.name}.part")
            try {
                val url = "https://raw.githubusercontent.com/Renakoni/nextvol/v0.2.2/app/src/main/res/font/${target.name}"
                check(downloader.downloadFile(url, temporary, onProgress = onProgress))
                val family = FontFamily(loadReaderTypeface(temporary))
                target.parentFile!!.mkdirs()
                check(temporary.renameTo(target))
                downloadedFontFamilies.put(target, family)
            } finally {
                temporary.delete()
            }
        }
    }
}
