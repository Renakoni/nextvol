package indi.renakoni.nextvol.ui.home.explore.search

import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.searchHubDestination() {
    composable<Route.Main.Explore.SearchHub> {
        val nav = LocalNavController.current
        val model = hiltViewModel<SearchHubViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(model) {
            model.setActive(true)
            onStopOrDispose { model.setActive(false) }
        }
        SearchHubScreen(
            state = state, onQuery = model::setQuery, onSearch = model::search,
            onScope = model::selectScope, onSelectSource = model::selectSource,
            onDeleteHistory = model::deleteHistory, onClearHistory = model::clearHistory,
            onLoadMore = model::loadMore, onStop = model::stop, onResume = model::resume, onRetry = model::retryFailures,
            onManageSources = { nav.navigate(Route.Main.Settings.Sources) },
            onSource = { id -> nav.navigate(Route.Main.Settings.SourceDetail(id.namespace, id.id)) },
            onBook = nav::navigateToBookDetailDestination, onBack = nav::popBackStackIfResumed
        )
    }
}

fun NavGraphBuilder.exploreSearchDestination() {
    composable<Route.Main.Explore.Search> { entry ->
        val nav = LocalNavController.current
        val model = hiltViewModel<ExploreSearchViewModel>()
        LifecycleStartEffect(model) {
            model.setActive(true)
            onStopOrDispose { model.setActive(false, retainBrowser = nav.currentBackStackEntry?.id == entry.id) }
        }
        DisposableEffect(model, nav, entry) {
            onDispose { if (nav.currentBackStackEntry?.id != entry.id) model.setActive(false) }
        }
        LaunchedEffect(model, entry) {
            entry.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.navigation.collect { command ->
                    if (model.accepts(command)) nav.navigateToBookDetailDestination(command.bookId)
                }
            }
        }
        ExploreSearchScreen(
            onLoadMore = model::loadMore,
            exploreSearchUiState = model.uiState,
            refresh = model::retry,
            requestAddBookToBookshelf = { nav.navigateToAddBookToBookshelfDialog(it) },
            onClickBack = { nav.popBackStackIfResumed() },
            onChangeSearchType = model::changeSearchType,
            onSearch = model::search,
            onClickDeleteHistory = model::deleteHistory,
            onClickClearAllHistory = model::clearAllHistory,
            onClickBook = { nav.navigateToBookDetailDestination(it) },
            updateSuggestions = model::updateSuggestions,
            onManageSources = {
                nav.navigate(Route.Main.Settings.SourceDetail(model.sourceId.namespace, model.sourceId.id))
            },
        )
    }
}

