package indi.renakoni.nextvol.ui.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.toRoute
import android.net.Uri
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechRequest
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import indi.renakoni.nextvol.utils.isResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.Serializable

@Serializable
data class SpeechSettingsRoute(val bookId: String? = null, val schemeId: String? = null, val followBook: Boolean = true)

@Serializable
data class SpeechSchemesRoute(val bookId: String? = null)

@Serializable
object SpeechDiagnosticsRoute

@Serializable
data class HttpSpeechSourcesRoute(val voiceKey: String? = null, val configureCredentials: Boolean = false, val schemeId: String? = null)

@Serializable
object SpeechEnginesRoute

@Serializable
data class SpeechEngineEditorRoute(val code: String = "", val type: String = "inner")

@Serializable
data class OfflineVoiceEditorRoute(val id: String = "", val copy: Boolean = false, val newVoice: Boolean = false, val engineCode: String? = null)

@Serializable
data class HttpVoiceEditorRoute(val id: String = "", val copy: Boolean = false, val engineCode: String? = null)

@Serializable
object SpeechPreferencesRoute

@Serializable
data class SpeechPlaybackSettingsRoute(val bookId: String? = null, val schemeId: String? = null)

@Serializable
data class SpeechTextAnalysisRoute(val bookId: String? = null, val schemeId: String? = null)

@Serializable
data class SpeechNameTestRoute(val schemeId: String? = null)

@Serializable
data class SpeechReplacementsRoute(val schemeId: String? = null)

@Serializable
data class SpeechReplacementEditorRoute(val ruleId: String? = null, val schemeId: String? = null)

@Serializable
data class SpeechReplacementPreviewRoute(val schemeId: String? = null)

@Serializable
data class SpeechRolesRoute(val schemeId: String? = null)

@Serializable
data class SpeechVoicePoolRoute(val schemeId: String? = null, val bookId: String? = null)

@Serializable
data class SpeechRoleEditorRoute(val ruleId: String? = null, val schemeId: String? = null)

@Serializable
data class SpeechPackagePreviewRoute(val uri: String, val schemeId: String? = null)

fun NavController.navigateToSpeechSettings(bookId: String? = null) {
    navigate(SpeechSettingsRoute(bookId)) { launchSingleTop = true }
}

internal fun NavController.navigateToReadAloudBook(request: SpeechRequest) {
    if (request.isPreview || !isResumed()) return
    navigate(Route.Book.Reader(request.bookId, request.chapterId)) {
        // A reader entry can have advanced beyond its original route's chapter.
        // Replace that entry so the listening chapter is actually opened again.
        if (currentDestination?.hasRoute<Route.Book.Reader>() == true) {
            popUpTo<Route.Book.Reader> { inclusive = true }
        }
        launchSingleTop = true
    }
}

