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
import hnovel.imports.AUTO_PROFILE
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceListing
import indi.renakoni.nextvol.data.web.SourceStatus
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
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
    composable<Route.Main.Settings.Sources> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        SourceSettingsLifecycle(model, entry, nav)
        val state by model.state.collectAsStateWithLifecycle()
        SourcesScreen(state, model,
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
    onSearch: (Identifier) -> Unit = {}, onBack: () -> Unit) {
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
    val visibleSources = state.addedSources.filter {
        managementGroup == null || (managementGroup == "" && it.preferences.groupIds.isEmpty()) || managementGroup in it.preferences.groupIds
    }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var url by rememberSaveable { mutableStateOf("") }
    val addStates = rememberSaveableStateHolder()
    val settingsStates = rememberSaveableStateHolder()
    var editorMenu by remember { mutableStateOf(false) }
    var discardEditor by remember { mutableStateOf(false) }
    var findingInEditor by rememberSaveable(state.editor?.installed?.definition?.sourceId) { mutableStateOf(false) }
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
    LaunchedEffect(state.installed, state.nativeSources) {
        if (managementGroup == "" && state.addedSources.none { it.preferences.groupIds.isEmpty() }) managementGroup = null
        chosen = chosen - state.installed.map { it.definition.importKey }.toSet()
        selectedSources = selectedSources.filter { selected -> state.addedSources.any { it.key == selected } }
        selectedGroupSources = selectedGroupSources.filter { selected -> state.addedSources.any {
            it.key == selected && (openedGroup in it.preferences.groupIds) != addingGroupSources
        } }
    }
    LaunchedEffect(state.groups) {
        if (!managementGroup.isNullOrEmpty() && state.groups.none { it.id == managementGroup }) managementGroup = null
        if (!openedGroup.isNullOrEmpty() && state.groups.none { it.id == openedGroup }) {
            openedGroup = null; addingGroupSources = false; selectingGroupSources = false; selectedGroupSources = emptyList()
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.previewFile(uri, AUTO_PROFILE) } }
    val selectedSource = state.addedSources.find { it.id == state.selected }
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
            selectedSource != null -> selectedSource.name
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
                if (state.editor != null) {
                    IconButton(onClick = { findingInEditor = !findingInEditor }, enabled = !state.busy) {
                        Icon(painterResource(R.drawable.find_replace_24px), stringResource(R.string.sources_editor_find_replace))
                    }
                    Box {
                        IconButton(onClick = { editorMenu = true }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.sources_editor_tools))
                        }
                        DropdownMenu(editorMenu, onDismissRequest = { editorMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.sources_editor_validate)) },
                                onClick = { editorMenu = false; model.validateEditor() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sources_editor_format)) },
                                onClick = { editorMenu = false; model.formatEditor() })
                        }
                    }
                    TextButton(onClick = model::saveEditor, enabled = !state.busy) { Text(stringResource(R.string.sources_editor_save)) }
                }
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
                model.updateGroups(state.addedSources.filter { it.key in selectedGroupSources }.map { it.id }.toSet(),
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
        if (state.editor != null) {
            SourceEditorScreen(state.editor, state.busy, model, Modifier.padding(padding).consumeWindowInsets(padding), findingInEditor,
                onCloseFind = { findingInEditor = false })
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
        } else settingsStates.SaveableStateProvider(page.key) {
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = page.listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy && state.showProgress) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) } }
            inlineMessage?.takeUnless { it == R.string.sources_saved || it == R.string.source_groups_saved }?.let { message ->
                item { Text(stringResource(message), color = MaterialTheme.colorScheme.primary) }
            }
            if (state.selected != null) {
                item(key = "source-settings") {
                    state.addedSources.find { it.id == state.selected }?.let { source ->
                        SourceSettings(source, state, model, onSearch = { onSearch(source.id) })
                    }
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
                item(key = "source-user-groups") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader(modifier = Modifier.weight(1f),
                                text = stringResource(R.string.source_catalog_installed, state.addedSources.size))
                            if (selecting) {
                                val selection = when {
                                    visibleSources.isNotEmpty() && visibleSources.all { it.key in selectedSources } -> ToggleableState.On
                                    visibleSources.any { it.key in selectedSources } -> ToggleableState.Indeterminate
                                    else -> ToggleableState.Off
                                }
                                Row(Modifier.heightIn(min = 48.dp).triStateToggleable(selection,
                                    enabled = !state.busy && visibleSources.isNotEmpty(), role = Role.Checkbox) {
                                    val ids = visibleSources.map { it.key }
                                    selectedSources = if (selection == ToggleableState.On) selectedSources - ids.toSet()
                                        else (selectedSources + ids).distinct()
                                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TriStateCheckbox(selection, onClick = null, enabled = !state.busy && visibleSources.isNotEmpty())
                                    Text(stringResource(R.string.source_group_select_all), style = MaterialTheme.typography.labelLarge)
                                }
                            } else if (state.addedSources.isNotEmpty()) TextButton(onClick = { selecting = true },
                                enabled = !state.busy && visibleSources.isNotEmpty()) {
                                Text(stringResource(R.string.source_group_select_members))
                            }
                        }
                        SourceGroupFilters(state.groups, state.addedSources, managementGroup) { managementGroup = it }
                    }
                }
                if (visibleSources.isEmpty()) item {
                    Text(stringResource(R.string.source_catalog_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(visibleSources, key = { it.key }) { source ->
                    val id = source.id
                    val selected = source.key in selectedSources
                    val selectLabel = stringResource(R.string.source_group_select)
                    ListItem(headlineContent = { Text(source.name,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = if (selecting) ({ Checkbox(selected, onCheckedChange = null, enabled = !state.busy) }) else null,
                        supportingContent = {
                            Text(source.host,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = if (selecting) null else ({ Switch(source.preferences.enabled,
                            { model.setEnabled(id, it) }, enabled = !state.busy) }),
                        modifier = Modifier.clip(MaterialTheme.shapes.large).then(if (selecting)
                            Modifier.toggleable(selected, enabled = !state.busy, role = Role.Checkbox) {
                                selectedSources = if (selected) selectedSources - source.key
                                    else selectedSources + source.key
                            } else Modifier.combinedClickable(enabled = !state.busy, onClick = { model.select(id) },
                                onLongClickLabel = selectLabel, onLongClick = {
                                    selecting = true; selectedSources = listOf(source.key)
                                })),
                        colors = ListItemDefaults.colors(containerColor = if (selecting && selected)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer))
                }
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
        val members = state.addedSources.filter { it.key in groupingSources.orEmpty() }.map { it.id }.toSet()
        SourceGroupsDialog(state.groups, state.addedSources, state.busy, state.message, state.groupRevision, groupingSources.orEmpty().toSet(),
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
    if (state.configurationPanel) SourceConfigurationSheet(state.loginForm, state.busy, { values, action, formId, longClick ->
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
                else if (link == null) model.submitLogin(values, action, formId, longClick) else uriHandler.openUri(link)
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
