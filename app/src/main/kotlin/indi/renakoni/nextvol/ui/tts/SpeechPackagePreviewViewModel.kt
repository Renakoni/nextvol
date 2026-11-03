package indi.renakoni.nextvol.ui.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.SpeechPackageError
import hnovel.speech.SpeechPackagePreview
import hnovel.speech.HttpSpeechPackageImport
import hnovel.speech.previewHttpSpeechPackage
import hnovel.speech.previewSpeechPackage
import hnovel.speech.OfflinePackagePreview
import hnovel.speech.OfflinePackagePlan
import hnovel.speech.previewOfflineVoice
import hnovel.speech.MatchaPackageError
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.SpeechConfigurationImport
import indi.renakoni.nextvol.tts.previewSpeechConfiguration
import indi.renakoni.nextvol.tts.speechPackageReferences
import indi.renakoni.nextvol.tts.withInstalledEngines
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineInstallException
import indi.renakoni.nextvol.tts.matcha.OfflineInstallError
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

data class SpeechPackagePreviewState(
    val preview: SpeechPackagePreview? = null,
    val httpImports: Map<Int, HttpSpeechPackageImport> = emptyMap(),
    val offlineImports: Map<Pair<Int, Int>, OfflinePackagePreview> = emptyMap(),
    val availability: Map<String, OfflineAvailability> = emptyMap(),
    val installing: Boolean = false,
    val installingId: String? = null,
    val installed: String? = null,
    val installError: Int? = null,
    val configuration: SpeechConfigurationImport? = null,
    val conflicts: List<String> = emptyList(),
    val configurationSaved: Boolean = false,
    val offlineConfigurations: Map<Pair<Int, Int>, InstalledOfflineVoice> = emptyMap(),
    val offlineEngineImports: Map<Int, hnovel.speech.SpeechEngineConfig> = emptyMap(),
    val updatedOfflineVoices: Set<String> = emptySet(),
)

