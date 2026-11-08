package indi.renakoni.nextvol.tts.matcha

import hnovel.speech.OfflinePackagePlan
import hnovel.speech.previewOfflineVoice
import hnovel.speech.previewSpeechPackage
import hnovel.speech.safeSpeechPackagePath
import indi.renakoni.nextvol.tts.offline.legacy.prepareMicrosoftResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Prepares files in a caller-owned staging directory and returns the package avatar filename. */
internal suspend fun prepareOfflineSpeechPackage(file: File, plan: OfflinePackagePlan, stage: File): String? {
    val task = currentCoroutineContext()
    var avatarFile: String? = null
    ZipFile(file).use { zip ->
        val seen = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            task.ensureActive()
            val entry = entries.nextElement()
            val path = entry.name.removeSuffix("/")
            if (seen.size >= 100_000 || !safeSpeechPackagePath(path) || !seen.add(path)) {
                throw OfflineInstallException(OfflineInstallError.InvalidArchive)
            }
        }
        // Reopen the document and resolve the selection again before copying any resource.
        val preview = previewSpeechPackage(file) { task.ensureActive() }
        val actual = preview.engines.flatMap { engine -> engine.voices.filter { voice ->
            engine.code + "\u0000" + voice.code == plan.id
        }.mapNotNull { voice ->
            task.ensureActive()
            previewOfflineVoice(preview, engine, voice).plan
        } }.singleOrNull { it.id == plan.id }
        if (actual != plan) throw OfflineInstallException(OfflineInstallError.ChangedPackage)
        val buffer = ByteArray(64 * 1024)
        for (resource in plan.resources) {
            task.ensureActive()
            val relative = resource.path.removePrefix(plan.directory + "/")
            if (!resource.path.startsWith(plan.directory + "/") || !safeSpeechPackagePath(relative)) {
                throw OfflineInstallException(OfflineInstallError.InvalidArchive)
            }
            val entry = zip.getEntry(resource.path)
            if (entry == null || entry.isDirectory || entry.size < 0 || entry.size != resource.bytes) {
                throw OfflineInstallException(OfflineInstallError.ChangedPackage)
            }
            val destination = File(stage, relative)
            destination.parentFile!!.mkdirs()
            var total = 0L
            zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                while (true) {
                    task.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > resource.bytes) throw OfflineInstallException(OfflineInstallError.InvalidArchive)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            if (total != resource.bytes) throw OfflineInstallException(OfflineInstallError.InvalidArchive)
        }
        plan.avatar?.let { avatar ->
            val entry = zip.getEntry(avatar.path)
            if (entry != null && !entry.isDirectory) {
                val name = ".avatar-${UUID.randomUUID()}"
                zip.getInputStream(entry).use { input -> File(stage, name).outputStream().use { output ->
                    while (true) {
                        task.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
                avatarFile = name
            }
        }
    }
    if (plan.configuration.engine == "microsoft") {
        prepareMicrosoftResources(plan.resources.map { File(stage, it.path.removePrefix(plan.directory + "/")) }) {
            task.ensureActive()
        }
    }
    plan.configuration.sherpa?.let { MatchaResources(stage, it).validate() }
    return avatarFile
}
