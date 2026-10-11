package indi.renakoni.nextvol.ui.home.explore.home

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageEffects
import indi.renakoni.nextvol.ui.home.settings.navigateToSettingsDestination
import indi.renakoni.nextvol.utils.isResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import indi.renakoni.nextvol.ui.components.ListCoverViewModel

fun NavGraphBuilder.exploreHomeDestination() {
    composable<Route.Main.Explore.Home> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<ExploreHomeViewModel>()
        val covers = hiltViewModel<ListCoverViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        DiscoveryPageEffects(model, entry)
        ExploreHomeScreen(state, model::select, model::scroll, model::refresh,
            onMore = { model.more(it)?.let { route -> nav.navigate(route) } },
            onBook = { nav.navigateToBookDetailDestination(it.storageKey) },
            onSearch = { nav.navigate(Route.Main.Explore.SearchHub(state.selected?.namespace, state.selected?.id,
                state.sources.firstOrNull { it.metadata.id == state.selected }?.metadata?.item?.name)) },
            onManageSources = { nav.navigate(state.selected?.let { Route.Main.Settings.SourceDetail(it.namespace, it.id) } ?: Route.Main.Settings.Sources) },
            onInput = { id, value -> model.interact(id, value) },
            onAction = { id, longClick -> model.interact(id, longClick = longClick) },
            onSettings = nav::navigateToSettingsDestination, onScope = model::selectScope, onPage = model::selectPage,
            onRetryPreview = model::retryPreview, coverFor = covers::cover)
    }
}

fun NavController.navigateToExploreHomeDestination() {
    if (!isResumed()) return
    navigate(Route.Main.Explore.Home)
}
