package indi.renakoni.nextvol.ui.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
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
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import indi.renakoni.nextvol.utils.isResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.Serializable

@Serializable
object SpeechSettingsRoute

@Serializable
object HttpSpeechSourcesRoute

@Serializable
object SpeechEnginesRoute

@Serializable
data class SpeechEngineEditorRoute(val code: String = "", val type: String = "inner")

@Serializable
data class SpeechEngineVoicesRoute(val code: String, val type: String)

@Serializable
data class OfflineVoiceEditorRoute(val id: String, val copy: Boolean = false, val newVoice: Boolean = false)

@Serializable
data class HttpVoiceEditorRoute(val id: String = "", val copy: Boolean = false, val engineCode: String? = null)

@Serializable
object SpeechPreferencesRoute

@Serializable
object SpeechPlaybackSettingsRoute

@Serializable
object SpeechReplacementsRoute

@Serializable
data class SpeechReplacementEditorRoute(val ruleId: String? = null)

@Serializable
object SpeechReplacementPreviewRoute

@Serializable
object SpeechReplacementsRoute

@Serializable
data class SpeechReplacementEditorRoute(val ruleId: String? = null)

@Serializable
object SpeechReplacementPreviewRoute

@Serializable
data class SpeechPackagePreviewRoute(val uri: String)