/** Resource installation is independent of whether this device can execute the selected engine. */
@HiltViewModel
class SpeechPackagePreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val availability: OfflineAvailabilityChecker,
    private val http: indi.renakoni.nextvol.tts.HttpSpeechRepository,
    private val engines: OfflineEngineRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechPackagePreviewState())
    val state = mutableState.asStateFlow()
    private var input: Uri? = null
    private var reading: Job? = null
    private var installing: Job? = null

    fun read(uri: Uri) {
        if (uri == input) return
        input = uri
        reading?.cancel()
        mutableState.value = SpeechPackagePreviewState()
        reading = viewModelScope.launch {
            val result = try { withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                var name = ""
                val parsed = try {
                    name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0)?.take(256).orEmpty() else ""
                    }.orEmpty()
                    task.ensureActive()
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        // A seekable descriptor lets ZipFile read the index without copying multi-GB models.
                        previewSpeechPackage(File("/proc/self/fd/${descriptor.fd}"), name) { task.ensureActive() }
                    } ?: SpeechPackagePreview(name, error = SpeechPackageError.ReadFailed)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SpeechPackagePreview(name, error = SpeechPackageError.ReadFailed) }
                val preview = parsed.withInstalledEngines(http.engines() + engines.all())
                val imports = preview.engines.mapIndexedNotNull { index, engine ->
                    task.ensureActive()
                    if (engine.isOnline) index to previewHttpSpeechPackage(engine) else null
                }.toMap()
                val local = preview.engines.flatMapIndexed { index, engine ->
                    if (!engine.isOnline) engine.voices.mapIndexed { voiceIndex, voice ->
                        task.ensureActive()
                        (index to voiceIndex) to previewOfflineVoice(preview, engine, voice)
                    } else emptyList()
                }.toMap()
                val updates = local.filterValues { it.error == MatchaPackageError.MissingResource ||
                    preview.resources.isEmpty() && it.error == MatchaPackageError.Ambiguous && it.detail == "voice"
                }.mapNotNull { (key, _) ->
                    val engine = preview.engines[key.first]
                    try { offline.previewConfiguration(engine, engine.voices[key.second])?.let { key to it } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IllegalArgumentException) { null }
                }.toMap()
                val capabilities = try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        ZipFile(File("/proc/self/fd/${descriptor.fd}")).use { zip ->
                            local.values.mapNotNull { it.plan }.associate { plan ->
                                task.ensureActive()
                                plan.id to availability.inspect(plan.configuration,
                                    plan.resources.map { it.path.removePrefix(plan.directory + "/") }.toSet()) { path ->
                                    zip.getInputStream(requireNotNull(zip.getEntry("${plan.directory}/$path")))
                                }
                            }
                        }
                    }.orEmpty()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyMap() }
                val configuration = previewSpeechConfiguration(preview, speechPackageReferences(http.sources(), offline.voices()),
                    context.getString(R.string.tts_replace_ungrouped))
                val engineImports = preview.engines.mapIndexedNotNull { index, engine ->
                    if (engine.isOnline || engine.raw == null || engine.type !in setOf(null, "inner")) return@mapIndexedNotNull null
                    try { index to hnovel.speech.readOfflineEngineDefinition(engine) }
                    catch (_: IllegalArgumentException) { null }
                }.toMap()
                SpeechPackagePreviewState(preview, imports, local, capabilities,
                    configuration = configuration, conflicts = configuration.conflicts(settings.get()), offlineConfigurations = updates,
                    offlineEngineImports = engineImports)
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SpeechPackagePreviewState(SpeechPackagePreview("", error = SpeechPackageError.ReadFailed)) }
            currentCoroutineContext().ensureActive()
            mutableState.value = result
        }
    }

    fun importConfiguration(replace: Boolean) {
        val configuration = state.value.configuration ?: return
        if (state.value.installing || configuration.error != null || !configuration.hasChanges) return
        mutableState.update { it.copy(installing = true, installError = null) }
        viewModelScope.launch {
            try {
                // All rule, role and pool changes share the existing single settings write.
                withContext(NonCancellable) { settings.update { configuration.applyTo(it, replace) } }
                mutableState.update { it.copy(configurationSaved = true, conflicts = emptyList()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(installError = R.string.tts_import_failed) } }
            finally { mutableState.update { it.copy(installing = false) } }
        }
    }

    fun importEngine(engine: hnovel.speech.SpeechEngineConfig, onSaved: () -> Unit) {
        if (state.value.installing) return
        mutableState.update { it.copy(installing = true, installError = null) }
        viewModelScope.launch {
            try {
                if (engine.isOnline) http.registerEngine(engine) else engines.register(engine)
                onSaved()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(installError = R.string.tts_import_failed) } }
            finally { mutableState.update { it.copy(installing = false) } }
        }
    }

    fun importOfflineConfiguration(draft: InstalledOfflineVoice) {
        if (state.value.installing || draft !in state.value.offlineConfigurations.values) return
        mutableState.update { it.copy(installing = true, installingId = draft.reference.id, installed = null, installError = null) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val voice = offline.importConfiguration(draft)
                    settings.update { if (it.offlineVoice?.id == voice.reference.id) it.copy(offlineVoice = voice.reference) else it }
                    mutableState.update { it.copy(installed = voice.name,
                        updatedOfflineVoices = it.updatedOfflineVoices + voice.reference.id) }
                }
                refreshConfiguration()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(installError = R.string.tts_import_failed) } }
            finally { mutableState.update { it.copy(installing = false) } }
        }
    }

    private suspend fun refreshConfiguration() {
        val preview = state.value.preview ?: return
        val configuration = previewSpeechConfiguration(preview, speechPackageReferences(http.sources(), offline.voices()),
            context.getString(R.string.tts_replace_ungrouped))
        val conflicts = configuration.conflicts(settings.get())
        mutableState.update { it.copy(configuration = configuration, conflicts = conflicts) }
    }

    fun install(plan: OfflinePackagePlan) {
        val uri = input ?: return
        if (state.value.installing || state.value.offlineImports.values.none { it.plan == plan }) return
        mutableState.update { it.copy(installing = true, installingId = plan.id, installError = null, installed = null) }
        installing = viewModelScope.launch {
            try {
                val voice = try {
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
                            settings.update {
                                if (it.offlineVoice?.id == installed.reference.id) it.copy(offlineVoice = installed.reference) else it
                            }
                        }
                    }
                }
                mutableState.update { it.copy(installed = voice.name) }
                refreshConfiguration()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = when ((failure as? OfflineInstallException)?.error) {
                    OfflineInstallError.ChangedPackage -> R.string.tts_offline_changed
                    OfflineInstallError.InvalidArchive -> R.string.tts_package_invalid_archive
                    null -> R.string.tts_import_failed
                }
                mutableState.update { it.copy(installError = message) }
            } finally { mutableState.update { it.copy(installing = false) } }
        }
    }

    fun cancelInstall() { installing?.cancel() }
}
