package indi.renakoni.nextvol.ui.home.categories

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavBackStackEntry
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageEffects
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryResultsScreen
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryResultsViewModel
import indi.renakoni.nextvol.ui.home.discovery.discoveryEnvironment
import indi.renakoni.nextvol.ui.home.CATEGORY_SOURCE_REQUEST
import indi.renakoni.nextvol.ui.home.settings.navigateToSettingsDestination
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.json.Json
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import indi.renakoni.nextvol.ui.components.ListCoverViewModel

fun NavGraphBuilder.categoriesDestination() {
    composable<Route.Main.Categories> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<CategoriesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        CategorySourceSelection(entry, !state.loadingSources, model::openSource)
        DiscoveryPageEffects(model, entry)
        CategoriesScreen(state, model::select,
            onCategory = { category -> model.result(category)?.let { nav.navigate(it) } },
            model::scroll, model::refresh,
            onManageSources = { nav.navigate(state.selected?.let { Route.Main.Settings.SourceDetail(it.namespace, it.id) } ?: Route.Main.Settings.Sources) },
            onSettings = nav::navigateToSettingsDestination, onBack = { nav.popBackStackIfResumed() },
            onInput = { id, value -> model.interact(id, value) }, onAction = { id, longClick -> model.interact(id, longClick = longClick) },
            onScope = model::selectScope, onPage = model::selectPage)
    }
    composable<Route.Main.DiscoveryResults> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<DiscoveryResultsViewModel>()
        val covers = hiltViewModel<ListCoverViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val environment = discoveryEnvironment()
        LifecycleStartEffect(model, environment) {
            model.environment(environment)
            model.setActive(true)
            onStopOrDispose { model.setActive(false, retainBrowser = nav.currentBackStackEntry?.id == entry.id) }
        }
        DisposableEffect(model, nav, entry) {
            onDispose { if (nav.currentBackStackEntry?.id != entry.id) model.setActive(false) }
        }
        DiscoveryResultsScreen(state, model::filter, model::loadMore, model::refresh, model::scroll,
            onBook = { nav.navigateToBookDetailDestination(it.storageKey) },
            onManageSources = { nav.navigate(Route.Main.Settings.SourceDetail(model.sourceId.namespace, model.sourceId.id)) },
            onSettings = nav::navigateToSettingsDestination, onBack = { nav.popBackStackIfResumed() }, coverFor = covers::cover)
    }
}

/** A shortcut can target a restored category entry. Bottom-tab visits do not overwrite its selection. */
@Composable
internal fun CategorySourceSelection(entry: NavBackStackEntry, ready: Boolean, onSelect: (Identifier) -> Unit) {
    val requested by entry.savedStateHandle.getStateFlow<String?>(CATEGORY_SOURCE_REQUEST, null).collectAsStateWithLifecycle()
    LaunchedEffect(requested, ready) {
        if (!ready) return@LaunchedEffect
        requested?.let { encoded ->
            val route = Json.decodeFromString<Route.Main.Categories>(encoded)
            val namespace = route.namespace
            val source = route.sourceId
            if (namespace != null && source != null) onSelect(Identifier(namespace, source))
            entry.savedStateHandle[CATEGORY_SOURCE_REQUEST] = null
        }
    }
}
