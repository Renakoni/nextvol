package indi.renakoni.nextvol.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton

/** Shared action, placed last in each root's existing app bar. */
@Composable
internal fun HomeSettingsAction(onClick: () -> Unit) {
    EditorialIconButton(
        onClick = onClick,
        painter = painterResource(R.drawable.outline_settings_24px),
        contentDescription = stringResource(R.string.nav_settings),
    )
}
