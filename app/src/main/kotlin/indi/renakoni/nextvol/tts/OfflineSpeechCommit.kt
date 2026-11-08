package indi.renakoni.nextvol.tts

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.OfflinePackagePlan
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class OfflineSpeechCommit @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineSpeechRepository,
    private val references: SpeechVoiceReferences,
) {
    internal suspend fun importConfiguration(draft: InstalledOfflineVoice, onCommitted: (InstalledOfflineVoice) -> Unit) {
        withContext(NonCancellable) {
            val voice = offline.importConfiguration(draft)
            references.replaceReference(SpeechVoiceRef(offlineVoice = draft.reference),
                SpeechVoiceRef(offlineVoice = voice.reference, label = voice.name))
            // Publish the committed result before returning to the cancellable caller.
            onCommitted(voice)
        }
    }

    internal suspend fun install(uri: Uri, plan: OfflinePackagePlan): InstalledOfflineVoice = try {
        withContext(Dispatchers.IO) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                offline.install(File("/proc/self/fd/${descriptor.fd}"), plan)
            } ?: error("Unreadable package")
        }
    } finally {
        // Publication can finish just as cancellation discards the IO result.
        // Keep the selected reference aligned with the committed catalog either way.
        withContext(NonCancellable) {
            offline.voices().singleOrNull { it.sourceKey == plan.id }?.let { installed ->
                references.replaceReference(SpeechVoiceRef(offlineVoice = installed.reference),
                    SpeechVoiceRef(offlineVoice = installed.reference, label = installed.name))
            }
        }
    }
}
