package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import hnovel.speech.previewSpeechPackage
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechPackagePreviewScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun roleConfigurationIsReadableWithLargeTextAndOffersNoInstallOrAudition() {
        val file = temporary.newFile().apply { writeText("roles: [{name: Narrator}]") }
        val preview = previewSpeechPackage(file, "roles.yaml")
        var backed = false
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                MaterialTheme(typography = AppTypography) { SpeechPackagePreviewScreen(preview) { backed = true } }
            }
        }
        compose.onNodeWithText("Package structure recognized").assertIsDisplayed()
        compose.onNodeWithTag("speech-package-preview").performScrollToNode(hasText("This configuration type is recognized; migration is not available yet."))
        compose.onNodeWithText("This configuration type is recognized; migration is not available yet.").assertIsDisplayed()
        compose.onNodeWithText("Import voices").assertDoesNotExist()
        compose.onNodeWithText("Preview voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }

    @Test fun packagePreviewAndExistingJsonImportHaveSeparateWorkingEntries() {
        var imported = 0
        var previewed = 0
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(), {}, { imported++ }, {}, {}, { _, _ -> }, {}, {}, { _, _, _, _ -> },
                    onPackagePreview = { previewed++ })
            }
        }
        compose.onNodeWithContentDescription("Library options").performClick()
        compose.onNodeWithText("Import voices").assertIsDisplayed()
        compose.onNodeWithText("Preview ZIP or YAML package").performClick()
        assertEquals(1, previewed)
        assertEquals(0, imported)
        compose.onNodeWithContentDescription("Library options").performClick()
        compose.onNodeWithText("Import voices").performClick()
        assertEquals(1, imported)
    }
}
