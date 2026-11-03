package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.BackgroundMusicTrack
import kotlin.math.roundToInt

@Composable
internal fun BackgroundMusicDialog(onDismiss: () -> Unit) {
    val viewModel = hiltViewModel<BackgroundMusicViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var replacingId by rememberSaveable { mutableStateOf<String?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importTracks(uris)
    }
    val replace = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTracks(listOf(uri), replacingId)
        replacingId = null
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackgroundMusicSheet(state, onDismiss, viewModel::select, viewModel::volume,
        onImport = { importPicker.launch(arrayOf("audio/*")) }, onDelete = viewModel::delete,
        onReplace = { replacingId = it; replace.launch(arrayOf("audio/*")) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundMusicSheet(state: BackgroundMusicUiState, onDismiss: () -> Unit,
    onSelect: (String, Boolean) -> Unit, onVolume: (Float) -> Unit, onImport: () -> Unit,
    onDelete: (String) -> Unit, onReplace: (String) -> Unit) {
    val enabled = !state.loading && !state.busy
    var volume by remember(state.settings.volume) { mutableFloatStateOf(state.settings.volume) }
    val volumeLabel = stringResource(R.string.tts_music_volume)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
            .testTag("background-music-sheet"), contentPadding = PaddingValues(bottom = 64.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.music_note_24px), null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.tts_background_music), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.volume_up_24px), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(volume, { volume = it }, enabled = enabled, onValueChangeFinished = { onVolume(volume) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel }.testTag("background-music-volume"),
                        thumb = { Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {} },
                        track = { SliderDefaults.Track(it, Modifier.height(4.dp), enabled = enabled, thumbTrackGapSize = 0.dp,
                            trackInsideCornerSize = 2.dp, drawStopIndicator = null) })
                    Text(stringResource(R.string.tts_music_volume_value, (volume * 100).roundToInt()), Modifier.width(40.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tts_music_tracks), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onImport, enabled = enabled, modifier = Modifier.testTag("background-music-import")) {
                        Icon(painterResource(R.drawable.input_24px), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tts_music_import))
                    }
                }
            }
            state.error?.let { message -> item {
                Text(stringResource(message), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            } }
            items(state.settings.tracks, key = { it.id }) { track ->
                BackgroundMusicRow(track, track.id in state.unavailable, enabled,
                    onSelect = { onSelect(track.id, it) }, onDelete = { onDelete(track.id) }, onReplace = { onReplace(track.id) })
            }
        }
    }
}

@Composable
private fun BackgroundMusicRow(track: BackgroundMusicTrack, unavailable: Boolean, enabled: Boolean,
    onSelect: (Boolean) -> Unit, onDelete: () -> Unit, onReplace: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(track.selected, enabled = enabled, role = Role.Checkbox, onValueChange = onSelect)
        .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Checkbox(track.selected, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (unavailable) {
                Text(stringResource(R.string.tts_music_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onReplace, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.tts_music_replace))
                }
            }
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.tts_music_delete, track.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
