package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

internal val LocalSpeechSchemeName = staticCompositionLocalOf<String?> { null }

@HiltViewModel
class SpeechSchemeContextViewModel @Inject constructor(editor: SpeechSettingsEditor) : ViewModel() {
    val name = editor.changes.map { it.schemeName }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
internal fun SpeechSchemeScope(content: @Composable () -> Unit) {
    val model = hiltViewModel<SpeechSchemeContextViewModel>()
    val name by model.name.collectAsStateWithLifecycle()
    val label = name?.ifBlank { stringResource(R.string.tts_scheme_initial) }
    CompositionLocalProvider(LocalSpeechSchemeName provides label, content = content)
}

@Composable
internal fun SpeechPageTitle(title: String) {
    Column {
        Text(title, style = MaterialTheme.typography.displayLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SpeechSchemeCaption()
    }
}

@Composable
internal fun SpeechSchemeCaption(modifier: Modifier = Modifier) {
    LocalSpeechSchemeName.current?.let {
        Text(stringResource(R.string.tts_scheme_editing, it), modifier, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
internal fun SpeechSettingsTopBar(scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    title: Int, onBack: () -> Unit, titleText: String? = null) {
    indi.renakoni.nextvol.ui.home.settings.SettingsTopBar(scrollBehavior, title, onBack, titleText,
        LocalSpeechSchemeName.current?.let { stringResource(R.string.tts_scheme_editing, it) })
}
