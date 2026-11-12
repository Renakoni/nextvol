package indi.renakoni.nextvol.ui.tts

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPackagePreviewScreen(
    preview: SpeechPackagePreview?,
    httpImports: Map<Int, HttpSpeechPackageImport> = emptyMap(),
    onImport: ((List<HttpSpeechDefinition>) -> Unit)? = null,
    onImportEngine: ((hnovel.speech.SpeechEngineConfig) -> Unit)? = null,
    onBack: () -> Unit,
    offline: SpeechPackagePreviewState = SpeechPackagePreviewState(),
    onInstall: (OfflinePackagePlan) -> Unit = {},
    onCancelInstall: () -> Unit = {},
    onImportConfiguration: (Boolean) -> Unit = {},
    onImportOfflineConfiguration: (InstalledOfflineVoice) -> Unit = {},
    onDismissEngineImportError: () -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val imports = preview?.engines.orEmpty().mapIndexedNotNull { index, engine ->
        val http = httpImports[index]
        val configuration = if (http?.sources?.isEmpty() == true) http.engine
            else if (engine.voices.isEmpty()) offline.offlineEngineImports[index] else null
        val action: (() -> Unit)? = when {
            onImport != null && !http?.sources.isNullOrEmpty() -> { { onImport(http.sources) } }
            onImportEngine != null && configuration != null -> { { onImportEngine(configuration) } }
            else -> null
        }
        action?.let { engine to it }
    }
    val context = LocalContext.current
    Scaffold(topBar = {
        SpeechSettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_package_preview, onBack) {
            if (imports.isNotEmpty()) Box {
                TextButton(onClick = {
                    if (imports.size == 1) imports.single().second() else importMenuExpanded = true
                }, enabled = !offline.installing) {
                    Text(stringResource(R.string.tts_package_import))
                }
                DropdownMenu(expanded = importMenuExpanded, onDismissRequest = { importMenuExpanded = false }) {
                    imports.forEach { (engine, action) ->
                        DropdownMenuItem(text = {
                            Column {
                                Text(engine.name.take(256), style = MaterialTheme.typography.bodyLarge)
                                Text(engine.code.take(256), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }, enabled = !offline.installing, onClick = {
                            importMenuExpanded = false
                            action()
                        })
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            item(key = "summary") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preview == null) {
                        Text(stringResource(R.string.tts_package_reading), style = MaterialTheme.typography.headlineSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        if (preview.name.isNotEmpty()) Text(preview.name, style = MaterialTheme.typography.displayMedium)
                        preview.error?.let { error ->
                            Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                        } ?: run {
                            if (preview.kinds == setOf(SpeechPackageKind.Unknown)) Text(stringResource(R.string.tts_package_unknown))
                            Text(stringResource(R.string.tts_package_counts, preview.engines.size,
                                preview.engines.sumOf { it.voices.size }, preview.resources.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            speechConfigurationPreview(offline, onImportConfiguration)
            preview?.engines?.forEachIndexed { engineIndex, engine ->
                val httpImport = httpImports[engineIndex]
                item(key = "engine:$engineIndex") {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(engine.name.take(256), style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(R.string.tts_package_engine_info, engine.code.take(256),
                            engine.type?.take(64) ?: stringResource(R.string.tts_package_undeclared)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val localVoices = offline.offlineImports.filterKeys { it.first == engineIndex }.values
                        val configurationUpdates = offline.offlineConfigurations.filterKeys { it.first == engineIndex }
                        val hasOffline = localVoices.any { it.plan != null } || configurationUpdates.isNotEmpty()
                        val offlineResolved = localVoices.isNotEmpty() && localVoices.count { it.plan != null } + configurationUpdates.size == localVoices.size
                        if (!hasOffline && httpImport?.sources.isNullOrEmpty() && httpImport?.engine == null) Text(stringResource(if (httpImport != null) {
                            R.string.tts_package_cannot_import
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
                    }
                }
                items(if ("refs:$engineIndex" in expanded) engine.requiredResources else engine.requiredResources.take(5),
                    key = { "ref:$engineIndex:${it.path}" }) { resource ->
                    Text(stringResource(if (resource.present) R.string.tts_package_resource_present else R.string.tts_package_resource_missing,
                        resource.path.take(1024)), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (engine.requiredResources.size > 5) item(key = "more-refs:$engineIndex") {
                    TextButton(onClick = { expanded = if ("refs:$engineIndex" in expanded) expanded - "refs:$engineIndex" else expanded + "refs:$engineIndex" },
                        modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(if ("refs:$engineIndex" in expanded) stringResource(R.string.tts_package_collapse)
                            else stringResource(R.string.tts_package_show_resource_refs, engine.requiredResources.size))
                    }
                }
                if (engine.voices.isNotEmpty()) item(key = "voices-title:$engineIndex") {
                    PackageSectionHeader(stringResource(R.string.tts_package_voices, engine.voices.size),
                        expanded = "voices:$engineIndex" in expanded,
                        onToggle = if (engine.voices.size > 5) ({
                            expanded = if ("voices:$engineIndex" in expanded) expanded - "voices:$engineIndex" else expanded + "voices:$engineIndex"
                        }) else null)
                }
                itemsIndexed(if ("voices:$engineIndex" in expanded) engine.voices else engine.voices.take(5),
                    key = { index, _ -> "voice:$engineIndex:$index" }) { voiceIndex, voice ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(voice.name.take(256).ifEmpty { stringResource(R.string.tts_package_unnamed_voice) }, style = MaterialTheme.typography.bodyLarge)
                        if (voice.code.isNotEmpty()) Text(voice.code.take(256), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        offline.offlineImports[engineIndex to voiceIndex]?.let { local ->
                            val plan = local.plan
                            val update = offline.offlineConfigurations[engineIndex to voiceIndex]
                            if (update != null) {
                                Text(stringResource(R.string.tts_config_voice_update_description), style = MaterialTheme.typography.bodySmall)
                                FilledTonalButton(onClick = { onImportOfflineConfiguration(update) },
                                    enabled = !offline.installing && update.reference.id !in offline.updatedOfflineVoices) {
                                    Text(stringResource(R.string.tts_config_voice_update))
                                }
                                if (offline.installingId == update.reference.id) {
                                    if (offline.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    offline.installed?.let { Text(stringResource(R.string.tts_config_voice_updated, it)) }
                                    offline.installError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                }
                            } else if (plan != null) {
                                Text(stringResource(R.string.tts_offline_size, Formatter.formatShortFileSize(context, plan.resources.sumOf { it.bytes })),
                                    style = MaterialTheme.typography.bodyMedium)
                                val status = offline.availability[plan.id]
                                if (status?.canRun != true) Text(stringResource(status?.message ?: R.string.tts_offline_check_incomplete, status?.detail.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall)
                                if (status?.canRun == false) Text(stringResource(R.string.tts_offline_install_only), style = MaterialTheme.typography.bodySmall)
                                FilledTonalButton(onClick = { onInstall(plan) }, enabled = !offline.installing) {
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
            }
            if (!preview?.documents.isNullOrEmpty()) {
                item(key = "documents-title") {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 8.dp))
                    PackageSectionHeader(stringResource(R.string.tts_package_documents))
                }
                items(preview.documents, key = { "document:${it.path}" }) { document ->
                    Text(document.path, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!preview?.resources.isNullOrEmpty()) {
                item(key = "resources-title") {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 8.dp))
                    PackageSectionHeader(stringResource(R.string.tts_package_resources), expanded = "resources" in expanded,
                        onToggle = if (preview.resources.size > 8) ({
                            expanded = if ("resources" in expanded) expanded - "resources" else expanded + "resources"
                        }) else null)
                }
                itemsIndexed(if ("resources" in expanded) preview.resources else preview.resources.take(8), key = { index, _ -> "resource:$index" }) { _, resource ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(resource.path.take(1024), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(Formatter.formatShortFileSize(context, resource.bytes), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (offline.engineImportFailed) AlertDialog(
        onDismissRequest = onDismissEngineImportError,
        title = { Text(stringResource(R.string.tts_package_import_failed), style = MaterialTheme.typography.displayMedium) },
        text = { Text(stringResource(R.string.tts_package_engine_save_failed), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismissEngineImportError) { Text(stringResource(R.string.tts_package_error_dismiss)) }
        },
    )
}

@Composable
private fun PackageSectionHeader(title: String, expanded: Boolean = false, onToggle: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onToggle != null) {
            val actionLabel = stringResource(if (expanded) R.string.tts_package_collapse else R.string.tts_package_show_all)
            TextButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = "$title, $actionLabel" }) {
                Text(actionLabel)
            }
        }
    }
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
