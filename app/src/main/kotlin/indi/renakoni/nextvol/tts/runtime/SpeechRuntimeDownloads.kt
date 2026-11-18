package indi.renakoni.nextvol.tts.runtime

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
    data object Unsupported : RuntimeDownload
    data class Downloading(val percent: Int?) : RuntimeDownload
    data object Installed : RuntimeDownload
    data class Failed(val detail: String) : RuntimeDownload
}

@Singleton
internal class SpeechRuntimeDownloads @Inject constructor(
    private val downloader: UpdateArtifactDownloader,
    private val availability: OfflineAvailabilityChecker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<SpeechRuntimePack, Job>()
    private val states = MutableStateFlow<Map<SpeechRuntimePack, RuntimeDownload>>(SpeechRuntimePack.entries.associateWith {
        when {
            !it.supported -> RuntimeDownload.Unsupported
            it.installed -> RuntimeDownload.Installed
            else -> RuntimeDownload.Available
        }
    })
    val changes = states.asStateFlow()

    @Synchronized fun start(pack: SpeechRuntimePack) {
        if (!pack.supported || pack.installed || jobs[pack]?.isCompleted == false) return
        set(pack, RuntimeDownload.Downloading(null))
        val job = scope.launch {
            try {
                install(pack)
                if (pack == SpeechRuntimePack.Vendor) availability.runtimeInstalled()
                set(pack, RuntimeDownload.Installed)
                if (pack == SpeechRuntimePack.Sovits) DeviceAcceleration.detect(SpeechLibraries.context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                set(pack, RuntimeDownload.Failed(failure.localizedMessage.orEmpty()))
            }
        }
        jobs[pack] = job
        job.invokeOnCompletion { cause ->
            synchronized(this) {
                if (jobs[pack] == job) {
                    jobs.remove(pack)
                    if (cause is CancellationException && !pack.installed) set(pack, RuntimeDownload.Available)
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
            // Only a complete, APK-pinned archive may supply executable libraries.
            var lastPercent = -1
            if (!downloader.downloadVerified(pack.url, pack.sha256, archive) { copied, total ->
                val progress = (copied * 100 / total).toInt()
                if (progress != lastPercent) {
                    lastPercent = progress
                    set(pack, RuntimeDownload.Downloading(progress))
                }
            }) throw IOException("Empty runtime download")
            staging.deleteRecursively()
            check(staging.mkdir()) { "Cannot create runtime staging directory" }
            val prefix = staging.canonicalPath + File.separator
            ZipFile(archive).use { zip ->
                for (entry in zip.entries()) {
                    currentCoroutineContext().ensureActive()
                    if (entry.isDirectory) continue
                    val file = File(staging, entry.name).canonicalFile
                    require(file.path.startsWith(prefix)) { "Invalid runtime archive path" }
                    file.parentFile!!.mkdirs()
                    zip.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
                    check(file.setReadOnly()) { "Cannot protect installed runtime" }
                }
            }
            currentCoroutineContext().ensureActive()
            check(staging.renameTo(pack.directory)) { "Cannot install runtime" }
            // An APK update starts a new process; only its pinned revision can be in use.
            root.listFiles()?.filter { it.isDirectory && it != pack.directory }?.forEach { it.deleteRecursively() }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun set(pack: SpeechRuntimePack, state: RuntimeDownload) = states.update { it + (pack to state) }
}
