package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.BackgroundMusicTrack
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
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
    BackgroundMusicSheet(
        state = state,
        onDismiss = onDismiss,
        onSelect = viewModel::select,
        onVolume = viewModel::volume,
        onImport = { importPicker.launch(arrayOf("audio/*")) },
        onDelete = viewModel::delete,
        onReplace = {
            replacingId = it
            replace.launch(arrayOf("audio/*"))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundMusicSheet(
    state: BackgroundMusicUiState,
    onDismiss: () -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onReplace: (String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val enabled = !state.loading && !state.busy
    var volume by remember(state.settings.volume) { mutableFloatStateOf(state.settings.volume) }
    val volumeLabel = stringResource(R.string.tts_music_volume)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = tokens.cardBorder) },
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
                .navigationBarsPadding()
                .testTag("background-music-sheet"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "bgm_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EditorialIconBadge(
                        painter = painterResource(R.drawable.music_note_24px),
                        size = 38.dp,
                        iconSize = 19.dp,
                        cornerRadius = 11.dp,
                    )
                    Text(
                        text = stringResource(R.string.tts_background_music),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600,
                    )
                    EditorialIconButton(
                        onClick = onDismiss,
                        painter = painterResource(R.drawable.close_24px),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }

            item(key = "bgm_volume") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.volume_up_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            enabled = enabled,
                            onValueChangeFinished = { onVolume(volume) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = volumeLabel }
                                .testTag("background-music-volume"),
                            thumb = {
                                Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                            },
                            track = {
                                SliderDefaults.Track(
                                    sliderState = it,
                                    modifier = Modifier.height(4.dp),
                                    enabled = enabled,
                                    thumbTrackGapSize = 0.dp,
                                    trackInsideCornerSize = 2.dp,
                                    drawStopIndicator = null,
                                )
                            },
                        )
                        Text(
                            text = stringResource(R.string.tts_music_volume_value, (volume * 100).roundToInt()),
                            modifier = Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }

            item(key = "bgm_tracks_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tts_music_tracks),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onImport,
                        enabled = enabled,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("background-music-import"),
                    ) {
                        Icon(painterResource(R.drawable.input_24px), null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tts_music_import), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            state.error?.let { message ->
                item(key = "bgm_error") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Text(
                            text = stringResource(message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (!state.loading && state.settings.tracks.isEmpty()) {
                item(key = "bgm_empty") {
                    EmptyPage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        icon = painterResource(R.drawable.music_note_24px),
                        title = stringResource(R.string.nothing_here),
                        description = stringResource(R.string.tts_music_description),
                    )
                }
            }

            items(state.settings.tracks, key = { it.id }) { track ->
                BackgroundMusicRow(
                    track = track,
                    unavailable = track.id in state.unavailable,
                    enabled = enabled,
                    onSelect = { onSelect(track.id, it) },
                    onDelete = { onDelete(track.id) },
                    onReplace = { onReplace(track.id) },
                )
            }
        }
    }
}

@Composable
private fun BackgroundMusicRow(
    track: BackgroundMusicTrack,
    unavailable: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onReplace: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        containerColor = if (track.selected) tokens.activePillFill else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (track.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else tokens.cardBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(track.selected, enabled = enabled, role = Role.Checkbox, onValueChange = onSelect)
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(checked = track.selected, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.W500,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
                    Text(
                        text = stringResource(R.string.tts_music_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = onReplace,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(stringResource(R.string.tts_music_replace))
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.delete_forever_24px),
                    contentDescription = stringResource(R.string.tts_music_delete, track.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
