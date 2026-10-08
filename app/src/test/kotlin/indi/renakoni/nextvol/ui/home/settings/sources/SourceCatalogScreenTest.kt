package indi.renakoni.nextvol.ui.home.settings.sources

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import hnovel.imports.*
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.InstalledRuleSource
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SourceCatalogScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val model = mockk<SourcesViewModel>(relaxed = true)
    private val entries = listOf(
        CatalogSource("https://female.invalid/", SourceCategory.Female, "Female source", "Romance", 0),
        CatalogSource("https://added.invalid/", SourceCategory.Female, "Installed source", "Romance", 1),
        CatalogSource("https://anime.invalid/", SourceCategory.Anime, "Anime source", "Light novels", 0),
        CatalogSource("https://platform.invalid/", SourceCategory.Platforms, "Platform source", "Subscription chapters", 0))
    private val installed = SourceDefinition("existing", "legado", LEGADO_PROFILE, entries[1].key, entries[1].name,
        true, true, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
    private val state = SourceManagementState(catalog = entries, installed = listOf(InstalledRuleSource(installed, emptyList(), null)))

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun addAndBackKeepBothPagesDuringTheTransitionThenRemoveTheOldPage() {
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("Add book source").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithText("Manage groups").assertExists()
            compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.sources_url)).assertExists()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Manage groups").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithText("Manage groups").assertExists()
            compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.sources_url)).assertExists()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("Manage groups").assertIsDisplayed()
            compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.sources_url)).assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun categorySelectionSurvivesBackNavigationAndRecreationAndSkipsInstalledSources() {
        val contentRule = object : ComposeContentTestRule, ComposeTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) { activity.get().setContent(content = composable) }
        }
        val restoration = StateRestorationTester(contentRule)
        restoration.setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Categories").performClick()
        compose.onNodeWithText("Female fiction").performClick()
        compose.onNodeWithText("Platform source").assertDoesNotExist()
        compose.onNodeWithText("0 selected").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Installed source").assertIsNotEnabled()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Light novels").performClick()
        compose.onNodeWithText("Anime source").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Anime source").assertIsOn()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        verify(exactly = 1) { model.previewCatalog(setOf(entries[0].key, entries[2].key)) }
        verify(exactly = 0) { model.commit(any(), any(), any()) }
    }

    @Test fun bundledPixivCanBeSelectedFromTheAdultCategoryForPreview() {
        val catalog = SourceCatalog(activity.get())
        val pixiv = catalog.entries.single { it.key == "https://www.pixiv.net/novel" }
        activity.get().setContent { MaterialTheme {
            SourcesScreen(state.copy(catalog = catalog.entries), model, onDiagnostics = {}) {}
        } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Categories").performClick()
        compose.onNodeWithText(activity.get().getString(SourceCategory.Adult.title)).performScrollTo().performClick()
        compose.onNodeWithText(pixiv.name).performScrollTo().performClick()
        compose.onNodeWithText(pixiv.name).assertIsOn()
        compose.onNodeWithText("Continue").performClick()
        verify(exactly = 1) { model.previewCatalog(setOf(pixiv.key)) }
        verify(exactly = 0) { model.commit(any(), any(), any()) }
    }

    @Test fun importTabUsesTheExistingUrlAndFileEntryPoints() {
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Import").assertIsSelected()
        compose.onNodeWithText("Download and preview").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("https://fixture.invalid/sources.json")
        compose.onNodeWithText("Download and preview").performClick()
        verify(exactly = 1) { model.previewUrl("https://fixture.invalid/sources.json", AUTO_PROFILE) }
        compose.onNodeWithText("Choose local file").assertIsDisplayed()
    }
}