fun NavGraphBuilder.speechSettingsDestination() {
    composable<SpeechSchemesRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val model = hiltViewModel<SpeechSchemesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        SpeechSchemesScreen(state, model.bookId, nav::popBackStackIfResumed,
            onOpen = { nav.navigate(SpeechSettingsRoute(model.bookId, it, followBook = false)) },
            onBind = model::bind, onDefault = model::default, onCreate = model::create,
            onRename = model::rename, onDelete = model::delete)
    }
    composable<SpeechSettingsRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<SpeechSettingsRoute>()
        val bookId = route.bookId
        val schemesModel = hiltViewModel<SpeechSchemesViewModel>()
        val schemes by schemesModel.state.collectAsStateWithLifecycle()
        val viewModel = hiltViewModel<SpeechSettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.refresh() }
        val scheme = route.schemeId?.let { id -> schemes.schemes.items.find { it.id == id } }
        SpeechSettingsScreen(state, playback, nav::popBackStackIfResumed,
            schemeTitle = scheme?.label(),
            schemeUsage = scheme?.let { schemes.usage(it.id) },
            onSources = { nav.navigate(HttpSpeechSourcesRoute(schemeId = state.settings.schemeId)) { launchSingleTop = true } },
            onPreferences = { nav.navigate(SpeechPreferencesRoute) { launchSingleTop = true } },
            onPlaybackPreferences = { nav.navigate(SpeechPlaybackSettingsRoute(bookId, state.settings.schemeId)) { launchSingleTop = true } },
            onEngines = { nav.navigate(SpeechEnginesRoute) { launchSingleTop = true } }.takeIf { route.schemeId == null },
            onReplacements = { nav.navigate(SpeechReplacementsRoute(state.settings.schemeId)) { launchSingleTop = true } },
            onRoles = { nav.navigate(SpeechRolesRoute(state.settings.schemeId)) { launchSingleTop = true } },
            onDiagnostics = { nav.navigate(SpeechDiagnosticsRoute) { launchSingleTop = true } }.takeIf { route.schemeId == null },
            onVoicePool = { nav.navigate(SpeechVoicePoolRoute(state.settings.schemeId, bookId)) { launchSingleTop = true } },
            onCommand = viewModel.controller::command,
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer)
    }
    composable<SpeechDiagnosticsRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechDiagnosticsViewModel>()
        val logs by viewModel.logs.collectAsStateWithLifecycle()
        SpeechDiagnosticsScreen(logs, nav::popBackStackIfResumed,
            viewModel::clearLogs, viewModel::export)
    }
    composable<SpeechVoicePoolRoute> {
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val viewModel = hiltViewModel<SpeechVoicePoolViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SpeechVoicePoolScreen(state, nav::popBackStackIfResumed,
                onEditItem = viewModel::editItem, onEditPair = viewModel::editPair,
                onToggle = viewModel::toggle, onOrder = viewModel::order, onDeleteItems = viewModel::deleteItems, onDeletePairs = viewModel::deletePairs,
                onReassign = viewModel::reassign, onClearPairs = viewModel::clearPairs)
            SpeechVoicePoolEditor(state, viewModel::dismissEditor, viewModel::edit, viewModel::selectEngine, viewModel::selectGroup, viewModel::save)
        }
    }
    composable<SpeechRolesRoute> {
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val viewModel = hiltViewModel<SpeechRolesViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SpeechRolesScreen(state, nav::popBackStackIfResumed,
                onEdit = { id -> viewModel.editRule(id); nav.navigate(SpeechRoleEditorRoute(id, state.settings.schemeId)) },
                onToggle = viewModel::toggle, onOrder = viewModel::order, onDelete = viewModel::delete,
                onRename = viewModel::renameGroup)
        }
    }
    dialog<SpeechRoleEditorRoute>(dialogProperties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) { entry ->
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val owner = remember(entry) { nav.getBackStackEntry<SpeechRolesRoute>() }
            val viewModel = hiltViewModel<SpeechRolesViewModel>(owner)
            val state by viewModel.state.collectAsStateWithLifecycle()
            val route = entry.toRoute<SpeechRoleEditorRoute>()
            LaunchedEffect(state.loading, state.catalogLoading) {
                if (!state.loading && !state.catalogLoading && state.draft == null) viewModel.editRule(route.ruleId)
            }
            SpeechRoleEditorScreen(state, nav::popBackStackIfResumed,
                onSave = { viewModel.save(it, nav::popBackStackIfResumed) }, onEdit = viewModel::edit,
                onEngine = viewModel::selectEngine)
        }
    }
    composable<SpeechReplacementsRoute> { entry ->
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SpeechReplacementsScreen(state, nav::popBackStackIfResumed,
                onEdit = { nav.navigate(SpeechReplacementEditorRoute(it, entry.toRoute<SpeechReplacementsRoute>().schemeId)) { launchSingleTop = true } },
                onToggle = viewModel::toggle, onMove = viewModel::move, onDelete = viewModel::delete,
                onPreview = { nav.navigate(SpeechReplacementPreviewRoute(entry.toRoute<SpeechReplacementsRoute>().schemeId)) { launchSingleTop = true } },
                onSort = viewModel::sort, onToggleGroup = viewModel::toggleGroup)
        }
    }
    dialog<SpeechReplacementEditorRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SpeechReplacementEditorScreen(state, entry.toRoute<SpeechReplacementEditorRoute>().ruleId, nav::popBackStackIfResumed,
                onSave = { viewModel.save(it, nav::popBackStackIfResumed) }, onPreview = viewModel::preview,
                onClearPreview = viewModel::clearPreview, onClearSaveError = viewModel::clearSaveError)
        }
    }
    composable<SpeechReplacementPreviewRoute> {
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SpeechReplacementPreviewScreen(state, nav::popBackStackIfResumed,
                onPreview = { viewModel.preview(it) }, onClearPreview = viewModel::clearPreview)
        }
    }
    composable<SpeechPlaybackSettingsRoute> { entry ->
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val bookId = entry.toRoute<SpeechPlaybackSettingsRoute>().bookId
            val viewModel = hiltViewModel<SpeechPlaybackSettingsViewModel>()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val defaultContext = stringResource(R.string.tts_role_context_default)
            LaunchedEffect(Unit) { viewModel.initializeRoleContext(defaultContext) }
            SpeechPlaybackSettingsScreen(state, viewModel.preferences, nav::popBackStackIfResumed,
                viewModel::update, viewModel::allowMultipleVoices, viewModel::roleContextPattern,
                onNameTest = { nav.navigate(SpeechNameTestRoute(entry.toRoute<SpeechPlaybackSettingsRoute>().schemeId)) { launchSingleTop = true } },
                onTextAnalysis = { nav.navigate(SpeechTextAnalysisRoute(bookId, entry.toRoute<SpeechPlaybackSettingsRoute>().schemeId)) { launchSingleTop = true } },
                onEngineCacheCapacity = viewModel::engineCacheCapacity,
                onAudioSettings = viewModel::audioSettings,
                onBackgroundMusic = viewModel::backgroundMusic,
                onAutoAssign = viewModel::autoAssign, onCacheLimit = viewModel::cacheLimit,
                onDiagnosticLogging = viewModel::diagnosticLogging)
        }
    }
    composable<SpeechNameTestRoute> {
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            SpeechNameTestScreen(nav::popBackStackIfResumed)
        }
    }
    dialog<SpeechTextAnalysisRoute>(dialogProperties = DialogProperties(
        dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            SpeechTextAnalysisDialog(nav::popBackStackIfResumed,
                onOpenStorage = { nav.navigate(Route.StorageManager) })
        }
    }
    composable<SpeechPreferencesRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val owner = remember(entry) { nav.getBackStackEntry<SpeechSettingsRoute>() }
        val route = owner.toRoute<SpeechSettingsRoute>()
        val schemesModel = hiltViewModel<SpeechSchemesViewModel>(owner)
        val schemes by schemesModel.state.collectAsStateWithLifecycle()
        val viewModel = hiltViewModel<SpeechSettingsViewModel>(owner)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val schemeName = state.settings.schemeName.ifBlank { stringResource(R.string.tts_scheme_initial) }
        CompositionLocalProvider(LocalSpeechSchemeName provides schemeName) {
            SpeechPreferencesScreen(state, nav::popBackStackIfResumed,
                viewModel::setRate, viewModel::setVolume, viewModel::setPitch,
                onSchemes = { nav.navigate(SpeechSchemesRoute(route.bookId)) { launchSingleTop = true } }.takeIf { route.schemeId == null },
                schemeSummary = if (route.bookId != null && schemes.schemes.bindings[route.bookId] == null)
                    stringResource(R.string.tts_scheme_follow_name, schemeName) else schemeName)
        }
    }
    composable<SpeechEnginesRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechEnginesScreen(state, nav::popBackStackIfResumed,
            onEdit = { nav.navigate(SpeechEngineEditorRoute(it?.code.orEmpty(), it?.type ?: "http")) },
            onAddVoice = { if (it.isOnline) nav.navigate(HttpVoiceEditorRoute(engineCode = it.code))
                else nav.navigate(OfflineVoiceEditorRoute(engineCode = it.code, newVoice = true)) },
            onOrder = viewModel::order)
    }
    dialog<SpeechEngineEditorRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val route = entry.toRoute<SpeechEngineEditorRoute>()
        SpeechEngineEditorScreen(state.engines.find { it.code == route.code && it.type == route.type }, route.code.isEmpty(), state,
            nav::popBackStackIfResumed, onSave = { viewModel.save(it, route.code.isEmpty(), nav::popBackStackIfResumed) },
            onLogin = viewModel::login)
    }
    composable<HttpSpeechSourcesRoute> { entry ->
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val route = entry.toRoute<HttpSpeechSourcesRoute>()
            val viewModel = hiltViewModel<HttpSpeechSourcesViewModel>()
            val settingsViewModel = hiltViewModel<SpeechSettingsViewModel>()
            val offlineViewModel = hiltViewModel<OfflineSpeechSourcesViewModel>()
            val offline by offlineViewModel.state.collectAsStateWithLifecycle()
            val settings by settingsViewModel.state.collectAsStateWithLifecycle()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val playback by viewModel.controller.state.collectAsStateWithLifecycle()
            val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
            val preview = preferences.auditionText.ifBlank { stringResource(R.string.tts_preview_text) }
            val context = LocalContext.current
            val resources = LocalResources.current
            val systemSpeechLabel = stringResource(R.string.tts_system_speech)
            val toggleVoice: (Boolean, SpeechVoiceRef) -> Unit = { dialogue, voice ->
                settingsViewModel.toggleVoice(dialogue, voice) {
                    Toast.makeText(context, R.string.tts_voice_enable_dialogue, Toast.LENGTH_SHORT).show()
                }
            }
            val httpVoice: (String?) -> SpeechVoiceRef = { id ->
                if (id == null) SpeechVoiceRef(engine = settings.settings.engine, voice = settings.settings.voice,
                    label = systemSpeechLabel)
                else SpeechVoiceRef(httpSource = id, label = state.sources.find { it.definition.id == id }?.definition?.name.orEmpty())
            }
            var deleting by remember { mutableStateOf<List<InstalledOfflineVoice>>(emptyList()) }
            var deletingHttp by remember { mutableStateOf<List<SavedHttpSpeechSource>>(emptyList()) }
            var addingToPool by remember { mutableStateOf<List<SpeechVoiceRef>>(emptyList()) }
            var backgroundMusic by rememberSaveable { mutableStateOf(false) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { viewModel.preview(it) { file ->
                    nav.navigate(SpeechPackagePreviewRoute(file.toString(), settings.settings.schemeId)) { launchSingleTop = true }
                } }
            }
            val systemSettingsPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { settingsViewModel.refresh() }
            HttpSpeechSourcesScreen(if (offline.error || settings.error == SpeechError.Storage)
                state.copy(error = R.string.tts_error_storage) else state, nav::popBackStackIfResumed,
                selection = settings.settings, onDialogue = { toggleVoice(true, httpVoice(it)) },
                focusVoiceKey = route.voiceKey, configureCredentials = route.configureCredentials,
                focusOfflineEngine = offline.voices.find { "offline:${it.reference.id}" == route.voiceKey }
                    ?.let { it.configuration?.engine ?: "sherpa" },
                onSystemGroup = { settingsViewModel.refresh(systemCatalog = true) },
                catalogReady = state.loaded && offline.loaded,
                onOfflineGroup = offlineViewModel::openEngine,
                onVoiceEditor = { source, copy -> nav.navigate(HttpVoiceEditorRoute(source.definition.id, copy)) },
                onEnableVoice = { source, enabled -> viewModel.enable(listOf(source), enabled) },
                onImport = { picker.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed",
                    "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml", "text/plain", "application/octet-stream")) },
                onSelect = { toggleVoice(false, httpVoice(it)) }, onDelete = { deletingHttp = listOf(it) },
                onConfirmImport = viewModel::confirmImport, onDismissImport = viewModel::dismissPreview, onEdit = viewModel::edit,
                onConfigure = viewModel::configure, onPreview = { viewModel.previewVoice(it, preview) },
                playback = playback, onCommand = viewModel.controller::command,
                onRefresh = { offlineViewModel.refreshCatalog(); settingsViewModel.refresh(); viewModel.refreshCatalog() },
                onSleepTimer = viewModel.controller::setSleepTimer,
                onChapterSleepTimer = viewModel.controller::setChapterSleepTimer,
                offlineGroups = offline.voices.map { it.configuration?.engine ?: "sherpa" }.distinct()
                    .associateWith { offline.engineNames[it] ?: it },
                offlineVoices = offline.voices, onOrderHttp = viewModel::order, onOrderOffline = { offlineViewModel.order(it) },
                onDeleteHttp = { deletingHttp = it }, onDeleteOffline = { deleting = it },
                onAddToPool = { addingToPool = it },
                onBackgroundMusic = { backgroundMusic = true },
                offlineRow = { voice, management -> OfflineSpeechSourceRow(voice, offline, selection = settings.settings, management = management,
                    playback = playback,
                    onSelect = { toggleVoice(false, SpeechVoiceRef(offlineVoice = it.reference, label = it.name)) },
                    onDialogue = { toggleVoice(true, SpeechVoiceRef(offlineVoice = it.reference, label = it.name)) },
                    onPreview = { offlineViewModel.preview(it, preview) }, onDelete = { deleting = it }, onEnable = offlineViewModel::enable,
                    onEdit = { voice, copy -> nav.navigate(OfflineVoiceEditorRoute(voice.reference.id, copy)) }) },
                systemSettings = { query ->
                    SystemSpeechSettings(settings.copy(loading = settings.loading || state.busy,
                        voices = settings.voices.filter { it.id.contains(query, true) || it.locale.contains(query, true) }),
                        onEngine = settingsViewModel::selectEngine,
                        onVoice = { toggleVoice(false, SpeechVoiceRef(engine = settings.settings.engine, voice = it, label = it)) },
                        onDialogue = { toggleVoice(true, SpeechVoiceRef(engine = settings.settings.engine, voice = it, label = it)) },
                        playback = playback,
                        onPreviewVoice = { viewModel.controller.previewVoice(preview,
                            settings.settings.copy(httpSource = null, voice = it, offlineVoice = null),
                            it.ifBlank { settings.engines.find { engine -> engine.packageName == settings.settings.engine }?.name ?: systemSpeechLabel }) },
                        onSystemSettings = {
                            try { systemSettingsPicker.launch(Intent("com.android.settings.TTS_SETTINGS")) }
                            catch (_: ActivityNotFoundException) { systemSettingsPicker.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        })
                })
            if (backgroundMusic) BackgroundMusicDialog { backgroundMusic = false }
            if (addingToPool.isNotEmpty()) SpeechAddToPoolDialog(settings.settings, state.busy, state.error,
                onDismiss = { addingToPool = emptyList() }, onAdd = { group ->
                    viewModel.addToPool(group, addingToPool) { count ->
                        addingToPool = emptyList()
                        Toast.makeText(context, resources.getString(R.string.tts_pool_added, count), Toast.LENGTH_SHORT).show()
                    }
                })
            if (deleting.isNotEmpty() || deletingHttp.isNotEmpty()) {
                val dismiss = { deleting = emptyList(); deletingHttp = emptyList() }
                AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.tts_remove_source)) },
                    text = { Text(stringResource(R.string.tts_voice_delete_confirm, deleting.size + deletingHttp.size)) },
                    confirmButton = { TextButton(onClick = {
                        if (deleting.isNotEmpty()) offlineViewModel.delete(deleting) else viewModel.delete(deletingHttp)
                        dismiss()
                    }) {
                        Text(stringResource(R.string.confirm))
                    } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
            }
        }
    }
    dialog<OfflineVoiceEditorRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<OfflineVoiceEditorRoute>()
        val viewModel = hiltViewModel<OfflineVoiceEditorViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val copyPattern = if (route.copy) stringResource(R.string.tts_voice_copy_suffix) else null
        LaunchedEffect(route.id, route.engineCode) { viewModel.load(route.id, copyPattern,
            null, route.newVoice, route.engineCode) }
        VoiceEditorScreen(state, route.copy, viewModel::edit, onSave = {
            viewModel.save(route.copy, nav::popBackStackIfResumed, creating = route.engineCode != null) },
            onBack = nav::popBackStackIfResumed,
            showParameters = preferences.showParameters, showExtensions = preferences.showExtensions, newVoice = route.newVoice)
    }
    dialog<HttpVoiceEditorRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<HttpVoiceEditorRoute>()
        val viewModel = hiltViewModel<HttpVoiceEditorViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val copyPattern = if (route.copy) stringResource(R.string.tts_voice_copy_suffix) else null
        LaunchedEffect(route.id) { viewModel.load(route.id, copyPattern, null, route.engineCode) }
        VoiceEditorScreen(state, route.copy, viewModel::edit, onSave = { viewModel.save(route.copy, nav::popBackStackIfResumed) },
            onBack = nav::popBackStackIfResumed, parametersSupported = viewModel.hasParameters,
            showParameters = preferences.showParameters, showExtensions = preferences.showExtensions, newVoice = route.engineCode != null)
    }
    composable<SpeechPackagePreviewRoute> { entry ->
        SpeechSchemeScope {
            HideReadAloudOverlay()
            val nav = LocalNavController.current
            val viewModel = hiltViewModel<SpeechPackagePreviewViewModel>()
            val sourceEntry = remember(entry) { nav.getBackStackEntry<HttpSpeechSourcesRoute>() }
            val sourcesViewModel = hiltViewModel<HttpSpeechSourcesViewModel>(sourceEntry)
            val state by viewModel.state.collectAsStateWithLifecycle()
            val uri = entry.toRoute<SpeechPackagePreviewRoute>().uri
            LaunchedEffect(uri) { viewModel.read(Uri.parse(uri)) }
            SpeechPackagePreviewScreen(state.preview, state.httpImports, onImport = { sources ->
                sourcesViewModel.previewPackage(sources, Uri.parse(uri))
                nav.popBackStackIfResumed()
            }, onImportEngine = { viewModel.importEngine(it, nav::popBackStackIfResumed) },
                onBack = nav::popBackStackIfResumed, offline = state,
                onInstall = viewModel::install, onCancelInstall = viewModel::cancelInstall,
                onImportConfiguration = viewModel::importConfiguration,
                onImportOfflineConfiguration = viewModel::importOfflineConfiguration)
        }
    }
}
