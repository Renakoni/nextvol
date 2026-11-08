package indi.renakoni.nextvol.ui.home.settings.about

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.ui.home.settings.licenses.navigateToSettingsLicensesDestination
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.settingsAboutDestination() {
    composable<Route.Main.Settings.About> {
        val navController = LocalNavController.current
        AboutSettingsScreen(
            onClickLicenses = navController::navigateToSettingsLicensesDestination,
            onBack = navController::popBackStackIfResumed,
        )
    }
}
