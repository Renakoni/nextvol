package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdateArtifactDownloader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Shared V2 encoders are downloaded once, independently of each speaker's converted weights. */
@Singleton
internal class SovitsRuntimeModels @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloader: UpdateArtifactDownloader,
) {
    val directory get() = File(context.noBackupFilesDir, "sovits/RuntimeAssets")
    private val installing = Mutex()

    suspend fun prepare(progress: (SovitsImportMessage) -> Unit) = installing.withLock {
        val files = context.assets.open("gpt-sovits/runtime-models.json").bufferedReader().use { JSONArray(it.readText()) }
        for (index in 0 until files.length()) {
            currentCoroutineContext().ensureActive()
            val spec = files.getJSONObject(index)
            val target = File(directory, spec.getString("path"))
            if (target.isFile && target.length() == spec.getLong("bytes")) continue
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val partial = File(target.parentFile, target.name + ".part")
            try {
                val urls = spec.getJSONArray("urls").let { list -> List(list.length(), list::getString) }
                progress(SovitsImportMessage(R.string.sovits_downloading_shared, index + 1, files.length(), 0))
                val url = downloader.selectDownloadUrl(urls)
                    ?: errorSovits(SovitsImportMessage(R.string.tts_runtime_sources_unreachable))
                var previous = -1
                requireSovits(downloader.downloadFile(url, partial, spec.getString("sha256")) { copied, _ ->
                    requireSovits(copied <= spec.getLong("bytes")) { SovitsImportMessage(R.string.sovits_shared_invalid) }
                    val percent = (copied * 100 / spec.getLong("bytes")).toInt()
                    if (percent != previous) {
                        previous = percent
                        progress(SovitsImportMessage(R.string.sovits_downloading_shared, index + 1, files.length(), percent))
                    }
                } && partial.length() == spec.getLong("bytes")) { SovitsImportMessage(R.string.sovits_shared_invalid) }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(target))
            } finally { partial.delete() }
        }
    }
}