fun NavController.navigateToSpeechSettings() = navigate(SpeechSettingsRoute) { launchSingleTop = true }

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
    composable<SpeechSettingsRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechSettingsViewModel>()
        // Keep the lightweight voice catalogs ready across visits to the library.
        hiltViewModel<HttpSpeechSourcesViewModel>()
        hiltViewModel<OfflineSpeechSourcesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.refresh() }
        SpeechSettingsScreen(state, playback, nav::popBackStackIfResumed,
            onSources = { nav.navigate(HttpSpeechSourcesRoute) { launchSingleTop = true } },
            onPreferences = { nav.navigate(SpeechPreferencesRoute) { launchSingleTop = true } },
            onPlaybackPreferences = { nav.navigate(SpeechPlaybackSettingsRoute) { launchSingleTop = true } },
            onEngines = { nav.navigate(SpeechEnginesRoute) { launchSingleTop = true } },
            onReplacements = { nav.navigate(SpeechReplacementsRoute) { launchSingleTop = true } },
            onCommand = viewModel.controller::command,
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer)
    }
    composable<SpeechReplacementsRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementsScreen(state, nav::popBackStackIfResumed,
            onEdit = { nav.navigate(SpeechReplacementEditorRoute(it)) { launchSingleTop = true } },
            onToggle = viewModel::toggle, onMove = viewModel::move, onDelete = viewModel::delete,
            onPreview = { nav.navigate(SpeechReplacementPreviewRoute) { launchSingleTop = true } },
            onSort = viewModel::sort, onToggleGroup = viewModel::toggleGroup)
    }
    dialog<SpeechReplacementEditorRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementEditorScreen(state, entry.toRoute<SpeechReplacementEditorRoute>().ruleId, nav::popBackStackIfResumed,
            onSave = { viewModel.save(it, nav::popBackStackIfResumed) }, onPreview = viewModel::preview,
            onClearPreview = viewModel::clearPreview, onClearSaveError = viewModel::clearSaveError)
    }
    composable<SpeechReplacementPreviewRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementPreviewScreen(state, nav::popBackStackIfResumed,
            onPreview = { viewModel.preview(it) }, onClearPreview = viewModel::clearPreview)
    }
    composable<SpeechPlaybackSettingsRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechPlaybackSettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechPlaybackSettingsScreen(state, viewModel.preferences, nav::popBackStackIfResumed, viewModel::update)
    }
    composable<SpeechReplacementsRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementsScreen(state, nav::popBackStackIfResumed,
            onEdit = { nav.navigate(SpeechReplacementEditorRoute(it)) { launchSingleTop = true } },
            onToggle = viewModel::toggle, onMove = viewModel::move, onDelete = viewModel::delete,
            onPreview = { nav.navigate(SpeechReplacementPreviewRoute) { launchSingleTop = true } },
            onSort = viewModel::sort, onToggleGroup = viewModel::toggleGroup)
    }
    dialog<SpeechReplacementEditorRoute>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementEditorScreen(state, entry.toRoute<SpeechReplacementEditorRoute>().ruleId, nav::popBackStackIfResumed,
            onSave = { viewModel.save(it, nav::popBackStackIfResumed) }, onPreview = viewModel::preview,
            onClearPreview = viewModel::clearPreview, onClearSaveError = viewModel::clearSaveError)
    }
    composable<SpeechReplacementPreviewRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementPreviewScreen(state, nav::popBackStackIfResumed,
            onPreview = { viewModel.preview(it) }, onClearPreview = viewModel::clearPreview)
    }
    composable<SpeechPreferencesRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechSettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.refresh() }
        SpeechPreferencesScreen(state, playback, nav::popBackStackIfResumed,
            viewModel::setRate, viewModel::setPitch,
            onCommand = viewModel.controller::command,
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer,
            onPlaybackSpeed = viewModel::setPlaybackSpeed,
            onHttpVolume = viewModel::setHttpVolume)
    }
    composable<SpeechEnginesRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var adding by remember { mutableStateOf<hnovel.speech.SpeechEngineConfig?>(null) }
        SpeechEnginesScreen(state, nav::popBackStackIfResumed,
            onEdit = { nav.navigate(SpeechEngineEditorRoute(it?.code.orEmpty(), it?.type ?: "http")) },
            onVoices = { nav.navigate(SpeechEngineVoicesRoute(it.code, it.type)) },
            onAddVoice = { if (it.type == "http") nav.navigate(HttpVoiceEditorRoute(engineCode = it.code)) else adding = it },
            onSave = { viewModel.save(it, false) {} }, onOrder = viewModel::order)
        adding?.let { engine -> EngineSpeakerPicker(engine, state, { adding = null }) {
            adding = null; nav.navigate(OfflineVoiceEditorRoute(it.reference.id, copy = true, newVoice = true))
        } }
    }
    composable<SpeechEngineEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val route = entry.toRoute<SpeechEngineEditorRoute>()
        SpeechEngineEditorScreen(state.engines.find { it.code == route.code && it.type == route.type }, route.code.isEmpty(), state,
            nav::popBackStackIfResumed, onSave = { viewModel.save(it, route.code.isEmpty(), nav::popBackStackIfResumed) })
    }
    composable<SpeechEngineVoicesRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val route = entry.toRoute<SpeechEngineVoicesRoute>()
        val engine = state.engines.find { it.code == route.code && it.type == route.type }
        var adding by remember { mutableStateOf(false) }
        LaunchedEffect(route.code) { if (route.type == "inner") viewModel.inspect(route.code) }
        SpeechEngineVoicesScreen(engine, state, nav::popBackStackIfResumed,
            onEdit = { nav.navigate(SpeechEngineEditorRoute(route.code, route.type)) },
            onAdd = { if (route.type == "http") nav.navigate(HttpVoiceEditorRoute(engineCode = route.code)) else adding = true },
            onOffline = { nav.navigate(OfflineVoiceEditorRoute(it.reference.id)) },
            onHttp = { nav.navigate(HttpVoiceEditorRoute(it.definition.id)) })
        if (adding && engine != null) EngineSpeakerPicker(engine, state, { adding = false }) {
            adding = false; nav.navigate(OfflineVoiceEditorRoute(it.reference.id, copy = true, newVoice = true))
        }
    }
    composable<HttpSpeechSourcesRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val settingsEntry = remember(entry) { nav.getBackStackEntry<SpeechSettingsRoute>() }
        val viewModel = hiltViewModel<HttpSpeechSourcesViewModel>(settingsEntry)
        val settingsViewModel = hiltViewModel<SpeechSettingsViewModel>(settingsEntry)
        val offlineViewModel = hiltViewModel<OfflineSpeechSourcesViewModel>(settingsEntry)
        val offline by offlineViewModel.state.collectAsStateWithLifecycle()
        val settings by settingsViewModel.state.collectAsStateWithLifecycle()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val preview = preferences.auditionText.ifBlank { stringResource(R.string.tts_preview_text) }
        var deleting by remember { mutableStateOf<List<InstalledOfflineVoice>>(emptyList()) }
        var deletingHttp by remember { mutableStateOf<List<SavedHttpSpeechSource>>(emptyList()) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.preview(it) { file ->
                nav.navigate(SpeechPackagePreviewRoute(file.toString())) { launchSingleTop = true }
            } }
        }
        val systemSettingsPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { settingsViewModel.refresh() }
        HttpSpeechSourcesScreen(if (offline.error) state.copy(error = R.string.tts_error_storage) else state, nav::popBackStackIfResumed,
            catalogReady = state.loaded && offline.loaded,
            onOfflineGroup = offlineViewModel::openEngine,
            onEngine = { nav.navigate(SpeechEngineEditorRoute(it, "http")) },
            onVoiceEditor = { source, copy -> nav.navigate(HttpVoiceEditorRoute(source.definition.id, copy)) },
            onEnableVoice = { source, enabled -> viewModel.enable(listOf(source), enabled) },
            onImport = { picker.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed",
                "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml", "text/plain", "application/octet-stream")) },
            onSelect = viewModel::select, onDelete = { deletingHttp = listOf(it) }, onSites = viewModel::sites,
            onConfirmImport = viewModel::confirmImport, onDismissImport = viewModel::dismissPreview, onEdit = viewModel::edit,
            onConfigure = viewModel::configure, onPreview = { viewModel.previewVoice(it, preview) },
            playback = playback, onCommand = viewModel.controller::command,
            onRefresh = { offlineViewModel.refreshCatalog(); settingsViewModel.refresh(); viewModel.refresh() },
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer,
            offlineGroups = offline.voices.map { it.configuration?.engine ?: "sherpa" }.distinct()
                .associateWith { offline.engineNames[it] ?: it },
            offlineSettings = { engine, query -> offlineSpeechSources(offline, engine, query, onSelect = offlineViewModel::select,
                onPreview = { offlineViewModel.preview(it, preview) }, onDelete = { deleting = it }, onEnable = offlineViewModel::enable,
                onEdit = { voice, copy -> nav.navigate(OfflineVoiceEditorRoute(voice.reference.id, copy)) }) },
            systemSettings = { query ->
                SystemSpeechSettings(settings.copy(loading = settings.loading || state.busy,
                    voices = settings.voices.filter { it.id.contains(query, true) || it.locale.contains(query, true) }),
                    onEngine = settingsViewModel::selectEngine, onVoice = settingsViewModel::selectVoice,
                    onPreviewVoice = { viewModel.controller.preview(preview, settings.settings.copy(httpSource = null, voice = it, offlineVoice = null)) },
                    onSystemSettings = {
                        try { systemSettingsPicker.launch(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: ActivityNotFoundException) { systemSettingsPicker.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    })
            })
        if (deleting.isNotEmpty() || deletingHttp.isNotEmpty()) {
            val current = deleting.any { it.reference.id == offline.selected?.id } || deletingHttp.any { it.definition.id == state.selected }
            val dismiss = { deleting = emptyList(); deletingHttp = emptyList() }
            AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.tts_remove_source)) },
                text = { Text(stringResource(R.string.tts_voice_delete_confirm, deleting.size + deletingHttp.size) +
                    if (current) "\n\n" + stringResource(R.string.tts_voice_delete_selected) else "") },
                confirmButton = { TextButton(onClick = { offlineViewModel.delete(deleting); viewModel.delete(deletingHttp); dismiss() }) {
                    Text(stringResource(if (current) R.string.tts_voice_delete_system else R.string.confirm))
                } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
        }
    }
    composable<OfflineVoiceEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<OfflineVoiceEditorRoute>()
        val viewModel = hiltViewModel<OfflineVoiceEditorViewModel>()
        val settingsEntry = remember(entry) { nav.getBackStackEntry<SpeechSettingsRoute>() }
        val library = hiltViewModel<OfflineSpeechSourcesViewModel>(settingsEntry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val copyPattern = if (route.copy) stringResource(R.string.tts_voice_copy_suffix) else null
        LaunchedEffect(route.id) { viewModel.load(route.id, copyPattern, library.state.value.voices.find { it.reference.id == route.id }, route.newVoice) }
        OfflineVoiceEditorScreen(state, route.copy, viewModel::edit, onSave = { viewModel.save(route.copy, nav::popBackStackIfResumed) },
            onBack = nav::popBackStackIfResumed,
            showParameters = preferences.showParameters, showExtensions = preferences.showExtensions, newVoice = route.newVoice)
    }
    composable<HttpVoiceEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<HttpVoiceEditorRoute>()
        val viewModel = hiltViewModel<HttpVoiceEditorViewModel>()
        val settingsEntry = remember(entry) { nav.getBackStackEntry<SpeechSettingsRoute>() }
        val library = hiltViewModel<HttpSpeechSourcesViewModel>(settingsEntry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val copyPattern = if (route.copy) stringResource(R.string.tts_voice_copy_suffix) else null
        LaunchedEffect(route.id) { viewModel.load(route.id, copyPattern, library.state.value.sources.find { it.definition.id == route.id }, route.engineCode) }
        OfflineVoiceEditorScreen(state, route.copy, viewModel::edit, onSave = { viewModel.save(route.copy, nav::popBackStackIfResumed) },
            onBack = nav::popBackStackIfResumed, parametersSupported = viewModel.hasParameters,
            showParameters = preferences.showParameters, showExtensions = preferences.showExtensions, newVoice = route.engineCode != null)
    }
    composable<SpeechPackagePreviewRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechPackagePreviewViewModel>()
        val sourceEntry = remember(entry) { nav.getBackStackEntry<SpeechSettingsRoute>() }
        val sourcesViewModel = hiltViewModel<HttpSpeechSourcesViewModel>(sourceEntry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val uri = entry.toRoute<SpeechPackagePreviewRoute>().uri
        LaunchedEffect(uri) { viewModel.read(Uri.parse(uri)) }
        SpeechPackagePreviewScreen(state.preview, state.httpImports, onImport = { sources ->
            sourcesViewModel.previewPackage(sources, Uri.parse(uri))
            nav.popBackStackIfResumed()
        }, onImportEngine = { viewModel.importEngine(it, nav::popBackStackIfResumed) },
            onBack = nav::popBackStackIfResumed, offline = state,
            onInstall = viewModel::install, onCancelInstall = viewModel::cancelInstall)
    }
}

@androidx.compose.runtime.Composable
private fun EngineSpeakerPicker(engine: hnovel.speech.SpeechEngineConfig, state: SpeechEnginesState,
    onDismiss: () -> Unit, onSelect: (InstalledOfflineVoice) -> Unit) {
    val voices = state.offline.filter { (it.configuration?.engine ?: "sherpa") == engine.code }.distinctBy { it.resourceReference }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tts_engine_choose_model)) }, text = {
        androidx.compose.foundation.lazy.LazyColumn {
            if (voices.isEmpty()) item { Text(stringResource(R.string.tts_engine_import_model)) }
            items(voices.size) { index -> TextButton(onClick = { onSelect(voices[index]) }) { Text(voices[index].name) } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
