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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.HttpSpeechPackageError
import hnovel.speech.HttpSpeechPackageImport
import hnovel.speech.MatchaPackageError
import hnovel.speech.OfflinePackagePlan
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.SpeechPackageError
import hnovel.speech.SpeechPackageKind
import hnovel.speech.SpeechPackageNotice
import hnovel.speech.SpeechPackagePreview
import hnovel.speech.SpeechPackageSupport
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechPackagePreviewScreen(
    preview: SpeechPackagePreview?,
    httpImports: Map<Int, HttpSpeechPackageImport> = emptyMap(),
    onImport: ((List<HttpSpeechDefinition>) -> Unit)? = null,
    onImportEngine: ((SpeechEngineConfig) -> Unit)? = null,
    onBack: () -> Unit,
    offline: SpeechPackagePreviewState = SpeechPackagePreviewState(),
    onInstall: (OfflinePackagePlan) -> Unit = {},
    onCancelInstall: () -> Unit = {},
    onImportConfiguration: (Boolean) -> Unit = {},
    onImportOfflineConfiguration: (InstalledOfflineVoice) -> Unit = {},
    onDismissEngineImportError: () -> Unit = {},
) {
    val tokens = NextVolDesign.tokens
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_package_preview),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
                actions = {
                    if (imports.isNotEmpty()) {
                        Box {
                            Button(
                                onClick = {
                                    if (imports.size == 1) imports.single().second() else importMenuExpanded = true
                                },
                                enabled = !offline.installing,
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text(stringResource(R.string.tts_package_import))
                            }
                            EditorialPopupMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
                            ) {
                                EditorialPopupMenuHeader(text = stringResource(R.string.tts_package_import))
                                imports.forEach { (engine, action) ->
                                    EditorialPopupMenuItem(
                                        text = engine.name.take(256),
                                        supportingText = engine.code.take(256),
                                        enabled = !offline.installing,
                                        onClick = {
                                            importMenuExpanded = false
                                            action()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (preview == null) {
                            Text(stringResource(R.string.tts_package_reading), style = MaterialTheme.typography.titleMedium)
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        } else {
                            if (preview.name.isNotEmpty()) {
                                Text(
                                    text = preview.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.W600,
                                )
                            }
                            preview.error?.let { error ->
                                Text(
                                    text = stringResource(error.messageResource),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } ?: run {
                                if (preview.kinds == setOf(SpeechPackageKind.Unknown)) {
                                    Text(stringResource(R.string.tts_package_unknown))
                                }
                                Text(
                                    text = stringResource(
                                        R.string.tts_package_counts,
                                        preview.engines.size,
                                        preview.engines.sumOf { it.voices.size },
                                        preview.resources.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            speechConfigurationPreview(offline, onImportConfiguration)
            preview?.engines?.forEachIndexed { engineIndex, engine ->
                val httpImport = httpImports[engineIndex]
                item(key = "engine:$engineIndex") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = engine.name.take(256),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.W600,
                            )
                            Text(
                                text = stringResource(
                                    R.string.tts_package_engine_info,
                                    engine.code.take(256),
                                    engine.type?.take(64) ?: stringResource(R.string.tts_package_undeclared),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val localVoices = offline.offlineImports.filterKeys { it.first == engineIndex }.values
                            val configurationUpdates = offline.offlineConfigurations.filterKeys { it.first == engineIndex }
                            val hasOffline = localVoices.any { it.plan != null } || configurationUpdates.isNotEmpty()
                            val offlineResolved = localVoices.isNotEmpty() && localVoices.count { it.plan != null } + configurationUpdates.size == localVoices.size
                            if (!hasOffline && httpImport?.sources.isNullOrEmpty() && httpImport?.engine == null) {
                                Text(
                                    text = stringResource(
                                        if (httpImport != null) {
                                            R.string.tts_package_cannot_import
                                        } else {
                                            when (engine.support) {
                                                SpeechPackageSupport.HttpAdapter -> R.string.tts_package_http_adapter
                                                SpeechPackageSupport.NeedsConfiguration -> R.string.tts_package_needs_configuration
                                                SpeechPackageSupport.Unsupported -> R.string.tts_package_unsupported
                                            }
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.W500,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            engine.notices.filterNot {
                                engine.support == SpeechPackageSupport.Unsupported && it == SpeechPackageNotice.UnsupportedEngine ||
                                    (httpImport != null || hasOffline) && it == SpeechPackageNotice.ImportPending ||
                                    offlineResolved && it in setOf(
                                        SpeechPackageNotice.RuntimeParameters,
                                        SpeechPackageNotice.ConfigurationRequired,
                                        SpeechPackageNotice.MissingResource,
                                    )
                            }.forEach { notice ->
                                Text(
                                    text = stringResource(notice.messageResource),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            httpImport?.error?.let { error ->
                                Text(
                                    text = stringResource(error.messageResource),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                items(
                    if ("refs:$engineIndex" in expanded) engine.requiredResources else engine.requiredResources.take(5),
                    key = { "ref:$engineIndex:${it.path}" },
                ) { resource ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (resource.present) R.string.tts_package_resource_present else R.string.tts_package_resource_missing,
                                resource.path.take(1024),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (engine.requiredResources.size > 5) {
                    item(key = "more-refs:$engineIndex") {
                        TextButton(
                            onClick = {
                                expanded = if ("refs:$engineIndex" in expanded) expanded - "refs:$engineIndex" else expanded + "refs:$engineIndex"
                            },
                        ) {
                            Text(
                                if ("refs:$engineIndex" in expanded) stringResource(R.string.tts_package_collapse)
                                else stringResource(R.string.tts_package_show_resource_refs, engine.requiredResources.size),
                            )
                        }
                    }
                }
                if (engine.voices.isNotEmpty()) {
                    item(key = "voices-title:$engineIndex") {
                        PackageSectionHeader(
                            title = stringResource(R.string.tts_package_voices, engine.voices.size),
                            expanded = "voices:$engineIndex" in expanded,
                            onToggle = if (engine.voices.size > 5) ({
                                expanded = if ("voices:$engineIndex" in expanded) expanded - "voices:$engineIndex" else expanded + "voices:$engineIndex"
                            }) else null,
                        )
                    }
                }
                itemsIndexed(
                    if ("voices:$engineIndex" in expanded) engine.voices else engine.voices.take(5),
                    key = { index, _ -> "voice:$engineIndex:$index" },
                ) { voiceIndex, voice ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = voice.name.take(256).ifEmpty { stringResource(R.string.tts_package_unnamed_voice) },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.W600,
                            )
                            if (voice.code.isNotEmpty()) {
                                Text(
                                    text = voice.code.take(256),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            offline.offlineImports[engineIndex to voiceIndex]?.let { local ->
                                val plan = local.plan
                                val update = offline.offlineConfigurations[engineIndex to voiceIndex]
                                if (update != null) {
                                    Text(stringResource(R.string.tts_config_voice_update_description), style = MaterialTheme.typography.bodySmall)
                                    FilledTonalButton(
                                        onClick = { onImportOfflineConfiguration(update) },
                                        enabled = !offline.installing && update.reference.id !in offline.updatedOfflineVoices,
                                        shape = CircleShape,
                                    ) {
                                        Text(stringResource(R.string.tts_config_voice_update))
                                    }
                                    if (offline.installingId == update.reference.id) {
                                        if (offline.installing) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                                        offline.installed?.let { Text(stringResource(R.string.tts_config_voice_updated, it)) }
                                        offline.installError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                    }
                                } else if (plan != null) {
                                    Text(
                                        text = stringResource(R.string.tts_offline_size, Formatter.formatShortFileSize(context, plan.resources.sumOf { it.bytes })),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    val status = offline.availability[plan.id]
                                    if (status?.canRun != true) {
                                        Text(
                                            text = stringResource(status?.message ?: R.string.tts_offline_check_incomplete, status?.detail.orEmpty()),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (status?.canRun == false) {
                                        Text(stringResource(R.string.tts_offline_install_only), style = MaterialTheme.typography.bodySmall)
                                    }
                                    FilledTonalButton(
                                        onClick = { onInstall(plan) },
                                        enabled = !offline.installing,
                                        shape = CircleShape,
                                    ) {
                                        Text(stringResource(R.string.tts_offline_install, voice.name))
                                    }
                                    if (plan.id == offline.installingId) {
                                        if (offline.installing) {
                                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
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
                                    Text(
                                        text = stringResource(reason, local.detail),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!preview?.documents.isNullOrEmpty()) {
                item(key = "documents-title") {
                    PackageSectionHeader(stringResource(R.string.tts_package_documents))
                }
                items(preview.documents, key = { "document:${it.path}" }) { document ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(document.path, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (!preview?.resources.isNullOrEmpty()) {
                item(key = "resources-title") {
                    PackageSectionHeader(
                        title = stringResource(R.string.tts_package_resources),
                        expanded = "resources" in expanded,
                        onToggle = if (preview.resources.size > 8) ({
                            expanded = if ("resources" in expanded) expanded - "resources" else expanded + "resources"
                        }) else null,
                    )
                }
                itemsIndexed(
                    if ("resources" in expanded) preview.resources else preview.resources.take(8),
                    key = { index, _ -> "resource:$index" },
                ) { _, resource ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(resource.path.take(1024), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = Formatter.formatShortFileSize(context, resource.bytes),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (offline.engineImportFailed) {
        AlertDialog(
            onDismissRequest = onDismissEngineImportError,
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_package_import_failed), fontWeight = FontWeight.W600) },
            text = { Text(stringResource(R.string.tts_package_engine_save_failed), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = onDismissEngineImportError, shape = CircleShape) {
                    Text(stringResource(R.string.tts_package_error_dismiss))
                }
            },
        )
    }
}

@Composable
private fun PackageSectionHeader(title: String, expanded: Boolean = false, onToggle: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.W600,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onToggle != null) {
            val actionLabel = stringResource(if (expanded) R.string.tts_package_collapse else R.string.tts_package_show_all)
            TextButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = "$title, $actionLabel" }) {
                Text(actionLabel)
            }
        }
    }
}

private val SpeechPackageNotice.messageResource: Int
    get() = when (this) {
        SpeechPackageNotice.MissingEngine -> R.string.tts_package_missing_engine
        SpeechPackageNotice.MissingVoices -> R.string.tts_package_missing_voices
        SpeechPackageNotice.MissingRequest -> R.string.tts_package_missing_request
        SpeechPackageNotice.ConfigurationRequired -> R.string.tts_package_configuration_required
        SpeechPackageNotice.UnsupportedEngine -> R.string.tts_package_unsupported_engine
        SpeechPackageNotice.RuntimeParameters -> R.string.tts_package_runtime_parameters
        SpeechPackageNotice.MissingResource -> R.string.tts_package_missing_resource
        SpeechPackageNotice.ImportPending -> R.string.tts_package_import_pending
    }

private val SpeechPackageError.messageResource: Int
    get() = when (this) {
        SpeechPackageError.ReadFailed -> R.string.tts_package_read_failed
        SpeechPackageError.UnsupportedFile -> R.string.tts_package_unsupported_file
        SpeechPackageError.InvalidArchive -> R.string.tts_package_invalid_archive
        SpeechPackageError.DuplicatePath -> R.string.tts_package_duplicate_path
        SpeechPackageError.InvalidYaml -> R.string.tts_package_invalid_yaml
        SpeechPackageError.YamlAlias -> R.string.tts_package_yaml_alias
        SpeechPackageError.DuplicateKey -> R.string.tts_package_duplicate_key
        SpeechPackageError.InvalidStructure -> R.string.tts_package_invalid_structure
    }

private val HttpSpeechPackageError.messageResource: Int
    get() = when (this) {
        HttpSpeechPackageError.UnsupportedEngine -> R.string.tts_package_unsupported_engine
        HttpSpeechPackageError.UnsupportedRequest -> R.string.tts_package_http_request_unsupported
        HttpSpeechPackageError.RequiresParameters -> R.string.tts_package_http_parameters_required
        HttpSpeechPackageError.InvalidConfiguration -> R.string.tts_package_invalid_structure
    }
