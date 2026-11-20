package indi.renakoni.nextvol.ui.home.settings.list

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.home.settings.data.SettingsMenuOptions.InterfaceLanguageOptions
import indi.renakoni.nextvol.ui.home.settings.formats.navigateToSettingsFormatsDestination
import io.nightfish.lightnovelreader.api.ui.LocalNavController

@Composable
fun DisplaySettingsList() {
    val navController = LocalNavController.current
    var locales by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales()) }
    var pendingLocales by remember { mutableStateOf<LocaleListCompat?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locales = AppCompatDelegate.getApplicationLocales()
    }

    // Dispose the old language popup before AppCompat can recreate the Activity.
    val selection = pendingLocales ?: locales
    key(selection) {
        SettingsMenuEntry(
            modifier = Modifier.background(colorScheme.surfaceContainer),
            painter = painterResource(R.drawable.language_24px),
            title = stringResource(R.string.settings_app_language),
            description = stringResource(R.string.settings_app_language_desc),
            options = InterfaceLanguageOptions,
            selectedOptionKey = remember { InterfaceLanguageOptions.selectedKey(selection) },
            onOptionChange = { tag ->
                val selected = LocaleListCompat.forLanguageTags(tag)
                if (selected != locales) pendingLocales = selected
            },
        )
    }
    pendingLocales?.let { selected ->
        SideEffect {
            AppCompatDelegate.setApplicationLocales(selected)
            locales = selected
            pendingLocales = null
        }
    }
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.short_text_24px),
        title = stringResource(R.string.settings_formats),
        description = stringResource(R.string.settings_formats_desc),
        onClick = navController::navigateToSettingsFormatsDestination
    )
}
