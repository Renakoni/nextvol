package indi.renakoni.nextvol.tts.runtime

import android.os.SystemClock
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdateArtifactDownloader
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import indi.renakoni.nextvol.tts.sovits.DeviceAcceleration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface RuntimeDownload {
    data object Available : RuntimeDownload
    data class Downloading(val copied: Long, val total: Long) : RuntimeDownload
    data object Installing : RuntimeDownload
    data object Installed : RuntimeDownload
    data class Failed(val detail: String) : RuntimeDownload
}

@Singleton
internal class SpeechRuntimeDownloads @Inject constructor(
    private val downloader: UpdateArtifactDownloader,
    private val availability: OfflineAvailabilityChecker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // After a process restart only the revision in the current APK can be in use.
    private val recovery = scope.launch {
        for (pack in SpeechRuntimePack.entries) {
            val current = pack.directory
            pack.root.listFiles()?.forEach { if (it != current) it.deleteRecursively() }
        }
    }
    private val jobs = mutableMapOf<SpeechRuntimePack, Job>()
    private val states = MutableStateFlow<Map<SpeechRuntimePack, RuntimeDownload>>(SpeechRuntimePack.entries.associateWith {
        if (it.installed) RuntimeDownload.Installed else RuntimeDownload.Available
    })
    val changes = states.asStateFlow()

    @Synchronized fun start(pack: SpeechRuntimePack) {
        if (pack != SpeechRuntimePack.Vendor && DeviceAcceleration.status.value.gpu != true) return
        val state = states.value[pack]
        if (state != RuntimeDownload.Available && state !is RuntimeDownload.Failed) return
        set(pack, RuntimeDownload.Downloading(0, pack.archiveBytes))
        val job = scope.launch {
            try {
                recovery.join()
                install(pack)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                set(pack, RuntimeDownload.Failed(failure.localizedMessage.orEmpty()))
            }
        }
        jobs[pack] = job
        job.invokeOnCompletion { cause ->
            synchronized(this) {
                if (jobs[pack] == job) {
                    jobs.remove(pack)
                    if (cause is CancellationException && states.value[pack] != RuntimeDownload.Installed) {
                        set(pack, RuntimeDownload.Available)
                    }
                }
            }
        }
    }

    @Synchronized fun cancel(pack: SpeechRuntimePack) { jobs[pack]?.cancel() }

    private suspend fun install(pack: SpeechRuntimePack) {
        val root = pack.root.apply { mkdirs() }
        val archive = File(root, "download.tmp")
        val staging = File(root, "unpacking")
        try {
            val task = currentCoroutineContext()
            check(staging.mkdir()) { "Cannot create runtime staging directory" }
            var installedBytes = 0L
            for (part in pack.archives) {
                var nextProgressAt = 0L
                val url = downloader.selectDownloadUrl(part.urls)
                    ?: throw IOException(SpeechLibraries.context.getString(R.string.tts_runtime_sources_unreachable))
                if (!downloader.downloadFile(url, archive, part.sha256) { copied, _ ->
                    if (copied > part.bytes) throw IOException("Unexpected runtime download size")
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextProgressAt || copied == part.bytes) {
                        set(pack, RuntimeDownload.Downloading(installedBytes + copied, pack.archiveBytes))
                        nextProgressAt = now + 100
                    }
                }) throw IOException("Incomplete runtime download")
                if (part == pack.archives.last()) set(pack, RuntimeDownload.Installing)
                if (part.path == null) unpack(archive, staging)
                else {
                    val target = File(staging, part.path)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    check(archive.renameTo(target)) { "Cannot install shared model" }
                }
                installedBytes += part.bytes
            }
            // A cancel and a completed installation must agree on which happened first.
            synchronized(this) {
                task.ensureActive()
                check(staging.renameTo(pack.directory)) { "Cannot install runtime" }
                availability.runtimeInstalled()
                set(pack, RuntimeDownload.Installed)
            }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun unpack(archive: File, staging: File) {
        val task = currentCoroutineContext()
        val prefix = staging.canonicalPath + File.separator
        ZipFile(archive).use { zip ->
            val buffer = ByteArray(64 * 1024)
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val file = File(staging, entry.name).canonicalFile
                require(file.path.startsWith(prefix)) { "Invalid runtime archive path" }
                file.parentFile!!.mkdirs()
                zip.getInputStream(entry).use { input ->
                    file.outputStream().use { output ->
                        while (true) {
                            task.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                check(file.setReadOnly()) { "Cannot protect installed runtime" }
            }
        }
    }

    private fun set(pack: SpeechRuntimePack, state: RuntimeDownload) = states.update { it + (pack to state) }
}
