package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.ManagedSource
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.data.web.rules.LoginStatus
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import indi.renakoni.nextvol.ui.components.SectionHeader

/** All added sources use the same settings page; backend-specific controls follow their capabilities. */
@Composable
internal fun SourceSettings(source: ManagedSource, state: SourceManagementState, model: SourcesViewModel,
    onSearch: () -> Unit) {
    val id = source.id
    val installed = state.installed.find { ImportedRuleSources.id(it.definition) == id }
    val settings = state.ruleSettings
    val zLibrary = state.zLibrary.takeIf { id == ZLibrarySources.ID }
    val entry = state.registry.find { it.metadata.id == id }
    val capabilities = entry.actionCapabilities()
    var configuration by rememberSaveable(source.key, state.variable) { mutableStateOf(state.variable) }
    val grantedPermissions = installed?.origins?.map { it.origin } ?: zLibrary?.settings?.origins
    val savedPermissions = grantedPermissions.orEmpty().joinToString("\n")
    var permissionBaseline by rememberSaveable(source.key) { mutableStateOf(savedPermissions) }
    var permissions by rememberSaveable(source.key) { mutableStateOf(savedPermissions) }
    // SaveableStateProvider restores values without rechecking rememberSaveable inputs.
    if (permissionBaseline != savedPermissions) {
        permissionBaseline = savedPermissions
        permissions = savedPermissions
    }
    var mirrorBaseline by rememberSaveable(source.key) { mutableStateOf(zLibrary?.settings?.origin.orEmpty()) }
    var mirror by rememberSaveable(source.key) { mutableStateOf(mirrorBaseline) }
    if (mirrorBaseline != zLibrary?.settings?.origin.orEmpty()) {
        mirrorBaseline = zLibrary?.settings?.origin.orEmpty()
        mirror = mirrorBaseline
    }
    var showDetails by rememberSaveable(source.key) { mutableStateOf(false) }
    var editPermissions by rememberSaveable(source.key) { mutableStateOf(false) }
    var editMirror by rememberSaveable(source.key) { mutableStateOf(false) }
    var editVariable by rememberSaveable(source.key) { mutableStateOf(false) }
    var manualVariable by rememberSaveable(source.key) { mutableStateOf(false) }
    var deleting by rememberSaveable(source.key) { mutableStateOf(false) }
    val hasVariable = settings != null && !manualVariable && (settings.variableDescription.isNotBlank() || state.variable.isNotBlank())
    val variableEditor: @Composable () -> Unit = {
        if (editVariable && settings != null) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.variableDescription.isNotBlank()) Text(settings.variableDescription,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(configuration, { configuration = it; model.clearVariableError() }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sources_variable)) }, minLines = 2, maxLines = 6,
                enabled = !state.busy && state.storedSettingsAvailable)
            SourceSaveActions(state.variableError, state.busy || !state.storedSettingsAvailable, R.string.sources_save_variable,
                onSave = { model.saveVariable(id, configuration) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(text = stringResource(R.string.sources_basic_section))
        if (entry?.status == SourceStatus.Failed) Text(stringResource(R.string.sources_action_failed), color = MaterialTheme.colorScheme.error)
        if (zLibrary?.restorationFailed == true) Text(stringResource(R.string.zlibrary_settings_failed), color = MaterialTheme.colorScheme.error)
        if (source.preferences.enabled && grantedPermissions?.isEmpty() == true) Text(stringResource(R.string.sources_awaiting_permissions))
        val enabledLabel = stringResource(R.string.sources_enabled)
        ListItem(headlineContent = { Text(enabledLabel) },
            trailingContent = { Switch(source.preferences.enabled, { model.setEnabled(id, it) }, enabled = !state.busy,
                modifier = Modifier.semantics { contentDescription = enabledLabel }) })
        Column {
            if (SourceCapability.Search in capabilities) SourceSettingsRow(stringResource(R.string.explore_search), !state.busy, onClick = onSearch)
            if (installed != null) SourceSettingsRow(stringResource(R.string.sources_editor_title), !state.busy) { model.beginEditor(id) }
        }
        val savedAccount = state.storedSettingsAvailable && state.loginStatus != LoginStatus.LoggedOut
        if (settings != null && (settings.loginDeclared || settings.loginErrorField != null || savedAccount || state.verification != null)) {
            SectionHeader(Modifier.padding(top = 12.dp), text = stringResource(if (settings.loginDeclared || settings.loginErrorField != null || savedAccount)
                R.string.sources_account_section else R.string.sources_verification_section))
            if (settings.loginErrorField != null) Text(stringResource(R.string.sources_login_definition_error, settings.loginErrorField), color = MaterialTheme.colorScheme.error)
            if (settings.loginErrorField == null || savedAccount || state.verification != null) {
                SourceAccountSection(state.loginStatus.takeIf { state.storedSettingsAvailable }, state.accountName,
                    entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready),
                    settings.loginErrorField == null && settings.loginDeclared && SourceCapability.Login in capabilities,
                    state.busy, state.verification,
                    onLogin = { model.beginLogin(id) }, onRelogin = { model.relogin(id) }, onLogout = { model.logout(id) },
                    onRetry = { model.select(id) }, onVerify = model::verifyPending)
            }
        }
        SectionHeader(Modifier.padding(top = 12.dp), text = stringResource(R.string.sources_network_section))
        val denied = installed?.deniedOrigins ?: zLibrary?.denied.orEmpty()
        Column {
            state.network?.let { SourceNetworkSection(it, state.busy, model::setBypassVpn, model::revokeCertificate) }
            if (grantedPermissions != null) SourceSettingsRow(stringResource(R.string.sources_permissions_title), !state.busy, editPermissions,
                denied.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.sources_denied_count, it.size) }) {
                editPermissions = !editPermissions
            }
        }
        if (editPermissions) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            denied.forEach { request ->
                SourcePermissionCandidate(request.origin, request.kind.name, permissions, state.busy) { permissions = it }
            }
            SourcePermissionsField(permissions, state.busy) { permissions = it; model.clearConfigurationError() }
            SourceSaveActions(state.configurationError, state.busy, R.string.sources_save_permissions,
                onSave = {
                    if (zLibrary != null) model.saveZLibrary(zLibrary.settings.origin, permissions)
                    else model.savePermissions(id, permissions)
                })
        }
        if (settings?.configurationDeclared == true || hasVariable || zLibrary != null) {
            SectionHeader(Modifier.padding(top = 12.dp), text = stringResource(R.string.sources_configuration))
            Column {
                if (settings?.configurationDeclared == true && settings.loginErrorField == null) {
                    SourceSettingsRow(stringResource(R.string.sources_open_panel), !state.busy &&
                        entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready) && SourceCapability.Login in capabilities) {
                        model.beginConfiguration(id)
                    }
                }
                if (hasVariable) SourceSettingsRow(stringResource(R.string.sources_edit_variable),
                    !state.busy && state.storedSettingsAvailable, editVariable) { editVariable = !editVariable }
                if (zLibrary != null) {
                    SourceSettingsRow(stringResource(R.string.zlibrary_mirror), !state.busy, editMirror, zLibrary.settings.origin) { editMirror = !editMirror }
                    if (editMirror) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(mirror, { mirror = it; model.clearConfigurationError() }, Modifier.fillMaxWidth(), enabled = !state.busy,
                            singleLine = true, label = { Text(stringResource(R.string.zlibrary_mirror)) })
                        ZLibrarySources.MIRRORS.forEach { candidate ->
                            TextButton(onClick = { mirror = candidate }, enabled = !state.busy) { Text(candidate) }
                        }
                        SourceSaveActions(state.configurationError, state.busy, R.string.zlibrary_save,
                            onSave = { model.saveZLibrary(mirror, permissions) })
                    }
                }
            }
        }
        if (hasVariable) variableEditor()
        val canCheckUpdate = installed?.let { model.canCheckUpdate(it.definition) } == true
        if (installed != null && (!hasVariable || canCheckUpdate)) Column {
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SourceSettingsRow(stringResource(R.string.sources_advanced), expanded = showDetails) { showDetails = !showDetails }
            if (showDetails) {
                if (canCheckUpdate) SourceSettingsRow(stringResource(R.string.sources_check_update), !state.busy) { model.checkUpdate(id) }
                // An undeclared variable can still be used by dynamically loaded scripts.
                if (!hasVariable) SourceSettingsRow(stringResource(R.string.sources_edit_variable),
                    !state.busy && state.storedSettingsAvailable, editVariable) { manualVariable = true; editVariable = !editVariable }
                if (!hasVariable) variableEditor()
            }
        }
        if (installed != null) TextButton(onClick = { deleting = true }, enabled = !state.busy) {
            Text(stringResource(R.string.sources_delete), color = MaterialTheme.colorScheme.error)
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.sources_delete)) },
        text = { Text(stringResource(R.string.sources_delete_help)) },
        confirmButton = { TextButton(onClick = { deleting = false; model.remove(id) }) { Text(stringResource(R.string.sources_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } })
}
