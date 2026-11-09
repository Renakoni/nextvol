package indi.renakoni.nextvol.ui.home.explore

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.ui.home.explore.home.exploreHomeDestination
import indi.renakoni.nextvol.ui.home.explore.search.exploreSearchDestination
import indi.renakoni.nextvol.ui.home.explore.search.searchHubDestination
import io.nightfish.lightnovelreader.api.Route

@OptIn(ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.exploreNavigation() {
    navigation<Route.Main.Explore>(
        startDestination = Route.Main.Explore.Home,
    ) {
        exploreHomeDestination()
        searchHubDestination()
        exploreSearchDestination()
    }
}

