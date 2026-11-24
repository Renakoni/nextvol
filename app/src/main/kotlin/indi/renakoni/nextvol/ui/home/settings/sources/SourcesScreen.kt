package indi.renakoni.nextvol.ui.home.settings.sources

import android.widget.Toast
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
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import hnovel.imports.AUTO_PROFILE
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceListing
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.book.detail.PixivBlockManager
import indi.renakoni.nextvol.ui.book.detail.PixivBlockingViewModel
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

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
        SourcesScreen(
            state,
            model,
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) },
        ) { nav.popBackStack() }
    }
    composable<Route.Main.Settings.Sources> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        SourceSettingsLifecycle(model, entry, nav)
        val state by model.state.collectAsStateWithLifecycle()
        SourcesScreen(
            state,
            model,
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) },
        ) { nav.popBackStack() }
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
        SourcesScreen(
            state,
            model,
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) },
        ) { nav.popBackStack() }
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

private data class SourcesPage(
    val state: SourceManagementState,
    val adding: Boolean,
    val category: SourceCategory?,
    val listState: LazyListState,
    val managingGroups: Boolean,
    val group: String?,
    val addingGroupSources: Boolean,
) {
    val key
        get() = when {
            state.editor != null -> "editor:${state.selected}"
            state.preview != null -> "preview"
            state.selected != null -> "source:${state.selected}"
            managingGroups && addingGroupSources -> "group-add:$group"
            managingGroups && group != null -> "group-members:$group"
            managingGroups -> "groups"
            adding -> category?.name ?: "add"
            else -> "list"
        }
    val depth
        get() = when {
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
fun SourcesScreen(
    state: SourceManagementState,
    model: SourcesViewModel,
    onSearch: (Identifier) -> Unit = {},
    onBack: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var sourceToast by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(context) { onDispose { sourceToast?.cancel() } }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addTab by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf<SourceCategory?>(null) }
    var managementGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var managementQuery by rememberSaveable { mutableStateOf("") }
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
    val groupFilteredSources = state.addedSources.filter {
        managementGroup == null ||
            (managementGroup == "" && it.preferences.groupIds.isEmpty()) ||
            managementGroup in it.preferences.groupIds
    }
    val trimmedQuery = managementQuery.trim()
    val visibleSources = if (trimmedQuery.isEmpty()) {
        groupFilteredSources
    } else {
        groupFilteredSources.filter { source ->
            source.name.contains(trimmedQuery, ignoreCase = true) ||
                source.host.contains(trimmedQuery, ignoreCase = true)
        }
    }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var url by rememberSaveable { mutableStateOf("") }
    val addStates = rememberSaveableStateHolder()
    val settingsStates = rememberSaveableStateHolder()
    var editorMenu by remember { mutableStateOf(false) }
    var discardEditor by remember { mutableStateOf(false) }
    var findingInEditor by rememberSaveable(state.editor?.installed?.definition?.sourceId) { mutableStateOf(false) }
    val managementListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val listState = if (state.selected == null) {
        managementListState
    } else {
        rememberSaveable(state.selected, saver = LazyListState.Saver) { LazyListState() }
    }
    var observedGroupRevision by rememberSaveable { mutableLongStateOf(state.groupRevision) }
    LaunchedEffect(state.groupRevision) {
        if (observedGroupRevision != state.groupRevision) {
            observedGroupRevision = state.groupRevision
            groupingSources = null
            selectedSources = emptyList()
            selecting = false
            editingGroup = null
            deletingGroup = null
            addingGroupSources = false
            selectingGroupSources = false
            selectedGroupSources = emptyList()
            snackbar.showSnackbar(groupsSavedMessage)
        }
    }
    val toastMessage = state.message?.takeIf {
        state.configurationPanel ||
            it == R.string.sources_saved ||
            it == R.string.sources_up_to_date ||
            it == R.string.sources_pixiv_update_unsupported ||
            it == R.string.sources_pixiv_update_failed
    }
    val toastText = toastMessage?.let { stringResource(it) }
    val inlineMessage = state.message?.takeUnless { it == toastMessage }
    LaunchedEffect(toastMessage, toastText) {
        val message = toastMessage ?: return@LaunchedEffect
        if (message == R.string.sources_saved) {
            adding = false
            category = null
            chosen = emptyList()
            model.consumeSavedMessage()
        } else {
            model.consumeMessage(message)
        }
        sourceToast?.cancel()
        sourceToast = textToast(context.applicationContext, toastText, Toast.LENGTH_SHORT).also { it.show() }
    }
    LaunchedEffect(state.installed, state.nativeSources) {
        if (managementGroup == "" && state.addedSources.none { it.preferences.groupIds.isEmpty() }) managementGroup = null
        chosen = chosen - state.installed.map { it.definition.importKey }.toSet()
        selectedSources = selectedSources.filter { selected -> state.addedSources.any { it.key == selected } }
        selectedGroupSources = selectedGroupSources.filter { selected ->
            state.addedSources.any {
                it.key == selected && (openedGroup in it.preferences.groupIds) != addingGroupSources
            }
        }
    }
    LaunchedEffect(state.groups) {
        if (!managementGroup.isNullOrEmpty() && state.groups.none { it.id == managementGroup }) managementGroup = null
        if (!openedGroup.isNullOrEmpty() && state.groups.none { it.id == openedGroup }) {
            openedGroup = null
            addingGroupSources = false
            selectingGroupSources = false
            selectedGroupSources = emptyList()
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> model.previewFile(uri, AUTO_PROFILE) }
    }
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
            addingGroupSources -> {
                addingGroupSources = false
                selectedGroupSources = emptyList()
            }
            managingGroups && selectingGroupSources -> {
                selectingGroupSources = false
                selectedGroupSources = emptyList()
            }
            managingGroups && openedGroup != null -> openedGroup = null
            managingGroups -> managingGroups = false
            selecting -> {
                selecting = false
                selectedSources = emptyList()
            }
            else -> onBack()
        }
    }
    BackHandler { back() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            val title = when {
                state.editor != null -> stringResource(R.string.sources_editor_title)
                state.preview != null -> stringResource(R.string.sources_preview)
                selectedSource != null -> selectedSource.name
                adding && category != null -> stringResource(category!!.title)
                adding -> stringResource(R.string.sources_add)
                managingGroups && addingGroupSources -> stringResource(R.string.source_group_add_title, openedGroupName)
                managingGroups && openedGroup != null -> openedGroupName
                managingGroups -> stringResource(R.string.source_groups_manage)
                else -> stringResource(R.string.sources_title)
            }
            EditorialTopBar(
                title = title,
                hazeState = null,
                frosted = false,
                showBottomDivider = state.editor != null,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = { back() },
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
                actions = {
                    if (state.editor != null) {
                        EditorialIconButton(
                            onClick = { findingInEditor = !findingInEditor },
                            enabled = !state.busy,
                            painter = painterResource(R.drawable.find_replace_24px),
                            contentDescription = stringResource(R.string.sources_editor_find_replace),
                            containerColor = if (findingInEditor) tokens.activePillFill else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
                            contentColor = if (findingInEditor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Box {
                            EditorialIconButton(
                                onClick = { editorMenu = true },
                                enabled = !state.busy,
                                painter = painterResource(R.drawable.more_vert_24px),
                                contentDescription = stringResource(R.string.sources_editor_tools),
                            )
                            EditorialPopupMenu(
                                expanded = editorMenu,
                                onDismissRequest = { editorMenu = false },
                            ) {
                                EditorialPopupMenuHeader(text = stringResource(R.string.sources_editor_tools))
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.sources_editor_validate),
                                    leadingIcon = painterResource(R.drawable.check_24px),
                                    onClick = {
                                        editorMenu = false
                                        model.validateEditor()
                                    },
                                )
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.sources_editor_format),
                                    leadingIcon = painterResource(R.drawable.text_snippet_24px),
                                    onClick = {
                                        editorMenu = false
                                        model.formatEditor()
                                    },
                                )
                            }
                        }
                        Button(
                            onClick = model::saveEditor,
                            enabled = !state.busy,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(stringResource(R.string.sources_editor_save))
                        }
                    }
                    if (managingGroups && openedGroup != null && !addingGroupSources && !selectingGroupSources && state.selected == null) {
                        Box {
                            EditorialIconButton(
                                onClick = { groupMenu = true },
                                enabled = !state.busy,
                                painter = painterResource(R.drawable.more_vert_24px),
                                contentDescription = stringResource(R.string.source_group_options),
                            )
                            EditorialPopupMenu(
                                expanded = groupMenu,
                                onDismissRequest = { groupMenu = false },
                            ) {
                                EditorialPopupMenuHeader(text = stringResource(R.string.source_group_options))
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.source_group_rename),
                                    leadingIcon = painterResource(R.drawable.edit_square_24px),
                                    onClick = {
                                        groupMenu = false
                                        editingGroup = openedGroup
                                    },
                                )
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.source_group_delete),
                                    leadingIcon = painterResource(R.drawable.delete_forever_24px),
                                    destructive = true,
                                    onClick = {
                                        groupMenu = false
                                        deletingGroup = openedGroup
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (managingGroups && openedGroup != null && (addingGroupSources || selectingGroupSources)) {
                SourceSelectionBar(
                    summary = stringResource(R.string.source_group_selected, selectedGroupSources.size),
                    secondary = stringResource(android.R.string.cancel),
                    onSecondary = {
                        selectedGroupSources = emptyList()
                        addingGroupSources = false
                        selectingGroupSources = false
                    },
                    action = stringResource(if (addingGroupSources) R.string.source_group_add else R.string.source_group_remove),
                    onAction = {
                        model.updateGroups(
                            state.addedSources.filter { it.key in selectedGroupSources }.map { it.id }.toSet(),
                            if (addingGroupSources) setOfNotNull(openedGroup) else emptySet(),
                            if (addingGroupSources) emptySet() else setOfNotNull(openedGroup),
                        )
                    },
                    actionEnabled = selectedGroupSources.isNotEmpty() && !state.busy,
                    secondaryEnabled = !state.busy,
                    applyNavigationBarPadding = true,
                )
            } else if (selecting && !managingGroups && state.selected == null && !adding && state.preview == null) {
                SourceSelectionBar(
                    summary = stringResource(R.string.source_group_selected, selectedSources.size),
                    secondary = stringResource(android.R.string.cancel),
                    onSecondary = {
                        selecting = false
                        selectedSources = emptyList()
                    },
                    action = stringResource(R.string.source_group_move),
                    onAction = { groupingSources = selectedSources },
                    actionEnabled = selectedSources.isNotEmpty() && !state.busy,
                    secondaryEnabled = !state.busy,
                    applyNavigationBarPadding = true,
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = SourcesPage(state, adding, category, listState, managingGroups, openedGroup, addingGroupSources),
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.key },
            label = "source-settings-page",
            transitionSpec = {
                val direction = if (targetState.depth >= initialState.depth) 1 else -1
                (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 12 * direction }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 12 * direction })
            },
        ) { page ->
            val state = page.state
            if (state.editor != null) {
                SourceEditorScreen(
                    draft = state.editor,
                    busy = state.busy,
                    model = model,
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding),
                    finding = findingInEditor,
                    onCloseFind = { findingInEditor = false },
                )
            } else if (state.preview != null) {
                SourceImportPreview(state, model, Modifier.padding(padding))
            } else if (page.adding && state.selected == null) {
                val selectedCategory = page.category
                addStates.SaveableStateProvider(selectedCategory?.name ?: "add") {
                    if (selectedCategory == null) {
                        SourceCatalogAddScreen(
                            state = state,
                            tab = addTab,
                            onTab = { addTab = it },
                            url = url,
                            onUrl = { url = it },
                            onPreviewUrl = { model.previewUrl(url, AUTO_PROFILE) },
                            onFile = { file.launch(arrayOf("*/*")) },
                            onCategory = { category = it },
                            onCancel = model::cancel,
                            modifier = Modifier.padding(padding),
                        )
                    } else {
                        SourceCatalogSelectionScreen(
                            state = state,
                            category = selectedCategory,
                            selected = chosen,
                            onSelection = { chosen = it },
                            onContinue = { model.previewCatalog(chosen.toSet()) },
                            onCancel = model::cancel,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            } else if (page.managingGroups && state.selected == null) {
                groupStates.SaveableStateProvider(page.key) {
                    if (page.group == null) {
                        SourceGroupsScreen(
                            state = state,
                            onOpen = {
                                openedGroup = it
                                selectingGroupSources = false
                                selectedGroupSources = emptyList()
                            },
                            onCreate = { editingGroup = "" },
                            modifier = Modifier.padding(padding),
                        )
                    } else {
                        SourceGroupMembersScreen(
                            state = state,
                            groupId = page.group,
                            adding = page.addingGroupSources,
                            selecting = selectingGroupSources,
                            selected = selectedGroupSources,
                            onSelection = { selectedGroupSources = it },
                            onSelect = { selectingGroupSources = true },
                            onAdd = {
                                selectedGroupSources = emptyList()
                                selectingGroupSources = false
                                addingGroupSources = true
                            },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            } else {
                settingsStates.SaveableStateProvider(page.key) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        state = page.listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.busy && state.showProgress) {
                            item(key = "sources_progress") {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(14.dp),
                                ) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(CircleShape),
                                    )
                                    TextButton(
                                        onClick = model::cancel,
                                        modifier = Modifier.align(Alignment.End),
                                    ) {
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                }
                            }
                        }
                        inlineMessage?.takeUnless { it == R.string.sources_saved || it == R.string.source_groups_saved }?.let { message ->
                            item(key = "sources_inline_msg") {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        text = stringResource(message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (state.selected != null) {
                            item(key = "source-settings") {
                                state.addedSources.find { it.id == state.selected }?.let { source ->
                                    SourceSettings(source, state, model, onSearch = { onSearch(source.id) })
                                }
                            }
                        } else {
                            item(key = "source-management-actions") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SourceManagementAction(
                                        title = stringResource(R.string.sources_add),
                                        icon = R.drawable.library_add_24px,
                                        enabled = !state.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { adding = true }
                                    SourceManagementAction(
                                        title = stringResource(R.string.source_groups_manage),
                                        icon = R.drawable.view_list_24px,
                                        enabled = !state.busy,
                                        modifier = Modifier.weight(1f),
                                    ) { managingGroups = true }
                                }
                            }
                            if (state.addedSources.isNotEmpty()) {
                                item(key = "source-search-bar") {
                                    TextField(
                                        value = managementQuery,
                                        onValueChange = { managementQuery = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(0.75.dp, tokens.cardBorder, CircleShape),
                                        singleLine = true,
                                        enabled = !state.busy,
                                        placeholder = {
                                            Text(
                                                text = stringResource(R.string.source_group_search),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.search_24px),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        trailingIcon = if (managementQuery.isNotEmpty()) {
                                            {
                                                IconButton(onClick = { managementQuery = "" }, enabled = !state.busy) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.close_24px),
                                                        contentDescription = stringResource(R.string.source_group_clear_search),
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        shape = CircleShape,
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = tokens.cardSurface,
                                            unfocusedContainerColor = tokens.cardSurface,
                                            disabledContainerColor = tokens.cardSurface.copy(alpha = 0.6f),
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            disabledIndicatorColor = Color.Transparent,
                                        ),
                                    )
                                }
                            }
                            item(key = "source-user-groups") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 44.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        SectionHeader(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 4.dp),
                                            text = stringResource(R.string.source_catalog_installed, state.addedSources.size),
                                        )
                                        if (selecting) {
                                            val selection = when {
                                                visibleSources.isNotEmpty() && visibleSources.all { it.key in selectedSources } -> ToggleableState.On
                                                visibleSources.any { it.key in selectedSources } -> ToggleableState.Indeterminate
                                                else -> ToggleableState.Off
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .heightIn(min = 44.dp)
                                                    .clip(CircleShape)
                                                    .triStateToggleable(
                                                        state = selection,
                                                        enabled = !state.busy && visibleSources.isNotEmpty(),
                                                        role = Role.Checkbox,
                                                    ) {
                                                        val ids = visibleSources.map { it.key }
                                                        selectedSources = if (selection == ToggleableState.On) {
                                                            selectedSources - ids.toSet()
                                                        } else {
                                                            (selectedSources + ids).distinct()
                                                        }
                                                    }
                                                    .padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                TriStateCheckbox(
                                                    state = selection,
                                                    onClick = null,
                                                    enabled = !state.busy && visibleSources.isNotEmpty(),
                                                )
                                                Text(
                                                    text = stringResource(R.string.source_group_select_all),
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                            }
                                        } else if (state.addedSources.isNotEmpty()) {
                                            TextButton(
                                                onClick = { selecting = true },
                                                enabled = !state.busy && visibleSources.isNotEmpty(),
                                            ) {
                                                Text(stringResource(R.string.source_group_select_members))
                                            }
                                        }
                                    }
                                    SourceGroupFilters(state.groups, state.addedSources, managementGroup) { managementGroup = it }
                                }
                            }
                            if (visibleSources.isEmpty()) {
                                item(key = "sources_empty") {
                                    EmptyPage(
                                        icon = painterResource(
                                            if (trimmedQuery.isNotEmpty()) R.drawable.search_24px else R.drawable.public_24px,
                                        ),
                                        title = stringResource(
                                            if (trimmedQuery.isNotEmpty()) R.string.source_group_no_matches
                                            else R.string.source_catalog_empty,
                                        ),
                                        description = "",
                                    )
                                }
                            }
                            items(visibleSources, key = { it.key }) { source ->
                                val id = source.id
                                val selected = source.key in selectedSources
                                val selectLabel = stringResource(R.string.source_group_select)
                                val rowModifier = Modifier
                                    .fillMaxWidth()
                                    .squircleBackground(
                                        color = if (selecting && selected) tokens.activePillFill else tokens.cardSurface,
                                        cornerRadius = tokens.cardRadius,
                                    )
                                    .squircleBorder(
                                        width = if (selecting && selected) 1.25.dp else 0.75.dp,
                                        color = if (selecting && selected) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                                        cornerRadius = tokens.cardRadius,
                                    )
                                    .squircleClip(tokens.cardRadius)
                                    .then(
                                        if (selecting) {
                                            Modifier.toggleable(
                                                value = selected,
                                                enabled = !state.busy,
                                                role = Role.Checkbox,
                                            ) {
                                                selectedSources = if (selected) selectedSources - source.key
                                                else selectedSources + source.key
                                            }
                                        } else {
                                            Modifier.combinedClickable(
                                                enabled = !state.busy,
                                                onClick = { model.select(id) },
                                                onLongClickLabel = selectLabel,
                                                onLongClick = {
                                                    selecting = true
                                                    selectedSources = listOf(source.key)
                                                },
                                            )
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 14.dp)

                                Row(
                                    modifier = rowModifier,
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (selecting) {
                                        Checkbox(
                                            checked = selected,
                                            onCheckedChange = null,
                                            enabled = !state.busy,
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = source.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.W600,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = source.host,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (!selecting) {
                                        Spacer(Modifier.width(4.dp))
                                        MiuixSwitch(
                                            checked = source.preferences.enabled,
                                            onCheckedChange = { model.setEnabled(id, it) },
                                            enabled = !state.busy,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (discardEditor) {
        AlertDialog(
            onDismissRequest = { discardEditor = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.sources_editor_discard)) },
            text = {
                Text(
                    text = stringResource(R.string.sources_editor_discard_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        discardEditor = false
                        model.closeEditor()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.sources_editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discardEditor = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (groupingSources != null) {
        val members = state.addedSources.filter { it.key in groupingSources.orEmpty() }.map { it.id }.toSet()
        SourceGroupsDialog(
            groups = state.groups,
            installed = state.addedSources,
            busy = state.busy,
            message = state.message,
            revision = state.groupRevision,
            members = groupingSources.orEmpty().toSet(),
            onDismiss = { groupingSources = null },
            onSave = { added, removed -> model.updateGroups(members, added, removed) },
            onCreate = { name, added, removed -> model.createGroup(name, members, added, removed) },
        )
    }
    editingGroup?.let { groupId ->
        SourceGroupNameDialog(
            groups = state.groups,
            group = state.groups.find { it.id == groupId },
            busy = state.busy,
            message = state.message,
            onDismiss = { editingGroup = null },
            onSave = {
                if (groupId.isEmpty()) model.createGroup(it) else model.renameGroup(groupId, it)
            },
        )
    }
    state.groups.find { it.id == deletingGroup }?.let { group ->
        SourceGroupDeleteDialog(
            group = group,
            busy = state.busy,
            message = state.message,
            onDismiss = { deletingGroup = null },
            onDelete = { model.deleteGroup(group.id) },
        )
    }

    var blockingSource by remember { mutableStateOf<Identifier?>(null) }
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
    if (state.configurationPanel) {
        SourceConfigurationSheet(
            form = state.loginForm,
            busy = state.busy,
            onSubmit = { values, action, formId, longClick ->
                configurationScope.launch {
                    if (configurationActionPending) return@launch
                    configurationActionPending = true
                    try {
                        val manager = model.configurationBlockManager(action, formId)
                        val link = model.configurationLink(action, formId)
                        if (manager != null) {
                            val current = model.state.value
                            if (current.configurationPanel && current.loginForm?.id == formId) blockingSource = manager
                        } else if (link == null) {
                            model.submitLogin(values, action, formId, longClick)
                        } else {
                            uriHandler.openUri(link)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        textToast(context, R.string.sources_action_failed, Toast.LENGTH_SHORT).show()
                    } finally {
                        configurationActionPending = false
                    }
                }
            },
            onCancel = model::cancelLogin,
            pageTitle = blockingSource?.takeIf { blockingReady }?.let { stringResource(R.string.pixiv_block_manager) },
            onPageBack = { blockingSource = null },
            pageContent = blockingSource?.takeIf { blockingReady }?.let { id ->
                { PixivBlockManager(id, requireNotNull(blockingModel)) }
            },
        )
    } else {
        state.loginForm?.let { form ->
            SourceLoginDialog(
                form = form,
                busy = state.busy,
                onSubmit = model::submitLogin,
                onCancel = model::cancelLogin,
                message = inlineMessage?.let { stringResource(it) },
            )
        }
    }
}

// Search and login resolve the lazy registration themselves. A failed registration cannot start work.
internal fun SourceListing?.actionCapabilities(): Set<SourceCapability> =
    if (this != null && status in setOf(SourceStatus.Registered, SourceStatus.Ready)) metadata.capabilities else emptySet()
