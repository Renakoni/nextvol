package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry

@Composable
internal fun ReaderMotionSwitch(settings: ReaderSettingsEditor, modifier: Modifier = Modifier) {
    SettingsSwitchEntry(
        modifier = modifier,
        painter = painterResource(R.drawable.transition_chop_24px),
        title = stringResource(R.string.reader_reduce_motion),
        description = stringResource(R.string.reader_reduce_motion_description),
        checked = settings.reduceMotion,
        booleanUserData = settings.reduceMotionUserData,
    )
}
