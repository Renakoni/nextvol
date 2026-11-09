package indi.renakoni.nextvol.ui.home.bookshelf

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.ui.home.bookshelf.edit.bookshelfEditDestination
import indi.renakoni.nextvol.ui.home.bookshelf.home.bookshelfHomeDestination
import indi.renakoni.nextvol.ui.home.bookshelf.reorder.bookshelfReorderDestination
import io.nightfish.lightnovelreader.api.Route

fun NavGraphBuilder.bookshelfNavigation() {
    navigation<Route.Main.Bookshelf>(
        startDestination = Route.Main.Bookshelf.Home
    ) {
        bookshelfHomeDestination()
        bookshelfEditDestination()
        bookshelfReorderDestination()
    }
}
