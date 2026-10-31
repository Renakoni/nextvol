package indi.renakoni.nextvol.ui.tts

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hnovel.speech.SpeechPackageError
import hnovel.speech.SpeechPackageKind
import hnovel.speech.SpeechPackageNotice
import hnovel.speech.SpeechPackagePreview
import hnovel.speech.SpeechPackageSupport
import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.HttpSpeechPackageError
import hnovel.speech.HttpSpeechPackageImport
import hnovel.speech.OfflinePackagePlan
import hnovel.speech.MatchaPackageError
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPackagePreviewScreen(
    preview: SpeechPackagePreview?,
    httpImports: Map<Int, HttpSpeechPackageImport> = emptyMap(),
    onImport: ((List<HttpSpeechDefinition>) -> Unit)? = null,
    onBack: () -> Unit,
    offline: SpeechPackagePreviewState = SpeechPackagePreviewState(),
    onInstall: (OfflinePackagePlan) -> Unit = {},
    onCancelInstall: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val context = LocalContext.current
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_package_preview, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-package-preview"),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            item(key = "summary") {
                SettingsCategory {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (preview == null) {
                            Text(stringResource(R.string.tts_package_reading), style = MaterialTheme.typography.headlineSmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            if (preview.name.isNotEmpty()) Text(preview.name, style = MaterialTheme.typography.headlineSmall)
                            preview.error?.let { error ->
                                Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium)
                            } ?: run {
                                Text(stringResource(if (preview.kinds == setOf(SpeechPackageKind.Unknown)) R.string.tts_package_unknown
                                    else R.string.tts_package_valid), color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyLarge)
                                Text(preview.kinds.map { stringResource(it.labelResource) }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.tts_package_counts, preview.engines.size,
                                    preview.engines.sumOf { it.voices.size }, preview.resources.size), style = MaterialTheme.typography.bodyMedium)
                                if (preview.kinds.any { it != SpeechPackageKind.Voices && it != SpeechPackageKind.Unknown }) {
                                    Text(stringResource(R.string.tts_package_config_unsupported), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Text(stringResource(if (offline.offlineImports.isNotEmpty()) R.string.tts_offline_import_note
                            else if (httpImports.values.any { it.sources.isNotEmpty() }) R.string.tts_package_http_static
                            else R.string.tts_package_static), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (offline.installing) {
                            Text(stringResource(R.string.tts_offline_installing))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = onCancelInstall) { Text(stringResource(R.string.cancel)) }
                        }
                        offline.installed?.let { Text(stringResource(R.string.tts_offline_installed, it), color = MaterialTheme.colorScheme.primary) }
                        offline.installError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            preview?.engines?.forEachIndexed { engineIndex, engine ->
                val httpImport = httpImports[engineIndex]
                item(key = "engine:$engineIndex") {
                    SettingsCategory(title = engine.name.take(256)) {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.tts_package_engine_info, engine.code.take(256),
                                engine.type?.take(64) ?: stringResource(R.string.tts_package_undeclared)), style = MaterialTheme.typography.bodyMedium)
                            val localVoices = offline.offlineImports.filterKeys { it.first == engineIndex }.values
                            val hasOffline = localVoices.any { it.plan != null }
                            val offlineResolved = localVoices.isNotEmpty() && localVoices.all { it.plan != null }
                            Text(stringResource(if (hasOffline) R.string.tts_offline_profile else if (httpImport != null) {
                                if (httpImport.sources.isNotEmpty()) R.string.tts_package_can_import else R.string.tts_package_cannot_import
                            } else when (engine.support) {
                                SpeechPackageSupport.HttpAdapter -> R.string.tts_package_http_adapter
                                SpeechPackageSupport.NeedsConfiguration -> R.string.tts_package_needs_configuration
                                SpeechPackageSupport.Unsupported -> R.string.tts_package_unsupported
                            }), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                            engine.notices.filterNot { engine.support == SpeechPackageSupport.Unsupported && it == SpeechPackageNotice.UnsupportedEngine ||
                                (httpImport != null || hasOffline) && it == SpeechPackageNotice.ImportPending ||
                                offlineResolved && it in setOf(SpeechPackageNotice.RuntimeParameters,
                                    SpeechPackageNotice.ConfigurationRequired, SpeechPackageNotice.MissingResource) }.forEach { notice ->
                                Text(stringResource(notice.messageResource), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            httpImport?.error?.let { error -> Text(stringResource(error.messageResource), style = MaterialTheme.typography.bodyMedium) }
                            if (httpImport?.hasLocalAudioSettings == true) {
                                Text(stringResource(R.string.tts_package_local_audio), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.tts_package_not_verified), style = MaterialTheme.typography.bodySmall)
                            if (onImport != null && !httpImport?.sources.isNullOrEmpty()) {
                                FilledTonalButton(onClick = { onImport(httpImport.sources) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.tts_import_sources))
                                }
                            }
                        }
                    }
                }
                items(if ("refs:$engineIndex" in expanded) engine.requiredResources else engine.requiredResources.take(5),
                    key = { "ref:$engineIndex:${it.path}" }) { resource ->
                    Text(stringResource(if (resource.present) R.string.tts_package_resource_present else R.string.tts_package_resource_missing,
                        resource.path.take(1024)), Modifier.padding(horizontal = 38.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (engine.requiredResources.size > 5) item(key = "more-refs:$engineIndex") {
                    TextButton(onClick = { expanded = if ("refs:$engineIndex" in expanded) expanded - "refs:$engineIndex" else expanded + "refs:$engineIndex" },
                        modifier = Modifier.padding(horizontal = 26.dp)) {
                        Text(if ("refs:$engineIndex" in expanded) stringResource(R.string.tts_package_collapse)
                            else stringResource(R.string.tts_package_show_resource_refs, engine.requiredResources.size))
                    }
                }
                itemsIndexed(if ("voices:$engineIndex" in expanded) engine.voices else engine.voices.take(5),
                    key = { index, _ -> "voice:$engineIndex:$index" }) { voiceIndex, voice ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(voice.name.take(256).ifEmpty { stringResource(R.string.tts_package_unnamed_voice) }, style = MaterialTheme.typography.headlineSmall)
                        if (voice.code.isNotEmpty()) Text(voice.code.take(256), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        offline.offlineImports[engineIndex to voiceIndex]?.let { local ->
                            val plan = local.plan
                            if (plan != null) {
                                Text(stringResource(R.string.tts_offline_size, Formatter.formatShortFileSize(context, plan.resources.sumOf { it.bytes })),
                                    style = MaterialTheme.typography.bodyMedium)
                                if (!offline.offlineSupported) Text(stringResource(R.string.tts_offline_device), style = MaterialTheme.typography.bodySmall)
                                FilledTonalButton(onClick = { onInstall(plan) }, enabled = offline.offlineSupported && !offline.installing) {
                                    Text(stringResource(R.string.tts_offline_install, voice.name))
                                }
                                if (plan.id == offline.installingId) {
                                    if (offline.installing) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        TextButton(onClick = onCancelInstall) { Text(stringResource(R.string.cancel)) }
                                    }
                                    offline.installed?.let { Text(stringResource(R.string.tts_offline_installed, it), style = MaterialTheme.typography.bodySmall) }
                                    offline.installError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                }
                            } else {
                                val reason = when (local.error) {
                                    MatchaPackageError.Parameters -> R.string.tts_offline_parameters
                                    MatchaPackageError.UnsafePath -> R.string.tts_offline_path
                                    MatchaPackageError.Ambiguous -> R.string.tts_offline_ambiguous
                                    MatchaPackageError.MissingResource -> R.string.tts_offline_missing
                                    else -> R.string.tts_offline_model
                                }
                                Text(stringResource(reason, local.detail), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (engine.voices.size > 5) item(key = "more:$engineIndex") {
                    TextButton(onClick = { expanded = if ("voices:$engineIndex" in expanded) expanded - "voices:$engineIndex" else expanded + "voices:$engineIndex" },
                        modifier = Modifier.padding(horizontal = 26.dp)) {
                        Text(if ("voices:$engineIndex" in expanded) stringResource(R.string.tts_package_collapse)
                            else stringResource(R.string.tts_package_show_voices, engine.voices.size))
                    }
                }
            }
            if (!preview?.documents.isNullOrEmpty()) {
                item(key = "documents-title") {
                    SectionHeader(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), stringResource(R.string.tts_package_documents))
                }
                items(preview.documents, key = { "document:${it.path}" }) { document ->
                    Text(document.path, Modifier.padding(horizontal = 38.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!preview?.resources.isNullOrEmpty()) {
                item(key = "resources-title") {
                    SectionHeader(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), stringResource(R.string.tts_package_resources))
                }
                itemsIndexed(if ("resources" in expanded) preview.resources else preview.resources.take(8), key = { index, _ -> "resource:$index" }) { _, resource ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 8.dp)) {
                        Text(resource.path.take(1024), style = MaterialTheme.typography.bodyMedium)
                        Text(Formatter.formatShortFileSize(context, resource.bytes), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (preview.resources.size > 8) item(key = "more-resources") {
                    TextButton(onClick = { expanded = if ("resources" in expanded) expanded - "resources" else expanded + "resources" },
                        modifier = Modifier.padding(horizontal = 26.dp)) {
                        Text(if ("resources" in expanded) stringResource(R.string.tts_package_collapse)
                            else stringResource(R.string.tts_package_show_resources, preview.resources.size))
                    }
                }
            }
        }
    }
}

private val SpeechPackageKind.labelResource: Int get() = when (this) {
    SpeechPackageKind.Voices -> R.string.tts_package_voices
    SpeechPackageKind.Roles -> R.string.tts_package_roles
    SpeechPackageKind.Replacements -> R.string.tts_package_replacements
    SpeechPackageKind.VoicePool -> R.string.tts_package_pool
    SpeechPackageKind.Unknown -> R.string.tts_package_unknown
}

private val SpeechPackageNotice.messageResource: Int get() = when (this) {
    SpeechPackageNotice.MissingEngine -> R.string.tts_package_missing_engine
    SpeechPackageNotice.MissingVoices -> R.string.tts_package_missing_voices
    SpeechPackageNotice.MissingRequest -> R.string.tts_package_missing_request
    SpeechPackageNotice.ConfigurationRequired -> R.string.tts_package_configuration_required
    SpeechPackageNotice.UnsupportedEngine -> R.string.tts_package_unsupported_engine
    SpeechPackageNotice.RuntimeParameters -> R.string.tts_package_runtime_parameters
    SpeechPackageNotice.MissingResource -> R.string.tts_package_missing_resource
    SpeechPackageNotice.ImportPending -> R.string.tts_package_import_pending
}

private val SpeechPackageError.messageResource: Int get() = when (this) {
    SpeechPackageError.ReadFailed -> R.string.tts_package_read_failed
    SpeechPackageError.UnsupportedFile -> R.string.tts_package_unsupported_file
    SpeechPackageError.InvalidArchive -> R.string.tts_package_invalid_archive
    SpeechPackageError.DuplicatePath -> R.string.tts_package_duplicate_path
    SpeechPackageError.InvalidYaml -> R.string.tts_package_invalid_yaml
    SpeechPackageError.YamlAlias -> R.string.tts_package_yaml_alias
    SpeechPackageError.DuplicateKey -> R.string.tts_package_duplicate_key
    SpeechPackageError.InvalidStructure -> R.string.tts_package_invalid_structure
}

private val HttpSpeechPackageError.messageResource: Int get() = when (this) {
    HttpSpeechPackageError.UnsupportedEngine -> R.string.tts_package_unsupported_engine
    HttpSpeechPackageError.UnsupportedRequest -> R.string.tts_package_http_request_unsupported
    HttpSpeechPackageError.RequiresParameters -> R.string.tts_package_http_parameters_required
    HttpSpeechPackageError.InvalidConfiguration -> R.string.tts_package_invalid_structure
}
