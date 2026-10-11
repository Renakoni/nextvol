package indi.renakoni.nextvol.ui.home.settings.sources

import indi.renakoni.nextvol.ui.book.detail.PixivBlockManager
import indi.renakoni.nextvol.ui.book.detail.PixivBlockingViewModel

import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import indi.renakoni.nextvol.R
import hnovel.imports.EXTENSION_PROFILE
import hnovel.imports.AUTO_PROFILE
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceListing
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.data.web.rules.LoginStatus
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

fun NavGraphBuilder.settingsSourcesDestination() {
    composable<Route.Main.Settings.SourceImport> { entry ->
        val route = entry.toRoute<Route.Main.Settings.SourceImport>()
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        SourceSettingsLifecycle(model, entry, nav)
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(model) {
            model.state.first { !it.busy }
            model.openImportLink(route.url)
        }
        SourcesScreen(state, model,
            onDiagnostics = { id -> nav.navigate(Route.Main.Settings.SourceDiagnostic(id.namespace, id.id)) },
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
    composable<Route.Main.Settings.Sources> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        SourceSettingsLifecycle(model, entry, nav)
        val state by model.state.collectAsStateWithLifecycle()
        SourcesScreen(state, model,
            onDiagnostics = { id -> nav.navigate(Route.Main.Settings.SourceDiagnostic(id.namespace, id.id)) },
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
    composable<Route.Main.Settings.SourceDetail> { entry ->
        val route = entry.toRoute<Route.Main.Settings.SourceDetail>()
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        SourceSettingsLifecycle(model, entry, nav)
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(model) {
            model.state.first { !it.busy }
            model.openFromDiscovery(Identifier(route.namespace, route.sourceId), route.login)
        }
        SourcesScreen(state, model,
            onDiagnostics = { id -> nav.navigate(Route.Main.Settings.SourceDiagnostic(id.namespace, id.id)) },
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
}

@Composable
private fun SourceSettingsLifecycle(model: SourcesViewModel, entry: NavBackStackEntry, nav: NavController) {
    LifecycleStartEffect(model, entry) {
        model.setActive(true)
        onStopOrDispose { model.setActive(false, retainBrowser = nav.currentBackStackEntry?.id == entry.id) }
    }
    DisposableEffect(model, entry) {
        onDispose { if (nav.currentBackStackEntry?.id != entry.id) model.setActive(false) }
    }
}

private data class SourcesPage(val state: SourceManagementState, val adding: Boolean, val category: SourceCategory?,
    val listState: LazyListState, val managingGroups: Boolean, val group: String?, val addingGroupSources: Boolean) {
    val key get() = when {
        state.editor != null -> "editor:${state.selected}"
        state.preview != null -> "preview"
        state.selected != null -> "source:${state.selected}"
        managingGroups && addingGroupSources -> "group-add:$group"
        managingGroups && group != null -> "group-members:$group"
        managingGroups -> "groups"
        adding -> category?.name ?: "add"
        else -> "list"
    }
    val depth get() = when {
        state.editor != null -> 2
        state.preview != null -> 3
        state.selected != null -> 1
        managingGroups && addingGroupSources -> 3
        managingGroups && group != null -> 2
        managingGroups -> 1
        adding && category != null -> 2
        adding -> 1
        else -> 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(state: SourceManagementState, model: SourcesViewModel,
    onDiagnostics: (Identifier) -> Unit, onSearch: (Identifier) -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var sourceToast by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(context) { onDispose { sourceToast?.cancel() } }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addTab by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf<SourceCategory?>(null) }
    var managementGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var managingGroups by rememberSaveable { mutableStateOf(false) }
    var openedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var addingGroupSources by rememberSaveable { mutableStateOf(false) }
    var selectingGroupSources by rememberSaveable { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var selectedGroupSources by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var editingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val openedGroupName = state.groups.find { it.id == openedGroup }?.name ?: stringResource(R.string.source_groups_manage)
    val groupStates = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val groupsSavedMessage = stringResource(R.string.source_groups_saved)
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedSources by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var groupingSources by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val visibleSources = state.installed.filter {
        managementGroup == null || (managementGroup == "" && it.preferences.groupIds.isEmpty()) || managementGroup in it.preferences.groupIds
    }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var url by rememberSaveable { mutableStateOf("") }
    val addStates = rememberSaveableStateHolder()
    var deleting by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf(false) }
    var discardEditor by remember { mutableStateOf(false) }
    val managementListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val listState = if (state.selected == null) managementListState
        else rememberSaveable(state.selected, saver = LazyListState.Saver) { LazyListState() }
    var observedGroupRevision by rememberSaveable { mutableLongStateOf(state.groupRevision) }
    LaunchedEffect(state.groupRevision) {
        if (observedGroupRevision != state.groupRevision) {
            observedGroupRevision = state.groupRevision
            groupingSources = null; selectedSources = emptyList(); selecting = false
            editingGroup = null; deletingGroup = null
            addingGroupSources = false; selectingGroupSources = false; selectedGroupSources = emptyList()
            snackbar.showSnackbar(groupsSavedMessage)
        }
    }
    val toastMessage = state.message?.takeIf { state.configurationPanel || it == R.string.sources_saved || it == R.string.sources_up_to_date ||
        it == R.string.sources_pixiv_update_unsupported || it == R.string.sources_pixiv_update_failed }
    val toastText = toastMessage?.let { stringResource(it) }
    val inlineMessage = state.message?.takeUnless { it == toastMessage }
    LaunchedEffect(toastMessage, toastText) {
        val message = toastMessage ?: return@LaunchedEffect
        if (message == R.string.sources_saved) {
            adding = false; category = null; chosen = emptyList()
            model.consumeSavedMessage()
        } else model.consumeMessage(message)
        sourceToast?.cancel()
        sourceToast = textToast(context.applicationContext, toastText, Toast.LENGTH_SHORT).also { it.show() }
    }
    LaunchedEffect(state.installed) {
        if (managementGroup == "" && state.installed.none { it.preferences.groupIds.isEmpty() }) managementGroup = null
        chosen = chosen - state.installed.map { it.definition.importKey }.toSet()
        selectedSources = selectedSources.filter { selected -> state.installed.any { it.definition.sourceId == selected } }
        selectedGroupSources = selectedGroupSources.filter { selected -> state.installed.any {
            it.definition.sourceId == selected && (openedGroup in it.preferences.groupIds) != addingGroupSources
        } }
    }
    LaunchedEffect(state.groups) {
        if (!managementGroup.isNullOrEmpty() && state.groups.none { it.id == managementGroup }) managementGroup = null
        if (!openedGroup.isNullOrEmpty() && state.groups.none { it.id == openedGroup }) {
            openedGroup = null; addingGroupSources = false; selectingGroupSources = false; selectedGroupSources = emptyList()
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.previewFile(uri, AUTO_PROFILE) } }
    val installed = state.installed.find { ImportedRuleSources.id(it.definition) == state.selected }
    val selectedEntry = state.registry.find { it.metadata.id == state.selected }
    fun back() {
        when {
            state.loginForm != null -> model.cancelLogin()
            state.busy -> model.cancel()
            state.editor != null -> if (state.editor.dirty) discardEditor = true else model.closeEditor()
            state.preview != null -> model.dismissPreview()
            state.selected != null -> model.select(null)
            adding && category != null -> category = null
            adding -> adding = false
            addingGroupSources -> { addingGroupSources = false; selectedGroupSources = emptyList() }
            managingGroups && selectingGroupSources -> { selectingGroupSources = false; selectedGroupSources = emptyList() }
            managingGroups && openedGroup != null -> openedGroup = null
            managingGroups -> managingGroups = false
            selecting -> { selecting = false; selectedSources = emptyList() }
            else -> onBack()
        }
    }
    BackHandler { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(when {
            state.editor != null -> stringResource(R.string.sources_editor_title)
            state.preview != null -> stringResource(R.string.sources_preview)
            state.selected == ZLibrarySources.ID -> "Z-Library"
            installed != null -> state.catalog.find { it.key == installed.definition.importKey }?.name ?: installed.definition.displayName
            selectedEntry != null -> selectedEntry.metadata.item.name
            adding && category != null -> stringResource(category!!.title)
            adding -> stringResource(R.string.sources_add)
            managingGroups && addingGroupSources -> stringResource(R.string.source_group_add_title, openedGroupName)
            managingGroups && openedGroup != null -> openedGroupName
            managingGroups -> stringResource(R.string.source_groups_manage)
            else -> stringResource(R.string.sources_title)
        }, style = MaterialTheme.typography.displayLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { back() }) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            } }, actions = {
                if (managingGroups && openedGroup != null && !addingGroupSources && !selectingGroupSources && state.selected == null) {
                    Box {
                        IconButton(onClick = { groupMenu = true }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.source_group_options))
                        }
                        DropdownMenu(groupMenu, onDismissRequest = { groupMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.source_group_rename)) },
                                leadingIcon = { Icon(painterResource(R.drawable.edit_square_24px), null) },
                                onClick = { groupMenu = false; editingGroup = openedGroup })
                            DropdownMenuItem(text = { Text(stringResource(R.string.source_group_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(painterResource(R.drawable.delete_forever_24px), null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { groupMenu = false; deletingGroup = openedGroup })
                        }
                    }
                }
            })
    }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (managingGroups && openedGroup != null && (addingGroupSources || selectingGroupSources)) SourceSelectionBar(
            summary = stringResource(R.string.source_group_selected, selectedGroupSources.size),
            secondary = stringResource(android.R.string.cancel),
            onSecondary = { selectedGroupSources = emptyList(); addingGroupSources = false; selectingGroupSources = false },
            action = stringResource(if (addingGroupSources) R.string.source_group_add else R.string.source_group_remove),
            onAction = {
                model.updateGroups(selectedGroupSources.map { Identifier("rules", it) }.toSet(),
                    if (addingGroupSources) setOfNotNull(openedGroup) else emptySet(),
                    if (addingGroupSources) emptySet() else setOfNotNull(openedGroup))
            }, actionEnabled = selectedGroupSources.isNotEmpty() && !state.busy, secondaryEnabled = !state.busy)
        else if (selecting && !managingGroups && state.selected == null && !adding && state.preview == null) SourceSelectionBar(
            summary = stringResource(R.string.source_group_selected, selectedSources.size),
            secondary = stringResource(android.R.string.cancel),
            onSecondary = { selecting = false; selectedSources = emptyList() },
            action = stringResource(R.string.source_group_move),
            onAction = { groupingSources = selectedSources }, actionEnabled = selectedSources.isNotEmpty() && !state.busy,
            secondaryEnabled = !state.busy)
    }) { padding -> AnimatedContent(targetState = SourcesPage(state, adding, category, listState, managingGroups, openedGroup, addingGroupSources),
            modifier = Modifier.fillMaxSize(), contentKey = { it.key }, label = "source-settings-page",
            transitionSpec = {
                val direction = if (targetState.depth >= initialState.depth) 1 else -1
                (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 12 * direction }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 12 * direction })
            }) { page ->
        val state = page.state
        val installed = state.installed.find { ImportedRuleSources.id(it.definition) == state.selected }
        val selectedEntry = state.registry.find { it.metadata.id == state.selected }
        if (state.editor != null) {
            SourceEditorScreen(state.editor, state.busy, model, Modifier.padding(padding))
        } else if (state.preview != null) {
            SourceImportPreview(state, model, Modifier.padding(padding))
        } else if (page.adding && state.selected == null) {
            val selectedCategory = page.category
            addStates.SaveableStateProvider(selectedCategory?.name ?: "add") {
                if (selectedCategory == null) SourceCatalogAddScreen(state, addTab, { addTab = it }, url, { url = it },
                    onPreviewUrl = { model.previewUrl(url, AUTO_PROFILE) }, onFile = { file.launch(arrayOf("*/*")) },
                    onCategory = { category = it }, onCancel = model::cancel, modifier = Modifier.padding(padding))
                else SourceCatalogSelectionScreen(state, selectedCategory, chosen, { chosen = it },
                    onContinue = { model.previewCatalog(chosen.toSet()) }, onCancel = model::cancel, modifier = Modifier.padding(padding))
            }
        } else if (page.managingGroups && state.selected == null) {
            groupStates.SaveableStateProvider(page.key) {
                if (page.group == null) SourceGroupsScreen(state, onOpen = {
                    openedGroup = it; selectingGroupSources = false; selectedGroupSources = emptyList()
                }, onCreate = { editingGroup = "" }, modifier = Modifier.padding(padding))
                else SourceGroupMembersScreen(state, page.group, page.addingGroupSources, selectingGroupSources, selectedGroupSources,
                    onSelection = { selectedGroupSources = it },
                    onSelect = { selectingGroupSources = true },
                    onAdd = { selectedGroupSources = emptyList(); selectingGroupSources = false; addingGroupSources = true },
                    modifier = Modifier.padding(padding))
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), state = page.listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy && state.showProgress) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) } }
            inlineMessage?.takeUnless { it == R.string.sources_saved || it == R.string.source_groups_saved }?.let { message ->
                item { Text(stringResource(message), color = MaterialTheme.colorScheme.primary) }
            }
            if (state.selected == ZLibrarySources.ID) {
                item(key = "zlibrary-settings") { ZLibrarySettingsEditor(state.zLibrary, state.busy, state.registry.find { it.metadata.id == ZLibrarySources.ID },
                    onEnabled = model::setZLibraryEnabled, onSave = model::saveZLibrary,
                    onSearch = { onSearch(ZLibrarySources.ID) }, network = state.network, onBypassVpn = model::setBypassVpn) }
            } else if (installed != null) {
                item(key = "rule-settings") {
                    val definition = installed.definition
                    val settings = checkNotNull(state.ruleSettings)
                    var configuration by rememberSaveable(definition.sourceId, state.variable) { mutableStateOf(state.variable) }
                    var permissions by rememberSaveable(definition.sourceId, installed.origins) { mutableStateOf(installed.origins.joinToString("\n") { it.origin }) }
                    var showDetails by rememberSaveable(definition.sourceId) { mutableStateOf(false) }
                    var editVariable by rememberSaveable(definition.sourceId) { mutableStateOf(false) }
                    val entry = state.registry.find { it.metadata.id == state.selected }
                    val capabilities = entry.actionCapabilities()
                    val active = entry?.status == SourceStatus.Ready && capabilities.isNotEmpty()
                    val check = state.checks[definition.sourceId]
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(text = stringResource(R.string.sources_basic_section))
                        if (entry?.status == SourceStatus.Failed) Text(stringResource(R.string.sources_action_failed), color = MaterialTheme.colorScheme.error)
                        if (installed.preferences.enabled && installed.origins.isEmpty()) Text(stringResource(R.string.sources_awaiting_permissions))
                        val enabledLabel = stringResource(R.string.sources_enabled)
                        ListItem(headlineContent = { Text(enabledLabel) },
                            trailingContent = { Switch(installed.preferences.enabled,
                                { model.setEnabled(state.selected!!, it) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = enabledLabel }) })
                        if (SourceCapability.Search in capabilities) {
                            Button(onClick = { onSearch(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.explore_search)) }
                        }
                        state.network?.let {
                            SectionHeader(text = stringResource(R.string.sources_network_section))
                            SourceNetworkSection(it, state.busy, model::setBypassVpn, model::revokeCertificate)
                        }
                        val savedAccount = state.storedSettingsAvailable && state.loginStatus != LoginStatus.LoggedOut
                        if (settings.loginDeclared || settings.loginErrorField != null || savedAccount || state.verification != null) {
                            SectionHeader(text = stringResource(if (settings.loginDeclared || settings.loginErrorField != null || savedAccount)
                                R.string.sources_account_section else R.string.sources_verification_section))
                            if (settings.loginErrorField != null) {
                                Text(stringResource(R.string.sources_login_definition_error, settings.loginErrorField), color = MaterialTheme.colorScheme.error)
                            }
                            if (settings.loginErrorField == null || savedAccount || state.verification != null) {
                                SourceAccountSection(state.loginStatus.takeIf { state.storedSettingsAvailable }, state.accountName,
                                    entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready),
                                    settings.loginErrorField == null && settings.loginDeclared && SourceCapability.Login in capabilities,
                                    state.busy, state.verification,
                                    onLogin = { model.beginLogin(state.selected!!) }, onRelogin = { model.relogin(state.selected!!) },
                                    onLogout = { model.logout(state.selected!!) },
                                    onRetry = { model.select(state.selected) }, onVerify = model::verifyPending)
                            }
                        }
                        if (settings.configurationDeclared || settings.variableDescription.isNotBlank()) {
                            SectionHeader(text = stringResource(R.string.sources_configuration))
                            if (settings.configurationDeclared && settings.loginErrorField == null) {
                                OutlinedButton(onClick = { model.beginConfiguration(state.selected!!) }, enabled = !state.busy &&
                                    entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready) && SourceCapability.Login in capabilities) {
                                    Text(stringResource(R.string.sources_open_panel))
                                }
                            }
                            if (settings.variableDescription.isNotBlank()) Text(settings.variableDescription)
                        }
                        TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.sources_advanced)) }
                        if (showDetails) {
                            OutlinedButton(onClick = { model.beginEditor(state.selected!!) }, enabled = !state.busy) {
                                Text(stringResource(R.string.sources_editor_title))
                            }
                            TextButton(onClick = { editVariable = !editVariable }, enabled = !state.busy && state.storedSettingsAvailable) {
                                Text(stringResource(R.string.sources_edit_variable))
                            }
                            if (editVariable) OutlinedTextField(configuration, { configuration = it }, Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.sources_variable)) }, enabled = !state.busy && state.storedSettingsAvailable)
                            SourceCheckStatus(check, check?.revision == definition.contentDigest && check.accountGeneration == entry?.metadata?.accountGeneration, details = true)
                            if (installed.deniedOrigins.isNotEmpty()) {
                                Text(stringResource(R.string.sources_denied_origins), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.sources_origin_candidates_help))
                                installed.deniedOrigins.forEach { request ->
                                    SourcePermissionCandidate(request.origin, request.kind.name, permissions, state.busy) { permissions = it }
                                }
                            }
                            SectionHeader(text = stringResource(R.string.sources_permissions_title))
                            Text(stringResource(R.string.sources_permissions_edit_help), style = MaterialTheme.typography.bodySmall)
                            SourcePermissionsField(permissions, state.busy) { permissions = it; model.clearConfigurationError() }
                            SourceSaveActions(state.configurationError, state.busy,
                                if (editVariable && state.storedSettingsAvailable) R.string.sources_save else R.string.sources_save_permissions,
                                onSave = { model.saveConfiguration(state.selected!!, configuration.takeIf { editVariable && state.storedSettingsAvailable }, permissions) })
                            OutlinedButton(onClick = { model.checkUpdate(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_check_update)) }
                            OutlinedButton(onClick = { onDiagnostics(state.selected!!) }, enabled = !state.busy && active) { Text(stringResource(R.string.sources_diagnostics)) }
                            Text(stringResource(R.string.sources_format, definition.format), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (definition.profile == EXTENSION_PROFILE) R.string.sources_profile_extension else R.string.sources_profile_standard),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.sources_revision, definition.revision.toString()), style = MaterialTheme.typography.bodySmall)
                            if (installed.previous != null) OutlinedButton(onClick = { rollback = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_rollback)) }
                        }
                        TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_delete), color = MaterialTheme.colorScheme.error) }
                    }
                    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.sources_delete)) },
                        text = { Text(stringResource(R.string.sources_delete_help)) }, confirmButton = { TextButton(onClick = { deleting = false; model.remove(state.selected!!) }) { Text(stringResource(R.string.sources_delete)) } },
                        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } })
                    if (rollback) AlertDialog(onDismissRequest = { rollback = false }, title = { Text(stringResource(R.string.sources_rollback)) },
                        text = { Text(stringResource(R.string.sources_rollback_help)) }, confirmButton = { TextButton(onClick = { rollback = false; model.rollback(state.selected!!, permissions) }) { Text(stringResource(R.string.sources_rollback)) } },
                        dismissButton = { TextButton(onClick = { rollback = false }) { Text(stringResource(android.R.string.cancel)) } })
                }
            } else if (state.selected != null) {
                item(key = "registered-settings") {
                    RegisteredSourceSettings(selectedEntry, state.network, state.busy,
                        onSearch = { onSearch(state.selected) }, onBypassVpn = model::setBypassVpn)
                }
            } else {
                item(key = "source-management-actions") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SourceManagementAction(stringResource(R.string.sources_add), R.drawable.library_add_24px,
                            !state.busy, Modifier.weight(1f)) { adding = true }
                        SourceManagementAction(stringResource(R.string.source_groups_manage), R.drawable.view_list_24px,
                            !state.busy, Modifier.weight(1f)) { managingGroups = true }
                    }
                }
                item { SectionHeader(text = stringResource(R.string.sources_builtin_group)) }
                item {
                    val entry = state.registry.find { it.metadata.id == ZLibrarySources.ID }
                    ListItem(headlineContent = { Text("Z-Library") },
                        supportingContent = { Column {
                            Text(stringResource(R.string.zlibrary_source_summary))
                        } },
                        trailingContent = {
                            if (state.zLibrary.settings.available && SourceCapability.Search in entry.actionCapabilities()) IconButton(onClick = { onSearch(ZLibrarySources.ID) }, enabled = !state.busy) {
                                Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                            }
                        }, modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(ZLibrarySources.ID) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                }
                items(state.registry.filter { it.metadata.builtIn && it.metadata.id != ZLibrarySources.ID }, key = { it.metadata.id.toString() }) { entry ->
                    ListItem(headlineContent = { Text(entry.metadata.item.name) }, supportingContent = { Column {
                        Text(stringResource(R.string.sources_builtin))
                    } },
                        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(entry.metadata.id) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        trailingContent = {
                            if (SourceCapability.Search in entry.actionCapabilities()) IconButton(onClick = { onSearch(entry.metadata.id) }, enabled = !state.busy) {
                                Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                            }
                        })
                }
                item(key = "source-user-groups") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader(modifier = Modifier.weight(1f),
                                text = if (state.installed.isEmpty()) stringResource(R.string.sources_imported_group)
                                    else stringResource(R.string.source_catalog_installed, state.installed.size))
                            if (selecting) {
                                val selection = when {
                                    visibleSources.isNotEmpty() && visibleSources.all { it.definition.sourceId in selectedSources } -> ToggleableState.On
                                    visibleSources.any { it.definition.sourceId in selectedSources } -> ToggleableState.Indeterminate
                                    else -> ToggleableState.Off
                                }
                                Row(Modifier.heightIn(min = 48.dp).triStateToggleable(selection,
                                    enabled = !state.busy && visibleSources.isNotEmpty(), role = Role.Checkbox) {
                                    val ids = visibleSources.map { it.definition.sourceId }
                                    selectedSources = if (selection == ToggleableState.On) selectedSources - ids.toSet()
                                        else (selectedSources + ids).distinct()
                                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TriStateCheckbox(selection, onClick = null, enabled = !state.busy && visibleSources.isNotEmpty())
                                    Text(stringResource(R.string.source_group_select_all), style = MaterialTheme.typography.labelLarge)
                                }
                            } else if (state.installed.isNotEmpty()) TextButton(onClick = { selecting = true },
                                enabled = !state.busy && visibleSources.isNotEmpty()) {
                                Text(stringResource(R.string.source_group_select_members))
                            }
                        }
                        SourceGroupFilters(state.groups, state.installed, managementGroup) { managementGroup = it }
                    }
                }
                if (visibleSources.isEmpty()) item {
                    Text(stringResource(R.string.source_catalog_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visibleSources, key = { it.definition.sourceId }) { source ->
                    val id = ImportedRuleSources.id(source.definition)
                    val presentation = state.catalog.find { it.key == source.definition.importKey }
                    val selected = source.definition.sourceId in selectedSources
                    val selectLabel = stringResource(R.string.source_group_select)
                    ListItem(headlineContent = { Text(presentation?.name ?: source.definition.displayName,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = if (selecting) ({ Checkbox(selected, onCheckedChange = null, enabled = !state.busy) }) else null,
                        supportingContent = {
                            Text(presentation?.host?.takeIf(String::isNotBlank)
                                ?: android.net.Uri.parse(source.definition.importKey).host.orEmpty(),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = if (selecting) null else ({ Switch(source.preferences.enabled,
                            { model.setEnabled(id, it) }, enabled = !state.busy) }),
                        modifier = Modifier.clip(MaterialTheme.shapes.large).then(if (selecting)
                            Modifier.toggleable(selected, enabled = !state.busy, role = Role.Checkbox) {
                                selectedSources = if (selected) selectedSources - source.definition.sourceId
                                    else selectedSources + source.definition.sourceId
                            } else Modifier.combinedClickable(enabled = !state.busy, onClick = { model.select(id) },
                                onLongClickLabel = selectLabel, onLongClick = {
                                    selecting = true; selectedSources = listOf(source.definition.sourceId)
                                })),
                        colors = ListItemDefaults.colors(containerColor = if (selecting && selected)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer))
                }
            }
        }
    } }
    if (discardEditor) AlertDialog(onDismissRequest = { discardEditor = false },
        title = { Text(stringResource(R.string.sources_editor_discard)) },
        text = { Text(stringResource(R.string.sources_editor_discard_help)) },
        confirmButton = { TextButton(onClick = { discardEditor = false; model.closeEditor() }) {
            Text(stringResource(R.string.sources_editor_discard))
        } }, dismissButton = { TextButton(onClick = { discardEditor = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (groupingSources != null) {
        val members = groupingSources.orEmpty().map { Identifier("rules", it) }.toSet()
        SourceGroupsDialog(state.groups, state.installed, state.busy, state.message, state.groupRevision, groupingSources.orEmpty().toSet(),
            onDismiss = { groupingSources = null },
            onSave = { added, removed -> model.updateGroups(members, added, removed) },
            onCreate = { name, added, removed -> model.createGroup(name, members, added, removed) })
    }
    editingGroup?.let { groupId ->
        SourceGroupNameDialog(state.groups, state.groups.find { it.id == groupId }, state.busy, state.message,
            onDismiss = { editingGroup = null }, onSave = {
                if (groupId.isEmpty()) model.createGroup(it) else model.renameGroup(groupId, it)
            })
    }
    state.groups.find { it.id == deletingGroup }?.let { group ->
        SourceGroupDeleteDialog(group, state.busy, state.message, onDismiss = { deletingGroup = null },
            onDelete = { model.deleteGroup(group.id) })
    }
    var blockingSource by remember { mutableStateOf<io.nightfish.lightnovelreader.api.identifier.Identifier?>(null) }
    var configurationActionPending by remember { mutableStateOf(false) }
    var blockingReady by remember(blockingSource) { mutableStateOf(false) }
    val blockingModel = blockingSource?.let { id ->
        val manager = hiltViewModel<PixivBlockingViewModel>()
        LaunchedEffect(id) {
            manager.loadManager(id)
            blockingReady = true
        }
        manager
    }
    val configurationScope = rememberCoroutineScope()
    LaunchedEffect(state.configurationPanel, state.loginForm?.id) { blockingSource = null }
    if (state.configurationPanel) SourceConfigurationSheet(state.loginForm, state.busy, { values, action, formId ->
        configurationScope.launch {
            if (configurationActionPending) return@launch
            configurationActionPending = true
            try {
                val manager = model.configurationBlockManager(action, formId)
                val link = model.configurationLink(action, formId)
                if (manager != null) {
                    val current = model.state.value
                    if (current.configurationPanel && current.loginForm?.id == formId) blockingSource = manager
                }
                else if (link == null) model.submitLogin(values, action, formId) else uriHandler.openUri(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                textToast(context, R.string.sources_action_failed, Toast.LENGTH_SHORT).show()
            } finally {
                configurationActionPending = false
            }
        }
        }, model::cancelLogin,
        pageTitle = blockingSource?.takeIf { blockingReady }?.let { stringResource(R.string.pixiv_block_manager) },
        onPageBack = { blockingSource = null },
        pageContent = blockingSource?.takeIf { blockingReady }?.let { id -> { PixivBlockManager(id, requireNotNull(blockingModel)) } })
    else state.loginForm?.let { form ->
        SourceLoginDialog(form, state.busy, model::submitLogin, model::cancelLogin,
            message = inlineMessage?.let { stringResource(it) })
    }
}

// Search and login resolve the lazy registration themselves. A failed registration cannot start work.
internal fun SourceListing?.actionCapabilities(): Set<SourceCapability> =
    if (this != null && status in setOf(SourceStatus.Registered, SourceStatus.Ready)) metadata.capabilities else emptySet()
