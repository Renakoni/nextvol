package indi.renakoni.nextvol.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.*
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.home.bookshelf.home.BookshelfHomeScreen
import indi.renakoni.nextvol.ui.home.bookshelf.home.BookshelfHomeViewModel
import indi.renakoni.nextvol.utils.currentMainRoute
import indi.renakoni.nextvol.utils.expandEnter
import indi.renakoni.nextvol.utils.expandExit
import indi.renakoni.nextvol.utils.expandPopEnter
import indi.renakoni.nextvol.utils.expandPopExit
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.flow.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfHomeLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun returningToBookshelfKeepsOneLiveSubscriptionAndStillReceivesUpdates() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        WorkManagerTestInitHelper.initializeTestWorkManager(activity.get(), Configuration.Builder().build())
        val store = ViewModelStore()
        val starts = AtomicInteger()
        val active = AtomicInteger()
        val snapshots = AtomicInteger()
        val shelves = MutableStateFlow(listOf(Bookshelf(id = 1, name = "Shelf")))
        val repository = mockk<BookshelfRepository> {
            every { getAllBookshelvesFlow() } returns flow {
                starts.incrementAndGet()
                active.incrementAndGet()
                try { emitAll(shelves.onEach { snapshots.incrementAndGet() }) }
                finally { active.decrementAndGet() }
            }
        }
        val users = mockk<UserDataRepository> {
            every { intListUserData(UserDataPath.BookshelfOrder.path) } returns mockk {
                every { getFlowWithDefault(any()) } returns flowOf(emptyList())
            }
            every { stringUserData(UserDataPath.Settings.Display.BookshelfLayout.path) } returns mockk {
                every { getFlow() } returns flowOf("List")
            }
        }
        val model = BookshelfHomeViewModel(repository, mockk(), users)
        store.put("shelf", model)
        fun await(condition: () -> Boolean) = compose.waitUntil(10_000) { compose.waitForIdle(); condition() }
        fun tab(label: Int) {
            compose.onNodeWithText(activity.get().getString(label), useUnmergedTree = true).performClick()
            compose.waitForIdle()
        }
        try {
            activity.get().setContent { MaterialTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                Box(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = Route.Main,
                        enterTransition = { expandEnter() }, exitTransition = { expandExit() },
                        popEnterTransition = { expandPopEnter() }, popExitTransition = { expandPopExit() }) {
                        navigation<Route.Main>(startDestination = Route.Main.Reading) {
                            navigation<Route.Main.Reading>(startDestination = Route.Main.Reading.Home) {
                                composable<Route.Main.Reading.Home> { Text("Reading host") }
                            }
                            navigation<Route.Main.Bookshelf>(startDestination = Route.Main.Bookshelf.Home) {
                                composable<Route.Main.Bookshelf.Home> {
                                    BookshelfHomeScreen(model::load, model.uiState, {}, {})
                                }
                            }
                        }
                    }
                    entry?.destination.currentMainRoute()?.let { root ->
                        Box(Modifier.align(Alignment.BottomCenter)) { HomeNavigateBar(root, nav) }
                    }
                }
            } }
            compose.waitForIdle()
            repeat(3) {
                tab(R.string.nav_bookshelf)
                await { model.uiState.bookshelfList.isNotEmpty() }
                tab(R.string.nav_reading)
            }
            assertEquals(1, starts.get())
            assertEquals(1, active.get())
            shelves.value = listOf(shelves.value.single().copy(name = "Renamed"))
            await { model.uiState.bookshelfList.single().name == "Renamed" }
            assertEquals(2, snapshots.get())
            tab(R.string.nav_bookshelf)
            assertEquals("Renamed", model.uiState.selectedBookshelf?.name)
            assertEquals(1, starts.get())
            compose.runOnIdle { store.clear() }
            await { active.get() == 0 }
        } finally {
            store.clear()
            activity.pause().stop().destroy()
        }
    }
}
