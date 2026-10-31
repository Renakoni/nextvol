package indi.renakoni.nextvol.ui.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
object SpeechPreferencesRoute

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
        val state by viewModel.state.collectAsStateWithLifecycle()
        val playback by viewModel.controller.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
        SpeechSettingsScreen(state, playback, nav::popBackStackIfResumed,
            onSources = { nav.navigate(HttpSpeechSourcesRoute()) { launchSingleTop = true } },
            onPreferences = { nav.navigate(SpeechPreferencesRoute) { launchSingleTop = true } },
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
            onPreview = { nav.navigate(SpeechReplacementPreviewRoute) { launchSingleTop = true } })
    }
    composable<SpeechReplacementEditorRoute> { entry ->
        HideReadAloudOverlay()
        val nav = LocalNavController.current
        val viewModel = hiltViewModel<SpeechReplacementsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SpeechReplacementEditorScreen(state, entry.toRoute<SpeechReplacementEditorRoute>().ruleId, nav::popBackStackIfResumed,
            onSave = { viewModel.save(it, nav::popBackStackIfResumed) }, onPreview = viewModel::preview,
            onClearPreview = viewModel::clearPreview)
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
        val preview = stringResource(R.string.tts_preview_text)
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { settingsViewModel.refresh(); offlineViewModel.refresh() }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::preview) }
        val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { nav.navigate(SpeechPackagePreviewRoute(it.toString())) { launchSingleTop = true } }
        }
        HttpSpeechSourcesScreen(state, nav::popBackStackIfResumed,
            initialEditId = entry.toRoute<HttpSpeechSourcesRoute>().editId,
            onInitialEditorClosed = nav::popBackStackIfResumed,
            onEditEngine = viewModel::editEngine,
            onImport = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onSelect = viewModel::select, onDelete = viewModel::delete, onSites = viewModel::sites,
            onConfirmImport = viewModel::confirmImport, onDismissImport = viewModel::dismissPreview, onEdit = viewModel::edit,
            onConfigure = viewModel::configure, onPreview = { viewModel.previewVoice(it, preview) },
            playback = playback, onCommand = viewModel.controller::command,
            onRefresh = settingsViewModel::refresh,
            onSleepTimer = viewModel.controller::setSleepTimer,
            onChapterSleepTimer = viewModel.controller::setChapterSleepTimer,
            onPackagePreview = { packagePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/yaml",
                "application/x-yaml", "text/yaml", "text/x-yaml", "text/plain", "application/octet-stream")) },
            offlineSettings = { OfflineSpeechSources(offline, offlineViewModel::select,
                onPreview = { offlineViewModel.preview(it, preview) }, onDelete = offlineViewModel::delete) },
            systemSettings = {
                SystemSpeechSettings(settings.copy(loading = settings.loading || state.busy),
                    onEngine = settingsViewModel::selectEngine, onVoice = settingsViewModel::selectVoice,
                    onPreviewVoice = { viewModel.controller.preview(preview, settings.settings.copy(httpSource = null, voice = it, offlineVoice = null)) },
                    onSystemSettings = {
                        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    })
            })
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
            sourcesViewModel.previewPackage(sources)
            nav.popBackStackIfResumed()
        }, onBack = nav::popBackStackIfResumed, offline = state,
            onInstall = viewModel::install, onCancelInstall = viewModel::cancelInstall)
    }
}
