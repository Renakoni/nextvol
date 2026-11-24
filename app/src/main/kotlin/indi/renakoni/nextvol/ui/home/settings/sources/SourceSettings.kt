package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.ManagedSource
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.data.web.rules.LoginStatus
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

/** All added sources use the same settings page; backend-specific controls follow their capabilities. */
@Composable
internal fun SourceSettings(
    source: ManagedSource,
    state: SourceManagementState,
    model: SourcesViewModel,
    onSearch: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
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
    var savingMirror by rememberSaveable(source.key) { mutableStateOf(false) }
    var editVariable by rememberSaveable(source.key) { mutableStateOf(false) }
    var manualVariable by rememberSaveable(source.key) { mutableStateOf(false) }
    var deleting by rememberSaveable(source.key) { mutableStateOf(false) }
    val hasVariable = settings != null && !manualVariable && (settings.variableDescription.isNotBlank() || state.variable.isNotBlank())

    val variableEditor: @Composable () -> Unit = {
        if (editVariable && settings != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (settings.variableDescription.isNotBlank()) {
                    Text(
                        text = settings.variableDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = configuration,
                    onValueChange = {
                        configuration = it
                        model.clearVariableError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    label = { Text(stringResource(R.string.sources_variable)) },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !state.busy && state.storedSettingsAvailable,
                )
                SourceSaveActions(
                    error = state.variableError,
                    busy = state.busy || !state.storedSettingsAvailable,
                    label = R.string.sources_save_variable,
                    onSave = { model.saveVariable(id, configuration) },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Basic section
        SectionHeader(
            modifier = Modifier.padding(start = 4.dp),
            text = stringResource(R.string.sources_basic_section),
        )
        if (entry?.status == SourceStatus.Failed) {
            SourceStatusCallout(stringResource(R.string.sources_action_failed), isError = true)
        }
        if (zLibrary?.restorationFailed == true) {
            SourceStatusCallout(stringResource(R.string.zlibrary_settings_failed), isError = true)
        }
        if (source.preferences.enabled && grantedPermissions?.isEmpty() == true) {
            SourceStatusCallout(stringResource(R.string.sources_awaiting_permissions), isError = false)
        }
        EditorialCard(modifier = Modifier.fillMaxWidth()) {
            val enabledLabel = stringResource(R.string.sources_enabled)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = enabledLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.W500,
                )
                MiuixSwitch(
                    checked = source.preferences.enabled,
                    onCheckedChange = { model.setEnabled(id, it) },
                    enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = enabledLabel },
                )
            }
            if (SourceCapability.Search in capabilities) {
                EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                SourceSettingsRow(stringResource(R.string.explore_search), !state.busy, onClick = onSearch)
            }
            if (installed != null) {
                EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                SourceSettingsRow(stringResource(R.string.sources_editor_title), !state.busy) {
                    model.beginEditor(id)
                }
            }
        }

        // Account & Verification section
        val savedAccount = state.storedSettingsAvailable && state.loginStatus != LoginStatus.LoggedOut
        if (settings != null && (settings.loginDeclared || settings.loginErrorField != null || savedAccount || state.verification != null)) {
            SectionHeader(
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                text = stringResource(
                    if (settings.loginDeclared || settings.loginErrorField != null || savedAccount) {
                        R.string.sources_account_section
                    } else {
                        R.string.sources_verification_section
                    },
                ),
            )
            if (settings.loginErrorField != null) {
                SourceStatusCallout(
                    stringResource(R.string.sources_login_definition_error, settings.loginErrorField),
                    isError = true,
                )
            }
            if (settings.loginErrorField == null || savedAccount || state.verification != null) {
                EditorialCard(modifier = Modifier.fillMaxWidth()) {
                    SourceAccountSection(
                        status = state.loginStatus.takeIf { state.storedSettingsAvailable },
                        accountName = state.accountName,
                        available = entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready),
                        loginAvailable = settings.loginErrorField == null && settings.loginDeclared && SourceCapability.Login in capabilities,
                        busy = state.busy,
                        verification = state.verification,
                        onLogin = { model.beginLogin(id) },
                        onRelogin = { model.relogin(id) },
                        onLogout = { model.logout(id) },
                        onRetry = { model.select(id) },
                        onVerify = model::verifyPending,
                    )
                }
            }
        }

        // Network & Permissions section
        SectionHeader(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            text = stringResource(R.string.sources_network_section),
        )
        val denied = installed?.deniedOrigins ?: zLibrary?.denied.orEmpty()
        EditorialCard(modifier = Modifier.fillMaxWidth()) {
            state.network?.let {
                SourceNetworkSection(it, state.busy, model::setBypassVpn, model::revokeCertificate)
            }
            if (grantedPermissions != null) {
                if (state.network != null) {
                    EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                }
                SourceSettingsRow(
                    title = stringResource(R.string.sources_permissions_title),
                    enabled = !state.busy,
                    expanded = editPermissions,
                    supporting = denied.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.sources_denied_count, it.size)
                    },
                ) {
                    editPermissions = !editPermissions
                }
                if (editPermissions) {
                    EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        denied.forEach { request ->
                            SourcePermissionCandidate(request.origin, request.kind.name, permissions, state.busy) {
                                permissions = it
                            }
                        }
                        SourcePermissionsField(permissions, state.busy) {
                            permissions = it
                            model.clearConfigurationError()
                        }
                        SourceSaveActions(
                            error = state.configurationError.takeUnless { savingMirror },
                            busy = state.busy,
                            label = R.string.sources_save_permissions,
                            onSave = {
                                savingMirror = false
                                if (zLibrary != null) model.saveZLibrary(zLibrary.settings.origin, permissions)
                                else model.savePermissions(id, permissions)
                            },
                        )
                    }
                }
            }
        }

        // Configuration section
        if (settings?.configurationDeclared == true || hasVariable || zLibrary != null) {
            SectionHeader(
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                text = stringResource(R.string.sources_configuration),
            )
            EditorialCard(modifier = Modifier.fillMaxWidth()) {
                var hasPreviousRow = false
                if (settings?.configurationDeclared == true && settings.loginErrorField == null) {
                    SourceSettingsRow(
                        title = stringResource(R.string.sources_open_panel),
                        enabled = !state.busy &&
                            entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready) &&
                            SourceCapability.Login in capabilities,
                    ) {
                        model.beginConfiguration(id)
                    }
                    hasPreviousRow = true
                }
                if (hasVariable) {
                    if (hasPreviousRow) EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    SourceSettingsRow(
                        title = stringResource(R.string.sources_edit_variable),
                        enabled = !state.busy && state.storedSettingsAvailable,
                        expanded = editVariable,
                    ) {
                        editVariable = !editVariable
                    }
                    if (editVariable) {
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        variableEditor()
                    }
                    hasPreviousRow = true
                }
                if (zLibrary != null) {
                    if (hasPreviousRow) EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    SourceSettingsRow(
                        title = stringResource(R.string.zlibrary_mirror),
                        enabled = !state.busy,
                        expanded = editMirror,
                        supporting = zLibrary.settings.origin,
                    ) {
                        editMirror = !editMirror
                    }
                    if (editMirror) {
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = mirror,
                                onValueChange = {
                                    mirror = it
                                    model.clearConfigurationError()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                enabled = !state.busy,
                                singleLine = true,
                                label = { Text(stringResource(R.string.zlibrary_mirror)) },
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ZLibrarySources.MIRRORS.forEach { candidate ->
                                    AssistChip(
                                        onClick = {
                                            mirror = candidate
                                            model.clearConfigurationError()
                                        },
                                        enabled = !state.busy,
                                        shape = CircleShape,
                                        label = { Text(candidate, style = MaterialTheme.typography.labelMedium) },
                                    )
                                }
                            }
                            SourceSaveActions(
                                error = state.configurationError.takeIf { savingMirror },
                                busy = state.busy,
                                label = R.string.zlibrary_save,
                                onSave = {
                                    savingMirror = true
                                    model.saveZLibrary(mirror, savedPermissions)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Advanced section
        val canCheckUpdate = installed?.let { model.canCheckUpdate(it.definition) } == true
        if (installed != null && (!hasVariable || canCheckUpdate)) {
            EditorialCard(modifier = Modifier.fillMaxWidth()) {
                SourceSettingsRow(
                    title = stringResource(R.string.sources_advanced),
                    expanded = showDetails,
                ) {
                    showDetails = !showDetails
                }
                if (showDetails) {
                    if (canCheckUpdate) {
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        SourceSettingsRow(stringResource(R.string.sources_check_update), !state.busy) {
                            model.checkUpdate(id)
                        }
                    }
                    // An undeclared variable can still be used by dynamically loaded scripts.
                    if (!hasVariable) {
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        SourceSettingsRow(
                            title = stringResource(R.string.sources_edit_variable),
                            enabled = !state.busy && state.storedSettingsAvailable,
                            expanded = editVariable,
                        ) {
                            manualVariable = true
                            editVariable = !editVariable
                        }
                        if (editVariable) {
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                            variableEditor()
                        }
                    }
                }
            }
        }

        // Delete action
        if (installed != null) {
            OutlinedButton(
                onClick = { deleting = true },
                enabled = !state.busy,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.sources_delete), fontWeight = FontWeight.W600)
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.sources_delete)) },
            text = {
                Text(
                    text = stringResource(R.string.sources_delete_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleting = false
                        model.remove(id)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.sources_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SourceStatusCallout(text: String, isError: Boolean) {
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        containerColor = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        },
        borderColor = if (isError) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
