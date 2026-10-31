package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.setting.AbstractSettingState
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltViewModel
class SpeechPlaybackSettingsViewModel @Inject constructor(userData: UserDataRepository) : ViewModel() {
    val preferences = SpeechPlaybackPreferences(userData, viewModelScope)
}

@Stable
class SpeechPlaybackPreferences(userData: UserDataRepository, scope: CoroutineScope) : AbstractSettingState(scope) {
    val auditionData = userData.stringUserData("tts.audition.text")
    val parametersData = userData.booleanUserData("tts.editor.parameters")
    val extensionsData = userData.booleanUserData("tts.editor.extensions")
    val auditionText by auditionData.asState("")
    val showParameters by parametersData.asState(false)
    val showExtensions by extensionsData.asState(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechPlaybackSettingsScreen(preferences: SpeechPlaybackPreferences, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val defaultText = stringResource(R.string.tts_preview_text)
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_playback_preferences, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_audition_text),
                    option = preferences.auditionText.ifBlank { defaultText },
                    onClick = { text = preferences.auditionText.ifBlank { defaultText }; editing = true })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_show_parameters),
                    trailingContent = { Switch(preferences.showParameters, preferences.parametersData::asynchronousSet) },
                    onClick = { preferences.parametersData.asynchronousSet(!preferences.showParameters) })
            }
            item {
                SettingsClickableEntry(title = stringResource(R.string.tts_voice_show_extensions),
                    trailingContent = { Switch(preferences.showExtensions, preferences.extensionsData::asynchronousSet) },
                    onClick = { preferences.extensionsData.asynchronousSet(!preferences.showExtensions) })
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.tts_voice_audition_text)) },
        text = { TextField(text, { text = it.take(2000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
        confirmButton = { TextButton(onClick = { preferences.auditionData.asynchronousSet(text.trim()); editing = false }) {
            Text(stringResource(R.string.tts_engine_save))
        } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
}
