package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdateArtifactDownloader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Shared V2 encoders are downloaded once, independently of each speaker's converted weights. */
@Singleton
internal class SovitsRuntimeModels @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloader: UpdateArtifactDownloader,
) {
    val directory get() = File(context.noBackupFilesDir, "sovits/RuntimeAssets")

    suspend fun prepare(progress: (SovitsImportMessage) -> Unit) {
        val files = context.assets.open("gpt-sovits/runtime-models.json").bufferedReader().use { JSONArray(it.readText()) }
        for (index in 0 until files.length()) {
            currentCoroutineContext().ensureActive()
            val spec = files.getJSONObject(index)
            val target = File(directory, spec.getString("path"))
            val size = spec.getLong("bytes")
            if (target.isFile && target.length() == size) continue
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val partial = File(target.parentFile, target.name + ".part")
            fun report(percent: Int) = progress(SovitsImportMessage(R.string.sovits_downloading_shared, index + 1, files.length(), percent))
            try {
                val urls = spec.getJSONArray("urls")
                val valid = (0 until urls.length()).any { mirror ->
                    requireSovits(directory.usableSpace + partial.length() >= size) { SovitsImportMessage(R.string.sovits_checkpoint_storage_full) }
                    var previous = 0
                    report(0)
                    try {
                        val downloaded = downloader.downloadFile(urls.getString(mirror), partial, spec.getString("sha256")) { copied, _ ->
                            if (copied > size) throw IOException("Unexpected shared model size")
                            val percent = (copied * 100 / size).toInt()
                            if (percent != previous) {
                                previous = percent
                                report(percent)
                            }
                        }
                        downloaded && partial.length() == size
                    } catch (failure: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (mirror == urls.length() - 1) throw failure
                        false
                    }
                }
                requireSovits(valid) { SovitsImportMessage(R.string.sovits_shared_invalid) }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(target))
            } finally { partial.delete() }
        }
    }
}
