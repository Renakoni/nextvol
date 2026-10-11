package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryPermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DiscoveryFailureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun networkFailureKeepsItsExplanationAndWorkingActionsWithoutAField() {
        var retries = 0
        var managed = 0
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(DiscoveryError.Network, { retries++ }, { managed++ }, back = {})
        } }
        compose.onNodeWithText("Could not load this content.").assertIsDisplayed()
        compose.onNodeWithText("Could not load this source. Check the connection and retry.").assertIsDisplayed()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, retries)
        assertEquals(1, managed)
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Error type: Network").assertIsDisplayed()
        compose.onNodeWithText("Source rule:", substring = true).assertDoesNotExist()
    }

    @Test fun ruleFieldAndOriginalErrorTypeAreOnlyShownInDismissibleDetails() {
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(DiscoveryError.InvalidRules, {}, {}, back = null, field = "header")
        } }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_invalid_rules)).assertIsDisplayed()
        compose.onNodeWithText("Source rule: header").assertDoesNotExist()
        compose.onNodeWithText("Error type: InvalidRules").assertDoesNotExist()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Source rule: header").assertIsDisplayed()
        compose.onNodeWithText("Error type: InvalidRules").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Source rule: header").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun websiteErrorsShowTheirStatusAndChangingStatusClosesOldDetails() {
        var status by mutableStateOf(400)
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(DiscoveryError.Network, {}, {}, back = null, field = "ruleExplore.bookList", httpStatus = status)
        } }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_http_error, 400)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_invalid_rules)).assertDoesNotExist()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Error type: Network").assertIsDisplayed()
        compose.runOnIdle { status = 403 }
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_http_denied, 403)).assertIsDisplayed()
        compose.runOnIdle { status = 503 }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_http_error, 503)).assertIsDisplayed()
    }

    @Test fun structuredHostCallFactsAppearOnlyInDetailsAndMatchTheExportShape() {
        val failure = hnovel.execution.ExecutionResult.Failure(hnovel.execution.FailureCode.BridgeDenied,
            hnovel.rules.RuleError(hnovel.rules.RuleStage.Script, hnovel.rules.RuleLocation("exploreUrl"), "BridgeDenied"),
            hostCall = hnovel.rules.ScriptHostCall("cookie.getCookie", 2, List(2) { hnovel.rules.ScriptArgumentType.String }))
        val exported = kotlinx.serialization.json.Json.encodeToString(hnovel.execution.ExecutionResult.Failure.serializer(), failure)
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(DiscoveryError.InvalidRules, {}, {}, back = null, field = "exploreUrl", diagnostic = failure)
        } }
        compose.onNodeWithText(exported).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText(exported).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(exported).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun permissionExplanationAndTargetRemainVisibleBeforeOpeningManagement() {
        var managed = 0
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(DiscoveryError.PermissionDenied, {}, { managed++ }, back = null, field = "header",
                permission = DiscoveryPermission("https://permission.example:443", "Script"))
        } }
        compose.onNodeWithText("This source needs network permission. Check its allowed sites in source management.").assertIsDisplayed()
        compose.onNodeWithText("https://permission.example:443").assertIsDisplayed()
        compose.onNodeWithText("Source script library").assertIsDisplayed()
        compose.onNodeWithText("Source rule: header").assertDoesNotExist()
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, managed)
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Error type: PermissionDenied").assertIsDisplayed()
        compose.onNodeWithText("Source rule: header").assertIsDisplayed()
    }

    @Test fun unavailableSourceOffersManagementAndBackWithoutAnIneffectiveRetry() {
        var managed = 0
        var backed = 0
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(DiscoveryError.Unavailable, {}, { managed++ }, { backed++ })
        } }
        compose.onNodeWithText("This source was removed, disabled or replaced. Return to the list or open source management.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Book sources").performClick()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, managed)
        assertEquals(1, backed)
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Error type: Unavailable").assertIsDisplayed()
    }

    @Test fun changedFailureClosesDetailsInsteadOfShowingThemForAnotherError() {
        var error by mutableStateOf(DiscoveryError.InvalidRules)
        var field by mutableStateOf("header")
        activity.get().setContent { MaterialTheme {
            DiscoveryFailure(error, {}, {}, back = null, field = field)
        } }
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Source rule: header").assertIsDisplayed()
        compose.runOnIdle { field = "ruleExplore.bookList" }
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Source rule: ruleExplore.bookList").assertIsDisplayed()
        compose.runOnIdle { error = DiscoveryError.Network }
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Error type: Network").assertIsDisplayed()
        compose.onNodeWithText("Error type: InvalidRules").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-rRU-w320dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longActionLabelsWrapAtLargeFontSizesAndDetailsRemainReachable() {
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme(typography = AppTypography) {
                    DiscoveryFailure(DiscoveryError.Unavailable, {}, {}, back = {}, field = "ruleExplore.bookList")
                }
            }
        }
        for (label in listOf(R.string.sources_title, R.string.sources_back, R.string.discovery_error_details)) {
            val node = compose.onNodeWithText(activity.get().getString(label)).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= screen.left && bounds.right <= screen.right)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // Native text retains subpixel widths after the layout size is rounded to pixels.
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_error_details)).performClick()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_rule_field, "ruleExplore.bookList"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.close)).assertIsDisplayed().performClick()
    }
}
