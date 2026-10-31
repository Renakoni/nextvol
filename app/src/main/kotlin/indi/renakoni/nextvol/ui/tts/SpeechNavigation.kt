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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import android.net.Uri
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechRequest
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.speakerData
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import io.nightfish.lightnovelreader.api.Route
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import indi.renakoni.nextvol.utils.isResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.Serializable

@Serializable
object SpeechSettingsRoute

@Serializable
data class HttpSpeechSourcesRoute(val editId: String? = null)

@Serializable
object SpeechEnginesRoute

@Serializable
data class SpeechEngineEditorRoute(val code: String)

@Serializable
data class OfflineVoiceEditorRoute(val id: String, val copy: Boolean = false)

@Serializable
data class HttpVoiceEditorRoute(val id: String, val copy: Boolean = false)

@Serializable
object SpeechPlaybackSettingsRoute

@Serializable
object SpeechPreferencesRoute

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
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
        SpeechSettingsScreen(state, playback, nav::popBackStackIfResumed,
            onSources = { nav.navigate(HttpSpeechSourcesRoute()) { launchSingleTop = true } },
            onPreferences = { nav.navigate(SpeechPreferencesRoute) { launchSingleTop = true } },
            onPlaybackPreferences = { nav.navigate(SpeechPlaybackSettingsRoute) { launchSingleTop = true } },
            onEngines = { nav.navigate(SpeechEnginesRoute) { launchSingleTop = true } },
            onCommand = viewModel.controller::command,
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer)
    }
    composable<SpeechPlaybackSettingsRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechPlaybackSettingsViewModel>()
        SpeechPlaybackSettingsScreen(viewModel.preferences, nav::popBackStackIfResumed)
    }
    composable<SpeechPreferencesRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechSettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
        SpeechPreferencesScreen(state, playback, nav::popBackStackIfResumed,
            viewModel::setRate, viewModel::setPitch,
            onCommand = viewModel.controller::command,
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer,
            onPlaybackSpeed = viewModel::setPlaybackSpeed)
    }
    composable<SpeechEnginesRoute> {
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
        SpeechEnginesScreen(state, nav::popBackStackIfResumed,
            onEdit = { nav.navigate(SpeechEngineEditorRoute(it)) { launchSingleTop = true } },
            onHttp = { nav.navigate(HttpSpeechSourcesRoute(editId = it)) { launchSingleTop = true } })
    }
    composable<SpeechEngineEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechEnginesViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val code = entry.toRoute<SpeechEngineEditorRoute>().code
        SpeechEngineEditorScreen(state.engines.find { it.code == code }, state, nav::popBackStackIfResumed,
            onSave = { viewModel.save(it, nav::popBackStackIfResumed) })
    }
    composable<HttpSpeechSourcesRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val context = LocalContext.current
        val viewModel = hiltViewModel<HttpSpeechSourcesViewModel>()
        val settingsViewModel = hiltViewModel<SpeechSettingsViewModel>()
        val offlineViewModel = hiltViewModel<OfflineSpeechSourcesViewModel>()
        val offline by offlineViewModel.state.collectAsStateWithLifecycle()
        val settings by settingsViewModel.state.collectAsStateWithLifecycle()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val preview = preferences.auditionText.ifBlank { stringResource(R.string.tts_preview_text) }
        val copyPattern = stringResource(R.string.tts_voice_copy_suffix)
        var query by rememberSaveable { mutableStateOf("") }
        var group by rememberSaveable { mutableStateOf(0) }
        var descending by rememberSaveable { mutableStateOf(false) }
        var selecting by rememberSaveable { mutableStateOf(false) }
        var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
        var deleting by remember { mutableStateOf<List<InstalledOfflineVoice>>(emptyList()) }
        var deletingHttp by remember { mutableStateOf<List<SavedHttpSpeechSource>>(emptyList()) }
        fun choose(id: String) { chosen = if (id in chosen) chosen - id else chosen + id }
        val voices = offline.voices.filter { voice ->
            listOf(voice.name, voice.configuration?.engine.orEmpty(), voice.configuration?.locale.orEmpty(),
                voice.metadata.note, voice.metadata.description).any { it.contains(query, ignoreCase = true) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).let { if (descending) it.reversed() else it }
        val selectedVoices = offline.voices.filter { "offline:${it.reference.id}" in chosen }
        val selectedHttp = state.sources.filter { "http:${it.definition.id}" in chosen }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { settingsViewModel.refresh(); offlineViewModel.refresh(); viewModel.refresh() }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::preview) }
        val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { nav.navigate(SpeechPackagePreviewRoute(it.toString())) { launchSingleTop = true } }
        }
        val http = state.sources.filter { source ->
            val metadata = source.speakerData()
            (listOf(source.definition.name, source.category.orEmpty()) + listOf("locale", "note", "desc").map {
                (metadata[it] as? JsonPrimitive)?.contentOrNull.orEmpty()
            }).any { it.contains(query, true) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.definition.name }).let { if (descending) it.reversed() else it }
        HttpSpeechSourcesScreen(state.copy(sources = http), nav::popBackStackIfResumed,
            libraryControls = { SpeechLibraryControls(query, { query = it }, group, { group = it }, descending, { descending = !descending },
                selecting, selectedVoices.size + selectedHttp.size, { selecting = !selecting; chosen = emptyList() },
                onSelectAll = { chosen = voices.map { "offline:${it.reference.id}" } + http.map { "http:${it.definition.id}" } },
                onEnable = { offlineViewModel.enable(selectedVoices, it); viewModel.enable(selectedHttp, it) },
                onCopy = { offlineViewModel.copy(selectedVoices, copyPattern); viewModel.copy(selectedHttp); chosen = emptyList() },
                onDelete = { deleting = selectedVoices; deletingHttp = selectedHttp.filterNot { it.isBuiltIn } },
                deleteCount = selectedVoices.size + selectedHttp.count { !it.isBuiltIn },
                noResults = query.isNotBlank() && voices.isEmpty() && http.isEmpty() && !state.busy &&
                    settings.voices.none { it.id.contains(query, true) || it.locale.contains(query, true) }) },
            searchActive = query.isNotBlank(),
            selecting = selecting, chosen = selectedHttp.map { it.definition.id }.toSet(), onChoose = { choose("http:$it") }, grouping = group,
            onVoiceEditor = { source, copy -> nav.navigate(HttpVoiceEditorRoute(source.definition.id, copy)) },
            onEnableVoice = { source, enabled -> viewModel.enable(listOf(source), enabled) },
            initialEditId = entry.toRoute<HttpSpeechSourcesRoute>().editId,
            onInitialEditorClosed = nav::popBackStackIfResumed,
            onEditEngine = viewModel::editEngine,
            onImport = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onSelect = viewModel::select, onDelete = { deletingHttp = listOf(it) }, onSites = viewModel::sites,
            onConfirmImport = viewModel::confirmImport, onDismissImport = viewModel::dismissPreview, onEdit = viewModel::edit,
            onConfigure = viewModel::configure, onPreview = { viewModel.previewVoice(it, preview) },
            playback = playback, onCommand = viewModel.controller::command,
            onRefresh = { offlineViewModel.refreshCatalog(); settingsViewModel.refresh(); viewModel.refresh() },
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer,
            onPackagePreview = { packagePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/yaml",
                "application/x-yaml", "text/yaml", "text/x-yaml", "text/plain", "application/octet-stream")) },
            offlineSettings = { offlineSpeechSources(offline, voices, group, selectedVoices.map { it.reference.id }.toSet(), selecting,
                onChoose = { choose("offline:$it") }, onSelect = offlineViewModel::select,
                onPreview = { offlineViewModel.preview(it, preview) }, onDelete = { deleting = it }, onEnable = offlineViewModel::enable,
                onEdit = { voice, copy -> nav.navigate(OfflineVoiceEditorRoute(voice.reference.id, copy)) }) },
            systemSettings = {
                SystemSpeechSettings(settings.copy(loading = settings.loading || state.busy,
                    voices = settings.voices.filter { it.id.contains(query, true) || it.locale.contains(query, true) }),
                    onEngine = settingsViewModel::selectEngine, onVoice = settingsViewModel::selectVoice,
                    onPreviewVoice = { viewModel.controller.preview(preview, settings.settings.copy(httpSource = null, voice = it, offlineVoice = null)) },
                    onSystemSettings = {
                        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    })
            })
        if (deleting.isNotEmpty() || deletingHttp.isNotEmpty()) {
            val current = deleting.any { it.reference.id == offline.selected?.id } || deletingHttp.any { it.definition.id == state.selected }
            val dismiss = { deleting = emptyList(); deletingHttp = emptyList() }
            AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.tts_remove_source)) },
                text = { Text(stringResource(R.string.tts_voice_delete_confirm, deleting.size + deletingHttp.size) +
                    if (current) "\n\n" + stringResource(R.string.tts_voice_delete_selected) else "") },
                confirmButton = { TextButton(onClick = { offlineViewModel.delete(deleting); viewModel.delete(deletingHttp); dismiss(); chosen = emptyList() }) {
                    Text(stringResource(if (current) R.string.tts_voice_delete_system else R.string.confirm))
                } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
        }
    }
    composable<OfflineVoiceEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<OfflineVoiceEditorRoute>()
        val viewModel = hiltViewModel<OfflineVoiceEditorViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val copyPattern = if (route.copy) stringResource(R.string.tts_voice_copy_suffix) else null
        LaunchedEffect(route.id) { viewModel.load(route.id, copyPattern) }
        OfflineVoiceEditorScreen(state, route.copy, viewModel::edit, onSave = { viewModel.save(route.copy, nav::popBackStackIfResumed) },
            onBack = nav::popBackStackIfResumed,
            showParameters = preferences.showParameters, showExtensions = preferences.showExtensions)
    }
    composable<HttpVoiceEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val route = entry.toRoute<HttpVoiceEditorRoute>()
        val viewModel = hiltViewModel<HttpVoiceEditorViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val preferences = hiltViewModel<SpeechPlaybackSettingsViewModel>().preferences
        val copyPattern = if (route.copy) stringResource(R.string.tts_voice_copy_suffix) else null
        LaunchedEffect(route.id) { viewModel.load(route.id, copyPattern) }
        OfflineVoiceEditorScreen(state, route.copy, viewModel::edit, onSave = { viewModel.save(route.copy, nav::popBackStackIfResumed) },
            onBack = nav::popBackStackIfResumed, parametersSupported = viewModel.hasParameters,
            showParameters = preferences.showParameters, showExtensions = preferences.showExtensions)
    }
    composable<SpeechPackagePreviewRoute> { entry ->
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
        }, onBack = nav::popBackStackIfResumed, offline = state,
            onInstall = viewModel::install, onCancelInstall = viewModel::cancelInstall)
    }
}
