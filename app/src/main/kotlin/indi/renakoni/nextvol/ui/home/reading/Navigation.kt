package indi.renakoni.nextvol.ui.home.reading

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.ui.home.reading.home.readingHomeDestination
import indi.renakoni.nextvol.ui.home.reading.stats.readingStatsNavigation
import io.nightfish.lightnovelreader.api.Route

fun NavGraphBuilder.readingNavigation() {
    navigation<Route.Main.Reading>(
        startDestination = Route.Main.Reading.Home,
    ) {
        readingHomeDestination()
        readingStatsNavigation()
    }
}

@Suppress("unused")
fun NavController.navigateToHomeReadingDestination() {
    navigate(Route.Main.Reading)
}
