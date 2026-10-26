package indi.renakoni.nextvol.ui.home.settings.sources

import android.app.Application
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import hnovel.imports.ImportOrigin
import hnovel.imports.SourceDefinition
import hnovel.imports.SourceDefinitionImporter
import hnovel.imports.SourceDefinitionStore
import hnovel.content.LoginField
import hnovel.content.LoginForm
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.data.web.rules.InstalledRuleSource
import indi.renakoni.nextvol.data.web.rules.LoginStatus
import indi.renakoni.nextvol.data.web.rules.RuleStoredSettings
import indi.renakoni.nextvol.data.web.rules.SourceRevisionUpdates
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import indi.renakoni.nextvol.data.web.zlibrary.ZLibraryState
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SourcesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val model = mockk<SourcesViewModel>(relaxed = true)
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun permissionFailureStaysVisibleBesideSaveAndTheSourceEditorIsSeparate() {
        val definition = SourceDefinition("fixture", "legado-json", hnovel.imports.LEGADO_PROFILE, "https://old.example/",
            "Source fixture", true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val installed = InstalledRuleSource(definition, listOf(hnovel.network.NetworkGrant("https://old.example:443")))
        val id = ImportedRuleSources.id(definition)
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = id))
        every { model.savePermissions(id, any()) } answers {
            state = state.copy(configurationError = SourceSaveError(indi.renakoni.nextvol.R.string.sources_missing_origin, "https://old.example:443"))
        }
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        val message = activity.get().getString(indi.renakoni.nextvol.R.string.sources_missing_origin, "https://old.example:443")
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Save permissions").assertIsDisplayed()
        compose.onNodeWithText("Edit source").performScrollTo().performClick()
        verify(exactly = 1) { model.beginEditor(id) }
        verify(exactly = 0) { model.saveEditor() }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun editorKeepsSaveErrorsVisibleAndBackRequiresDiscardingAnUnsavedDraft() {
        val definition = SourceDefinition("fixture", "legado-json", hnovel.imports.LEGADO_PROFILE, "https://old.example/",
            "Source fixture", true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val installed = InstalledRuleSource(definition, emptyList())
        val draft = SourceEditorState(installed, "{", originalJson = "{}",
            error = SourceSaveError(indi.renakoni.nextvol.R.string.sources_editor_invalid, "JSON"))
        val state = SourceManagementState(installed = listOf(installed), selected = ImportedRuleSources.id(definition), editor = draft)
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Save").assertIsDisplayed().performClick()
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.sources_editor_invalid, "JSON")).assertIsDisplayed()
        verify(exactly = 1) { model.saveEditor() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
        verify(exactly = 0) { model.closeEditor() }
        compose.onAllNodesWithText("Discard changes").onLast().performClick()
        verify(exactly = 1) { model.closeEditor() }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun jsonEditorIsImmediatelyEditableAndFindReplaceChangesOnlyTheDraft() {
        val definition = SourceDefinition("fixture", "legado-json", hnovel.imports.LEGADO_PROFILE, "https://old.example/",
            "Source fixture", true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val installed = InstalledRuleSource(definition, emptyList())
        val raw = """{"url":"old.example","script":"old.example"}"""
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = ImportedRuleSources.id(definition),
            editor = SourceEditorState(installed, raw)))
        every { model.editDraft(any()) } answers { state = state.copy(editor = state.editor!!.copy(rawJson = firstArg())) }
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        val editor = compose.onNodeWithContentDescription("Source rules (JSON)")
        editor.assertIsDisplayed().assertTextContains(raw)
        compose.onNodeWithText("Network access permissions").assertDoesNotExist()
        compose.onNodeWithText("Replace domain").assertDoesNotExist()
        compose.onNodeWithText("Find").assertDoesNotExist()
        compose.onNodeWithContentDescription("Find and replace").performClick()
        compose.onNodeWithText("Replace with").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show replace").performClick()
        compose.onNodeWithText("Replace all").assertIsNotEnabled()
        compose.onNodeWithText("Find").performTextInput("old.example")
        compose.onNodeWithText("Replace with").performTextInput("new")
        compose.onNodeWithContentDescription("Next").performClick()
        val first = androidx.compose.ui.text.TextRange(raw.indexOf("old.example"), raw.indexOf("old.example") + "old.example".length)
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, first))
        compose.onNodeWithContentDescription("Previous").performClick()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(raw.lastIndexOf("old.example"), raw.lastIndexOf("old.example") + "old.example".length)))
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, first))
        compose.onAllNodesWithText("Replace").onFirst().performClick()
        org.junit.Assert.assertEquals(raw.replaceFirst("old.example", "new"), state.editor!!.rawJson)
        val remaining = state.editor!!.rawJson.indexOf("old.example")
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(remaining, remaining + "old.example".length)))
        compose.onNodeWithText("1/1").assertExists()
        compose.onNodeWithText("Replace all").performClick()
        org.junit.Assert.assertEquals(raw.replace("old.example", "new"), state.editor!!.rawJson)
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Find and replace").performClick()
        compose.onNodeWithText("Find").assertDoesNotExist()
        editor.assertTextContains(state.editor!!.rawJson)
        compose.onNodeWithContentDescription("Editor tools").performClick()
        compose.onNodeWithText("Validate JSON").performClick()
        verify(exactly = 1) { model.validateEditor() }
        compose.onNodeWithContentDescription("Editor tools").performClick()
        compose.onNodeWithText("Format JSON").performClick()
        verify(exactly = 1) { model.formatEditor() }
        verify(exactly = 0) { model.saveEditor() }
    }

    @Test fun restoredPermissionDraftTracksAuthorizationsSavedInEditor() {
        val definition = SourceDefinition("fixture", "legado", "fixture", "https://old.example/",
            "Source fixture", false, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val installed = InstalledRuleSource(definition, listOf(hnovel.network.NetworkGrant("https://old.example:443")))
        val id = ImportedRuleSources.id(definition)
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = id))
        every { model.beginEditor(id) } answers { state = state.copy(editor = SourceEditorState(installed, "{}")) }
        every { model.closeEditor() } answers { state = state.copy(editor = null) }
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        val permissions = compose.onNodeWithText("Allowed site origins, one per line")
        val unsaved = "https://old.example:443\nhttps://draft.example:443"
        permissions.performScrollTo().performTextReplacement(unsaved)
        compose.onNodeWithText("Edit source").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source rules (JSON)").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        permissions.performScrollTo().assertTextContains(unsaved)
        compose.onNodeWithText("Edit source").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source rules (JSON)").assertExists()
        val updated = installed.copy(definition = definition.copy(revision = 2),
            origins = installed.origins + hnovel.network.NetworkGrant("https://new.example:443"))
        compose.runOnIdle {
            state = state.copy(installed = listOf(updated), editor = SourceEditorState(updated, "{}", saved = true))
        }
        compose.onNodeWithContentDescription("Back").performClick()
        val granted = "https://old.example:443\nhttps://new.example:443"
        permissions.performScrollTo().assertTextContains(granted)
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.savePermissions(id, granted) }
    }

    @Test fun findNavigationAndReplacementUseOnlyCountedMatches() {
        val definition = SourceDefinition("fixture", "legado", "fixture", "https://old.example/",
            "Source fixture", false, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val raw = """{"value":"aaaaa"}"""
        var draft by mutableStateOf(SourceEditorState(InstalledRuleSource(definition, emptyList()), raw))
        every { model.editDraft(any()) } answers { draft = draft.copy(rawJson = firstArg()) }
        activity.get().setContent { MaterialTheme { SourceEditorScreen(draft, false, model, finding = true) } }
        val editor = compose.onNodeWithContentDescription("Source rules (JSON)")
        val start = raw.indexOf("aaaaa")
        compose.onNodeWithContentDescription("Find").performTextInput("aa")
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("1/2").assertExists()
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithText("2/2").assertExists()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(start + 2, start + 4)))
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("1/2").assertExists()
        editor.performTextInputSelection(androidx.compose.ui.text.TextRange(start + 1, start + 3))
        compose.onNodeWithText("2 matches").assertExists()
        compose.onNodeWithContentDescription("Show replace").performClick()
        compose.onNodeWithContentDescription("Replace with").performTextInput("x")
        compose.onNodeWithText("Replace").performClick()
        org.junit.Assert.assertEquals("""{"value":"xaaa"}""", draft.rawJson)
        compose.onNodeWithText("1/1").assertExists()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(start + 1, start + 3)))
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithText("1/1").assertExists()
        compose.onNodeWithText("Replace all").performClick()
        org.junit.Assert.assertEquals("""{"value":"xxa"}""", draft.rawJson)
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
        verify(exactly = 0) { model.saveEditor() }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun findPanelKeepsInputsAndNavigationReachableWithLargeText() {
        val definition = SourceDefinition("fixture", "legado-json", hnovel.imports.LEGADO_PROFILE, "https://old.example/",
            "Source fixture", true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val draft = SourceEditorState(InstalledRuleSource(definition, emptyList()), "{}")
        activity.get().setContent { MaterialTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.6f)) {
                SourceEditorScreen(draft, false, model, finding = true)
            }
        } }
        compose.onNodeWithContentDescription("Show replace").performClick()
        compose.onNodeWithContentDescription("Find").assertIsDisplayed()
        compose.onNodeWithContentDescription("Replace with").assertIsDisplayed()
        compose.onNodeWithText("Replace all").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide replace").performClick()
        compose.onNodeWithContentDescription("Replace with").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun jsonValidationShowsTheLocationAndSelectsItWithoutChangingTheDraft() {
        val definition = SourceDefinition("fixture", "legado-json", hnovel.imports.LEGADO_PROFILE, "https://old.example/",
            "Source fixture", true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val raw = "{\n  \"name\":\n}"
        val draft = SourceEditorState(InstalledRuleSource(definition, emptyList()), raw,
            error = SourceSaveError(indi.renakoni.nextvol.R.string.sources_editor_syntax_error, line = 3, column = 1))
        activity.get().setContent { MaterialTheme { SourceEditorScreen(draft, false, model) } }
        compose.onNodeWithText("Line 3, column 1", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Source rules (JSON)").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(raw.length - 1, raw.length)))
        verify(exactly = 0) { model.editDraft(any()) }
        verify(exactly = 0) { model.saveEditor() }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun newSitesAreConfirmedOnlyAtSaveWithoutASecondPermissionsForm() {
        val definition = SourceDefinition("fixture", "legado-json", hnovel.imports.LEGADO_PROFILE, "https://old.example/",
            "Source fixture", true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val draft = SourceEditorState(InstalledRuleSource(definition, emptyList()), "{}",
            pendingOrigins = listOf("https://new.example:443"))
        activity.get().setContent { MaterialTheme { SourceEditorScreen(draft, false, model) } }
        compose.onNodeWithText("Allow access to new sites?").assertIsDisplayed()
        compose.onNodeWithText("https://new.example:443").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        verify(exactly = 1) { model.dismissEditorOrigins() }
        verify(exactly = 0) { model.approveEditorOrigins() }
        compose.onNodeWithText("Allow and save").performClick()
        verify(exactly = 1) { model.approveEditorOrigins() }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun configurationControlsScrollAndClosingDoesNotAuthenticate() {
        val form = LoginForm((1..40).map { LoginField("Control $it", "button", action = "configure()") }, null)
        var submitted = false
        var closed = false
        activity.get().setContent { MaterialTheme {
            SourceConfigurationSheet(form, false, { _, _, _, _ -> submitted = true }, { closed = true })
        } }
        compose.onNodeWithText("Source configuration").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Control 40"))
        compose.onNodeWithText("Control 40").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        org.junit.Assert.assertTrue(closed)
        org.junit.Assert.assertFalse(submitted)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun configurationHidesAccountActionsAndSubmitsOriginalFieldAndFormIds() {
        val update = LoginField("Update source", "button", action = "updateSource()")
        val preference = LoginField("Preference", "toggle", action = "configure()", choices = listOf("saved", "changed"))
        val form = LoginForm(listOf(
            LoginField("Source sign in", "button", action = " login(); "),
            LoginField("Source sign out", "button", action = "logout()"),
            preference,
            LoginField("Login settings", "button", action = "configureAccount()"),
            update), null, mapOf("Preference" to "saved"))
        activity.get().setContent { MaterialTheme {
            SourceConfigurationSheet(form, false, model::submitLogin, model::cancelLogin)
        } }
        compose.onNodeWithText("Source sign in").assertDoesNotExist()
        compose.onNodeWithText("Source sign out").assertDoesNotExist()
        compose.onNodeWithText("Login settings").assertIsDisplayed()
        compose.onNodeWithText("saved").assertIsDisplayed()
        compose.onNodeWithText("Preference").performTouchInput { longClick() }
        compose.onNodeWithText("saved").assertIsDisplayed()
        compose.onNodeWithText("changed").assertDoesNotExist()
        verify(exactly = 1) { model.submitLogin(form.values, preference.id, form.id, true) }
        compose.onNodeWithText("Update source").performTouchInput { click() }
        compose.onNodeWithText("Update source").performTouchInput { longClick() }
        verify(exactly = 1) { model.submitLogin(form.values, update.id, form.id) }
        verify(exactly = 1) { model.submitLogin(form.values, update.id, form.id, true) }
        verify(exactly = 3) { model.submitLogin(any(), any(), any(), any()) }
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun configurationDisablesAccountAndNavigatesWithoutSubmitting() {
        val account = LoginField("Account settings", "button", action = "account()", enabled = false)
        val setting = LoginField("General novels", "button", action = "configure()", section = "Discovery", checked = true)
        val form = LoginForm(listOf(account, setting), null)
        activity.get().setContent { MaterialTheme {
            SourceConfigurationSheet(form, false, model::submitLogin, model::cancelLogin)
        } }
        compose.onNodeWithText("Account settings").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Account settings").performTouchInput { longClick() }
        compose.onNodeWithText("General novels").assertDoesNotExist()
        compose.onNodeWithText("Discovery").performClick()
        compose.onNodeWithText("General novels").assertIsOn()
        verify(exactly = 0) { model.submitLogin(any(), any(), any(), any()) }
        compose.onNodeWithText("General novels").performClick()
        compose.onNodeWithText("General novels").performTouchInput { longClick() }
        verify(exactly = 1) { model.submitLogin(form.values, setting.id, form.id) }
        verify(exactly = 1) { model.submitLogin(form.values, setting.id, form.id, true) }
        verify(exactly = 2) { model.submitLogin(any(), any(), any(), any()) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Account settings").assertIsDisplayed()
        compose.onNodeWithText("General novels").assertDoesNotExist()
        verify(exactly = 0) { model.cancelLogin() }
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun configurationRefreshKeepsSectionAndShowsUpdatedState() {
        val field = LoginField("General novels", "button", action = "configure()", section = "Discovery", checked = true)
        val original = LoginForm(listOf(field), null, id = "original")
        var form by mutableStateOf(original)
        var submitted: Pair<String?, String>? = null
        activity.get().setContent { MaterialTheme {
            SourceConfigurationSheet(form, false, { _, action, formId, _ ->
                submitted = action to formId
                form = form.copy(fields = listOf(field.copy(checked = false)), id = form.id + "-refreshed")
            }, {})
        } }
        compose.onNodeWithText("Discovery").performClick()
        compose.onNodeWithText("General novels").assertIsOn().performClick()
        compose.onNodeWithText("General novels").assertIsOff()
        org.junit.Assert.assertEquals(field.id to original.id, submitted)
        compose.onNodeWithText("General novels").performClick()
        org.junit.Assert.assertEquals(field.id to "original-refreshed", submitted)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun loginDialogKeepsDeclaredAccountActions() {
        val login = LoginField("Source sign in", "button", action = "login()")
        val form = LoginForm(listOf(login, LoginField("Source sign out", "button", action = "logout()")), null)
        val submitted = mutableListOf<Triple<String?, String, Boolean>>()
        var busy by mutableStateOf(false)
        activity.get().setContent { MaterialTheme {
            SourceLoginDialog(form, busy, { _, action, formId, longClick -> submitted += Triple(action, formId, longClick) }, {})
        } }
        compose.onNodeWithText("Source sign in").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Source sign in").performTouchInput { longClick() }
        compose.onNodeWithText("Source sign out").assertIsDisplayed()
        compose.runOnIdle { busy = true }
        compose.onNodeWithText("Source sign in").assertIsNotEnabled().performTouchInput { longClick() }
        org.junit.Assert.assertEquals(listOf(Triple(login.id, form.id, false), Triple(login.id, form.id, true)), submitted)
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun savedChangesCloseAddingAndShowOneShortToastWithoutReplayingOnReentry() {
        ShadowToast.reset()
        var state by mutableStateOf(SourceManagementState())
        var visible by mutableStateOf(true)
        every { model.consumeSavedMessage() } answers { state = state.copy(message = null) }
        activity.get().setContent { MaterialTheme {
            if (visible) SourcesScreen(state, model) {}
        } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Download and preview").assertExists()
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.sources_saved) }
        compose.onNodeWithText("Download and preview").assertDoesNotExist()
        compose.onNodeWithText("Source changes saved.").assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertNull(state.message)
            org.junit.Assert.assertEquals(1, ShadowToast.shownToastCount())
            org.junit.Assert.assertEquals("Source changes saved.", ShadowToast.getTextOfLatestToast())
            org.junit.Assert.assertEquals(android.widget.Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
        }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, ShadowToast.shownToastCount()) }
        compose.runOnIdle { state = state.copy(message = indi.renakoni.nextvol.R.string.sources_saved) }
        compose.runOnIdle { org.junit.Assert.assertEquals(2, ShadowToast.shownToastCount()) }
        verify(exactly = 2) { model.consumeSavedMessage() }
    }

    @Suppress("DEPRECATION")
    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun updateResultsUseShortToastsWithoutClosingConfigurationOrLeavingInlineText() {
        ShadowToast.reset()
        val form = LoginForm(listOf(LoginField("Update source", "button", action = "updateSource()")), null)
        var state by mutableStateOf(SourceManagementState(loginForm = form, configurationPanel = true))
        every { model.consumeMessage(any()) } answers {
            if (state.message == firstArg<Int>()) state = state.copy(message = null)
        }
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        val messages = listOf(indi.renakoni.nextvol.R.string.sources_up_to_date,
            indi.renakoni.nextvol.R.string.sources_up_to_date,
            indi.renakoni.nextvol.R.string.sources_pixiv_update_unsupported,
            indi.renakoni.nextvol.R.string.sources_pixiv_update_failed)
        messages.forEachIndexed { index, message ->
            compose.runOnIdle { state = state.copy(message = message) }
            compose.onNodeWithText("Source configuration").assertIsDisplayed()
            compose.onNodeWithText(activity.get().getString(message)).assertDoesNotExist()
            compose.runOnIdle {
                org.junit.Assert.assertNull(state.message)
                org.junit.Assert.assertEquals(index + 1, ShadowToast.shownToastCount())
                org.junit.Assert.assertEquals(activity.get().getString(message), ShadowToast.getTextOfLatestToast())
                org.junit.Assert.assertEquals(android.widget.Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
                val content = ShadowToast.getLatestToast().view
                org.junit.Assert.assertTrue(content is android.widget.TextView)
                org.junit.Assert.assertEquals(activity.get().getString(message), (content as android.widget.TextView).text.toString())
            }
        }
        verify(exactly = 4) { model.consumeMessage(any()) }
    }

    @Test fun failedAndPartialImportsStayVisibleWithoutBeingConsumedAsSuccess() {
        ShadowToast.reset()
        var state by mutableStateOf(SourceManagementState())
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        for (message in listOf(indi.renakoni.nextvol.R.string.sources_import_partial,
            indi.renakoni.nextvol.R.string.sources_action_failed)) {
            compose.runOnIdle { state = state.copy(message = message) }
            compose.onNodeWithText(activity.get().getString(message)).assertIsDisplayed()
            compose.runOnIdle { org.junit.Assert.assertEquals(message, state.message) }
        }
        org.junit.Assert.assertEquals(0, ShadowToast.shownToastCount())
        verify(exactly = 0) { model.consumeSavedMessage() }
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h800dp")
    fun openingSettingsDoesNotInsertProgressButUpdatesStillDo() {
        val directory = java.nio.file.Files.createTempDirectory("source-settings-progress").toFile()
        val context = object : ContextWrapper(activity.get()) { override fun getFilesDir() = directory }
        val definition = SourceDefinition("qq-fixture", "legado", "fixture", "https://fixture.invalid/", "QQ fixture", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val sources = mockk<ImportedRuleSources>()
        coEvery { sources.sourceGroups() } returns emptyList()
        coEvery { sources.installedSources() } returns listOf(InstalledRuleSource(definition, emptyList()))
        val readingSettings = CompletableDeferred<Unit>()
        val finishReading = CompletableDeferred<Unit>()
        coEvery { sources.storedSettings(id, null) } coAnswers {
            readingSettings.complete(Unit)
            finishReading.await()
            RuleStoredSettings("", null, null)
        }
        val checkingUpdate = CompletableDeferred<Unit>()
        val updates = mockk<SourceRevisionUpdates>()
        every { updates.canCheckUpdate(definition) } returns false
        coEvery { updates.check(id) } coAnswers { checkingUpdate.complete(Unit); awaitCancellation() }
        val zLibrary = mockk<ZLibrarySources>()
        every { zLibrary.state } returns MutableStateFlow(ZLibraryState())
        val realModel = SourcesViewModel(context, sources, updates, mockk(), WebSourceRegistry(), zLibrary)
        fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
        try {
            activity.get().setContent { MaterialTheme {
                val state by realModel.state.collectAsState()
                SourcesScreen(state, realModel) {}
            } }
            waitUntil { !realModel.state.value.busy }
            compose.onNodeWithText("QQ fixture").performScrollTo().performClick()
            waitUntil { readingSettings.isCompleted }
            val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
            compose.onAllNodes(progress).assertCountEquals(0)
            val details = compose.onNodeWithText("Advanced options").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            finishReading.complete(Unit)
            waitUntil { !realModel.state.value.busy }
            org.junit.Assert.assertEquals(details,
                compose.onNodeWithText("Advanced options").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { realModel.checkUpdate(id) }
            waitUntil { checkingUpdate.isCompleted }
            compose.onAllNodes(progress).assertCountEquals(1)
            compose.runOnIdle { realModel.cancel() }
            waitUntil { !realModel.state.value.busy }
            compose.onAllNodes(progress).assertCountEquals(0)
        } finally {
            finishReading.complete(Unit)
            realModel.viewModelScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun longImportKeepsConfirmationReachableBeforeScrollingThroughCandidates() {
        val directory = java.nio.file.Files.createTempDirectory("long-source-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val raw = (0 until 22).joinToString(prefix = "[", postfix = "]") { index ->
                """{"bookSourceUrl":"https://source$index.invalid/","bookSourceName":"Source $index","bookSourceType":0,"customOrder":$index,"extensionFlag":"synthetic-private-value"}"""
            }
            val preview = importer.preview(raw)
            activity.get().setContent { MaterialTheme { SourcesScreen(previewState(preview), model) {} } }
            compose.onNodeWithText("Apply selected").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("22 of 22 selected").assertIsDisplayed()
            verify(exactly = 0) { model.commit(any(), any(), any()) }
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit((0 until 22).toSet(), (0 until 22).associateWith { "https://source$it.invalid:443" }, false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidPermissionLineIsShownAndCannotBeConfirmedUntilCorrected() {
        val directory = java.nio.file.Files.createTempDirectory("invalid-source-permissions").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""{"bookSourceUrl":"https://source.invalid/","bookSourceName":"Source","bookSourceType":0}""")
            activity.get().setContent { MaterialTheme { SourcesScreen(previewState(preview), model) {} } }
            compose.onNodeWithText("Advanced options").performClick()
            val field = compose.onNodeWithText("Allowed site origins, one per line")
            field.performScrollTo().performTextReplacement("https://source.invalid/\n\nhttps://210.140")
            compose.onNodeWithText("Invalid site address on line(s): 3").performScrollTo().assertExists()
            compose.onNodeWithText("Apply selected").assertIsNotEnabled()
            verify(exactly = 0) { model.commit(any(), any(), any()) }
            field.performScrollTo().performTextReplacement("https://source.invalid/\n\nhttps://210.140.92.183:8443")
            compose.onNodeWithText("Apply selected").assertIsEnabled().performClick()
            verify(exactly = 1) { model.commit(setOf(0), mapOf(0 to "https://source.invalid/\n\nhttps://210.140.92.183:8443"), false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun filtersKeepSelectionAndPermissionDraftsUntilExplicitConfirmation() {
        val directory = java.nio.file.Files.createTempDirectory("filtered-source-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            fun raw(index: Int) = """{"bookSourceUrl":"https://source$index.invalid/","bookSourceName":"Source $index","bookSourceType":0}"""
            importer.commit(importer.preview(raw(0)), listOf(hnovel.imports.ImportSelection(0, hnovel.imports.ImportDecision.Add)))
            val preview = importer.preview("[${raw(0)},${raw(1)}]")
            activity.get().setContent { MaterialTheme { SourcesScreen(previewState(preview), model) {} } }
            compose.onNodeWithText("Advanced options").performClick()
            compose.onNodeWithText("Updates").performClick()
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("Allowed site origins, one per line").performScrollTo().performTextReplacement("https://approved.invalid/")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("New"))
            compose.onNodeWithText("New").performClick()
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("2 of 2 selected").assertIsDisplayed()
            compose.onNodeWithText("Updates").performClick()
            compose.onNodeWithText("https://approved.invalid/").performScrollTo().assertExists()
            verify(exactly = 0) { model.commit(any(), any(), any()) }
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit(setOf(0, 1), mapOf(0 to "https://approved.invalid/", 1 to "https://source1.invalid:443"), false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun batchSelectionDeduplicatesDefinitionsAndManualChoiceKeepsOnlyOneVersion() {
        val directory = java.nio.file.Files.createTempDirectory("duplicate-source-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""[
                {"bookSourceUrl":"https://same.invalid/","bookSourceName":"Variant A","bookSourceType":0},
                {"bookSourceUrl":"https://same.invalid/","bookSourceName":"Variant B","bookSourceType":0},
                {"bookSourceUrl":"https://other.invalid/","bookSourceName":"Other","bookSourceType":0}]
            """)
            activity.get().setContent { MaterialTheme { SourcesScreen(previewState(preview), model) {} } }
            compose.onNodeWithText("Select visible").performClick()
            compose.onNodeWithText("2 of 2 selected").assertIsDisplayed()
            compose.onNodeWithText("Advanced options").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Variant B"))
            compose.onNodeWithText("Variant B").performClick()
            compose.onNodeWithText("2 of 3 selected").assertIsDisplayed()
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit(setOf(1, 2), mapOf(1 to "https://same.invalid:443", 2 to "https://other.invalid:443"), false) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun importNotesShowMeaningAndFieldNamesWithoutSourceValuesOrInternalCodes() {
        val directory = java.nio.file.Files.createTempDirectory("source-preview-notes").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""{"bookSourceUrl":"https://fixture.invalid/","bookSourceName":"Source","bookSourceType":0,"unknownField":"synthetic-private-value"}""")
            activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(preview = preview), model) {} } }
            compose.onNodeWithText("Import notes (2)").assertDoesNotExist()
            compose.onNodeWithText("Advanced options").performClick()
            compose.onNodeWithText("Import notes (2)").performScrollTo().performClick()
            compose.onNodeWithText("Field: unknownField").assertExists()
            compose.onNodeWithText("An unrecognized field is retained. Its behavior is not guaranteed.").assertExists()
            compose.onNodeWithText("ExecutionCompatibilityPending", substring = true).assertDoesNotExist()
            compose.onNodeWithText("synthetic-private-value", substring = true).assertDoesNotExist()
        } finally { directory.deleteRecursively() }
    }

    @Test fun enabledSearchOnlySourceDoesNotClaimVerificationOrOfferEmptyConfiguration() {
        val definition = SourceDefinition("unchecked", "legado", "fixture", "https://fixture.invalid/", "Unchecked", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val state = SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")))), selected = id,
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Unchecked", "fixture"),
                setOf(SourceCapability.Search)), SourceStatus.Ready)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Not checked yet").assertDoesNotExist()
        compose.onNodeWithText("Available").assertDoesNotExist()
        compose.onNodeWithText("Source configuration").assertDoesNotExist()
        compose.onNodeWithText("Signed out").assertDoesNotExist()
        compose.onNodeWithText("Format: legado").assertDoesNotExist()
        compose.onNodeWithText("Edit source").assertExists()
        compose.onNodeWithText("Advanced options").performScrollTo().performClick()
        compose.onNodeWithText("Check for update").assertDoesNotExist()
        compose.onNodeWithText("Restore previous revision").assertDoesNotExist()
        compose.onNodeWithText("Revision:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Not checked yet").assertDoesNotExist()
        compose.onNodeWithText("Source debugging").assertDoesNotExist()
        compose.onNodeWithText("Test source and export report").assertDoesNotExist()
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.savePermissions(id, "https://fixture.invalid/") }
    }

    @Test fun emptyStateOpensExplicitPreviewFlowWithoutImportingAutomatically() {
        activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(), model) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Source file URL").performScrollTo().performTextInput("https://fixture.invalid/source.json")
        compose.onNodeWithText("Download and preview").performScrollTo().performClick()
        verify(exactly = 1) { model.previewUrl("https://fixture.invalid/source.json", hnovel.imports.AUTO_PROFILE) }
        verify(exactly = 0) { model.commit(any(), any(), any()) }
        compose.onNodeWithText("Choose local file").assertExists()
        compose.onNodeWithText("Paste source JSON").assertDoesNotExist()
    }

    @Test fun blockedCoverOriginOnlyChangesTheDraftUntilTheOwnerIsSaved() {
        val definition = SourceDefinition("owner", "legado", "fixture", "https://books.invalid/", "Owner", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val state = SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://books.invalid/")),
            listOf(hnovel.network.OriginDenial("https://cdn.invalid:443", hnovel.network.ResourceKind.Image)))), selected = id,
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Owner", "fixture"),
                setOf(SourceCapability.Search)), SourceStatus.Ready)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        compose.onNodeWithText("https://cdn.invalid:443").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cover or content image").assertExists()
        compose.onNodeWithText("Add to permission draft").performScrollTo().performClick()
        verify(exactly = 0) { model.savePermissions(any(), any()) }
        compose.onNodeWithText("In permission draft").assertIsNotEnabled()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.savePermissions(id, "https://books.invalid/\nhttps://cdn.invalid:443") }
    }

    @Test fun importedSearchOnlySourceHasAnExplicitSearchEntryWithItsIdentity() {
        val definition = SourceDefinition("search-only", "legado", "fixture", "https://fixture.invalid/", "Search only", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val entry = SourceListing(SourceMetadata(WebDataSourceItem(id, "Search only", "fixture"), setOf(SourceCapability.Search)), SourceStatus.Ready)
        var state by mutableStateOf(SourceManagementState(
            installed = listOf(InstalledRuleSource(definition, emptyList())), registry = listOf(entry), selected = id))
        var selected: Identifier? = null
        activity.get().setContent { MaterialTheme {
            SourcesScreen(state, model, onSearch = { selected = it }) {}
        } }
        compose.onNodeWithText("Search this source").performClick()
        org.junit.Assert.assertEquals(id, selected)
        compose.runOnIdle { state = state.copy(registry = listOf(entry.copy(metadata = entry.metadata.copy(capabilities = emptySet())))) }
        compose.onNodeWithText("Search this source").assertDoesNotExist()
    }

    @Test fun registeredSourcesOfferActionsButInitializingAndFailedSourcesDoNot() {
        val definition = SourceDefinition("initializing", "legado", "fixture", "https://fixture.invalid/", "Loading source", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, """{"loginUrl":"https://fixture.invalid/login"}""")
        val id = ImportedRuleSources.id(definition)
        val entry = SourceListing(SourceMetadata(WebDataSourceItem(id, "Loading source", "fixture"),
            setOf(SourceCapability.Search, SourceCapability.Login)), SourceStatus.Registered)
        var state by mutableStateOf(SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")))), registry = listOf(entry)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Not initialized").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(selected = id, storedSettingsAvailable = true) }
        compose.onNodeWithText("Initialize source").assertDoesNotExist()
        compose.onNodeWithText("Search this source").assertIsEnabled()
        compose.onNodeWithText("Sign in").assertIsEnabled()
        compose.runOnIdle { state = state.copy(registry = listOf(entry.copy(status = SourceStatus.Initializing))) }
        compose.onNodeWithText("Search this source").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(registry = listOf(entry.copy(status = SourceStatus.Failed))) }
        compose.onNodeWithText("Search this source").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(registry = listOf(entry.copy(status = SourceStatus.Ready))) }
        compose.onNodeWithText("Search this source").assertIsEnabled()
    }

    @Test fun builtInSearchStartsLazySourcesAndHidesFailedOnes() {
        val id = Identifier("builtin", "fixture")
        val entry = SourceListing(SourceMetadata(WebDataSourceItem(id, "Built-in fixture", "fixture"),
            setOf(SourceCapability.Search), builtIn = true), SourceStatus.Registered)
        var state by mutableStateOf(SourceManagementState(registry = listOf(entry)))
        var searched: Identifier? = null
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onSearch = { searched = it }) {} } }
        compose.onNodeWithText("Initialize source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search this source").performClick()
        org.junit.Assert.assertEquals(id, searched)
        compose.runOnIdle { state = state.copy(registry = listOf(entry.copy(status = SourceStatus.Failed))) }
        compose.onNodeWithContentDescription("Search this source").assertDoesNotExist()
    }

    @Test fun disabledDefinitionIsSelectableInPreviewWithoutBeingEnabled() {
        val directory = java.nio.file.Files.createTempDirectory("disabled-preview").toFile()
        try {
            val importer = hnovel.imports.SourceDefinitionImporter(hnovel.imports.SourceDefinitionStore(directory.toPath()))
            val preview = importer.preview("""{"bookSourceUrl":"https://fixture.invalid/","bookSourceName":"Disabled source","bookSourceType":0,"enabled":false}""")
            org.junit.Assert.assertEquals(1, preview.candidates.size)
            activity.get().setContent { MaterialTheme { SourcesScreen(previewState(preview), model) {} } }
            compose.onNode(isToggleable()).assertIsEnabled().assertIsOff().performClick()
            compose.onNodeWithText("Apply selected").performClick()
            verify(exactly = 1) { model.commit(setOf(0), mapOf(0 to "https://fixture.invalid:443"), false) }
            verify(exactly = 0) { model.setEnabled(any(), any()) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun enablingSourceIsTheSingleActionAndDoesNotDiscardPermissionDrafts() {
        val definition = SourceDefinition("disabled", "legado", "fixture", "https://fixture.invalid/", "Disabled", false,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, """{"exploreUrl":"All::/books"}""")
        val id = ImportedRuleSources.id(definition)
        val installed = InstalledRuleSource(definition, listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")))
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = id))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithContentDescription("Enable source").assertIsOff()
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        compose.onNodeWithText("Allowed site origins, one per line").performScrollTo().performTextReplacement("https://draft.invalid/")
        compose.onNodeWithText("https://draft.invalid/").performScrollTo().assertExists()
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.savePermissions(id, "https://draft.invalid/") }
        compose.onNodeWithContentDescription("Enable source").performScrollTo().performClick()
        verify(exactly = 1) { model.setEnabled(id, true) }
        compose.runOnIdle { state = state.copy(installed = listOf(installed.copy(definition = definition.copy(rawJson = "{}")))) }
        compose.onNodeWithContentDescription("Show in Discover and Categories").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "en-rUS-w360dp-h640dp")
    fun cancellingUpdatePreviewDoesNotResetDetailScroll() {
        val directory = java.nio.file.Files.createTempDirectory("source-update-scroll").toFile()
        try {
            val raw = buildJsonObject {
                put("bookSourceUrl", "https://fixture.invalid/")
                put("bookSourceName", "Scrollable source")
                put("bookSourceType", 0)
                put("variableComment", (1..40).joinToString("\n") { "Configuration guidance line $it" })
            }.toString()
            val definition = SourceDefinition("scrollable", "legado", "fixture", "https://fixture.invalid/",
                "Scrollable source", false, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, raw)
            val id = ImportedRuleSources.id(definition)
            val preview = SourceDefinitionImporter(SourceDefinitionStore(directory.toPath())).preview(raw)
            var state by mutableStateOf(SourceManagementState(
                installed = listOf(InstalledRuleSource(definition, emptyList())), selected = id, storedSettingsAvailable = true))
            every { model.canCheckUpdate(any()) } returns true
            every { model.checkUpdate(id) } answers {
                state = state.copy(preview = preview, updateTarget = id,
                    previewOrigins = mapOf(0 to "https://fixture.invalid:443"))
            }
            every { model.dismissPreview() } answers {
                state = state.copy(preview = null, updateTarget = null, previewOrigins = emptyMap())
            }
            activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
            compose.onNodeWithText("Edit source variable").performScrollTo().performClick()
            compose.onNodeWithText("Source variable").performScrollTo().performTextReplacement("unsaved variable")
            compose.onNodeWithText("Advanced options").performScrollTo().performClick()
            val checkUpdate = activity.get().getString(indi.renakoni.nextvol.R.string.sources_check_update)
            compose.onNodeWithText(checkUpdate).performScrollTo().assertIsDisplayed()
            fun scrollPosition() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            org.junit.Assert.assertTrue("Fixture must start scrolled", scrollPosition() > 0f)
            compose.onNodeWithText(checkUpdate).performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.sources_preview)).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            org.junit.Assert.assertTrue("Returning from update preview must not jump to the top", scrollPosition() > 0f)
            compose.onNodeWithText(checkUpdate).assertIsDisplayed()
            compose.onNodeWithText("unsaved variable").assertExists()
            verify(exactly = 1) { model.checkUpdate(id) }
            verify(exactly = 1) { model.dismissPreview() }
        } finally { directory.deleteRecursively() }
    }

    private fun previewState(preview: hnovel.imports.ImportPreview) = SourceManagementState(preview = preview,
        previewOrigins = preview.candidates.associate { candidate -> candidate.index to
            hnovel.imports.SourceOriginCandidates.discover(kotlinx.serialization.json.Json.parseToJsonElement(candidate.rawJson)
                as kotlinx.serialization.json.JsonObject).joinToString("\n") { it.origin } })

    @Test fun zlibraryHasAnAppSearchEntryAndMirrorChangesStayDraftUntilSaved() {
        val native = indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
        val settings = indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySettings()
        val state = SourceManagementState(selected = native.ID,
            zLibrary = indi.renakoni.nextvol.data.web.zlibrary.ZLibraryState(settings),
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(native.ID, "Z-Library", "fixture"),
                setOf(SourceCapability.Search), builtIn = true), SourceStatus.Ready)))
        var selected: Identifier? = null
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model, onSearch = { selected = it }) {} } }
        compose.onNodeWithText("Search this source").performClick()
        org.junit.Assert.assertEquals(native.ID, selected)
        compose.onNodeWithText("Advanced options").performClick()
        compose.onNodeWithText("https://z-lib.fo:443").performScrollTo().performClick()
        verify(exactly = 0) { model.saveZLibrary(any(), any()) }
        compose.onNodeWithText("Save site and permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.saveZLibrary("https://z-lib.fo:443", settings.origins.joinToString("\n")) }
        compose.onNodeWithContentDescription("Enable source").performScrollTo().performClick()
        verify(exactly = 1) { model.setZLibraryEnabled(false) }
    }

    @Test fun loginControlsSubmitDefaultsAndTheCurrentSourceFormValues() {
        val form = LoginForm(listOf(LoginField("user", "text", label = "Account"), LoginField("password", "password"),
            LoginField("region", "select", action = "configure()", choices = listOf("east", "west"), label = "Region"),
            LoginField("remember", "toggle", action = "configure()", choices = listOf("no", "yes"), label = "Remember")), null,
            mapOf("user" to "alice", "password" to "", "region" to "east", "remember" to "no"))
        var submitted: Map<String, String>? = null
        val actions = mutableListOf<Pair<String, Boolean>>()
        activity.get().setContent { MaterialTheme { SourceLoginDialog(form, false, { values, action, formId, longClick ->
            org.junit.Assert.assertEquals(form.id, formId)
            if (action == null) {
                org.junit.Assert.assertFalse(longClick); submitted = values
            } else {
                actions += action to longClick
                if (longClick) org.junit.Assert.assertEquals(form.values, values)
            }
        }, {}) } }
        compose.onNodeWithText("Region: east").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("west").assertDoesNotExist()
        compose.onNodeWithText("Remember: no").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Remember: no").assertExists()
        compose.onNodeWithText("Account").performScrollTo().performTextReplacement("carol")
        compose.onNodeWithText("Region: east").performScrollTo().performClick()
        compose.onNodeWithText("west").performClick()
        compose.onNodeWithText("Remember: no").performScrollTo().performClick()
        compose.onAllNodesWithText("Sign in").filter(hasClickAction()).onFirst().performClick()
        org.junit.Assert.assertEquals(mapOf("user" to "carol", "password" to "", "region" to "west", "remember" to "yes"), submitted)
        val ids = form.fields.takeLast(2).map { it.id }
        org.junit.Assert.assertEquals(listOf(ids[0] to true, ids[1] to true, ids[0] to false, ids[1] to false), actions)
    }

    @Test fun largePanelDisplaysItsLastRowAndDispatchesBothSameNamedButtonsById() {
        val rows = List(59) { "{name:'setting$it'}" } + listOf(
            "{name:'same',type:'button',action:'one()'}", "{name:'same',type:'button',action:'two()'}")
        val form = LoginForm.parse(rows.joinToString(",", "[", "]"), "")
        val actions = mutableListOf<String?>()
        activity.get().setContent { MaterialTheme { SourceLoginDialog(form, false, { values, action, formId, _ ->
            org.junit.Assert.assertEquals(form.id, formId)
            org.junit.Assert.assertEquals(59, values.size)
            actions += action
        }, {}) } }
        compose.onNodeWithText("setting58").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("same")[0].performScrollTo().performClick()
        compose.onAllNodesWithText("same")[1].performScrollTo().performClick()
        org.junit.Assert.assertEquals(form.fields.takeLast(2).map { it.id }, actions)
    }

    @Test fun builtinRowOpensItsBasicSettings() {
        val builtinId = Identifier("lightnovelreader", "Wenku8")
        val builtin = SourceListing(SourceMetadata(WebDataSourceItem(builtinId, "Wenku8", "Built-in provider"),
            setOf(SourceCapability.Search), builtIn = true), SourceStatus.Registered)
        var state by mutableStateOf(SourceManagementState(registry = listOf(builtin)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Wenku8").performClick()
        verify(exactly = 1) { model.select(builtinId) }
        compose.runOnIdle { state = state.copy(selected = builtinId, network = SourceNetworkState()) }
        compose.onNodeWithText("Search this source").assertExists()
        compose.onNodeWithContentDescription("Bypass VPN").assertIsOff().assertIsEnabled().performClick()
        verify(exactly = 1) { model.setBypassVpn(true) }
    }

    @Test fun manualVariableNeedsNoAuthorCommentAndDraftsSurvivePreferenceChanges() {
        val definition = SourceDefinition("variable", "legado", "fixture", "https://fixture.invalid/", "Variable source", false,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val installed = InstalledRuleSource(definition, listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")))
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = id, variable = "saved",
            storedSettingsAvailable = true, network = SourceNetworkState()))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Source variable").assertDoesNotExist()
        compose.onNodeWithText("Account").assertDoesNotExist()
        compose.onNodeWithText("Edit source variable").performScrollTo().performClick()
        compose.onNodeWithText("Source variable").performScrollTo().performTextReplacement("unsaved variable")
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        compose.onNodeWithText("Allowed site origins, one per line").performScrollTo().performTextReplacement("https://draft.invalid/")
        compose.onNodeWithContentDescription("Bypass VPN").performScrollTo().performClick()
        verify(exactly = 1) { model.setBypassVpn(true) }
        compose.runOnIdle { state = state.copy(network = SourceNetworkState(true), busy = true) }
        compose.runOnIdle { state = state.copy(busy = false, installed = listOf(installed.copy(
            preferences = installed.preferences.copy(enabled = true)))) }
        compose.onNodeWithText("unsaved variable").performScrollTo().assertExists()
        compose.onNodeWithText("https://draft.invalid/").performScrollTo().assertExists()
        compose.onNodeWithText("Save variable").performScrollTo().performClick()
        verify(exactly = 1) { model.saveVariable(id, "unsaved variable") }
        verify(exactly = 0) { model.savePermissions(any(), any()) }
        compose.onNodeWithText("Save permissions").performScrollTo().performClick()
        verify(exactly = 1) { model.savePermissions(id, "https://draft.invalid/") }
    }

    @Test fun invalidLoginDeclarationKeepsBasicSettingsAndNetworkRepairReachable() {
        val definition = SourceDefinition("declarations", "legado", "fixture", "https://fixture.invalid/", "Declaration source", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, """{"loginCheckJs":"@js:true"}""")
        val id = ImportedRuleSources.id(definition)
        val installed = InstalledRuleSource(definition, emptyList())
        var state by mutableStateOf(SourceManagementState(installed = listOf(installed), selected = id, network = SourceNetworkState()))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Account").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithContentDescription("Bypass VPN").assertIsEnabled()
        compose.runOnIdle { state = state.copy(installed = listOf(installed.copy(definition = definition.copy(
            rawJson = """{"loginUi":"https://fixture.invalid/login"}""")))) }
        compose.onNodeWithText("Account").assertExists()
        compose.onNodeWithText("The source login declaration is invalid at loginUi. Review the source definition.").assertExists()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Basic settings").assertExists()
        compose.onNodeWithContentDescription("Bypass VPN").assertIsEnabled()
        compose.onNodeWithText("Network permissions").performScrollTo().performClick()
        compose.onNodeWithText("Save permissions").performScrollTo().assertIsEnabled()
    }

    @Test fun unsupportedNativeRouteCanOnlyTurnOffAnExistingBypassPreference() {
        val definition = SourceDefinition("native", "legado", "fixture", "https://fixture.invalid/", "Native source", false,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, """{"browserRead":true}""")
        val id = ImportedRuleSources.id(definition)
        var state by mutableStateOf(SourceManagementState(installed = listOf(InstalledRuleSource(definition, emptyList())),
            selected = id, network = SourceNetworkState(limitation = indi.renakoni.nextvol.R.string.sources_network_native)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithContentDescription("Bypass VPN").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(network = state.network!!.copy(bypassVpn = true)) }
        compose.onNodeWithContentDescription("Bypass VPN").assertIsOn().assertIsEnabled().performClick()
        verify(exactly = 1) { model.setBypassVpn(false) }
        verify(exactly = 0) { model.beginLogin(any()) }
    }

    @Test fun websiteVerificationNeedsNoLoginFormAndUsesOnlyTheCurrentAccountTicket() {
        val definition = SourceDefinition("verification", "legado", "fixture", "https://fixture.invalid/", "Verification source", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        val id = ImportedRuleSources.id(definition)
        val owner = indi.renakoni.nextvol.data.web.rules.VerificationOwner(id, "digest", 2)
        val prompt = indi.renakoni.nextvol.data.web.rules.VerificationPrompt("ticket", owner, "Verification source",
            hnovel.network.BrowserChallengeKind.Cloudflare, foreground = false)
        var state by mutableStateOf(SourceManagementState(installed = listOf(InstalledRuleSource(definition, emptyList())),
            selected = id, registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Verification source", "fixture"),
                setOf(SourceCapability.Search), revision = "digest", accountGeneration = 2), SourceStatus.Ready)),
            storedSettingsAvailable = true, loginStatus = LoginStatus.SessionSaved, verifications = listOf(prompt)))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Website verification required").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Open verification").performScrollTo().performClick()
        verify(exactly = 1) { model.verifyPending() }
        verify(exactly = 0) { model.beginLogin(any()) }
        compose.runOnIdle { state = state.copy(verifications = emptyList()) }
        compose.onNodeWithText("Session saved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        verify(exactly = 1) { model.logout(id) }
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(verifications = listOf(prompt.copy(opening = true))) }
        compose.onNodeWithText("Open verification").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(verifications = listOf(prompt.copy(kind = hnovel.network.BrowserChallengeKind.Login))) }
        compose.onNodeWithText("Sign-in required").assertExists()
        compose.onNodeWithText("Continue sign-in").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(registry = state.registry.map {
            it.copy(metadata = it.metadata.copy(accountGeneration = 3))
        }) }
        compose.onNodeWithText("Website verification").assertDoesNotExist()
        compose.onNodeWithText("Continue sign-in").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(verifications = listOf(prompt.copy(owner = owner.copy(generation = 3), foreground = true))) }
        compose.onNodeWithText("Open verification").assertDoesNotExist()
    }

    @Test fun backgroundLoginNoticeUsesItsTicketAndShowsConnectionFailuresAccurately() {
        val prompt = indi.renakoni.nextvol.data.web.rules.VerificationPrompt("ticket",
            indi.renakoni.nextvol.data.web.rules.VerificationOwner(Identifier("rules", "fixture"), "digest", 2),
            "Fixture", hnovel.network.BrowserChallengeKind.Login, foreground = false)
        val prompts = kotlinx.coroutines.flow.MutableStateFlow(listOf(prompt))
        val coordinator = mockk<indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator>(relaxed = true) {
            every { this@mockk.prompts } returns prompts
            coEvery { verifyBackground("ticket") } coAnswers {
                prompts.value = emptyList()
                throw hnovel.content.SourceContentException(hnovel.content.ContentError.Dns, "browser.verification")
            }
        }
        activity.get().setContent { MaterialTheme { indi.renakoni.nextvol.ui.SourceVerificationHost(coordinator) } }
        compose.onNodeWithText("Fixture needs sign-in. Background reading stopped.").assertIsDisplayed()
        compose.onNodeWithText("Continue sign-in").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(activity.get().getString(indi.renakoni.nextvol.R.string.sources_dns_failed)).assertIsDisplayed()
        coVerify(exactly = 1) { coordinator.verifyBackground("ticket") }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp")
    fun accountCardDistinguishesSavedSessionsAndOffersExplicitAccountActions() {
        val definition = SourceDefinition("account", "legado", "fixture", "https://fixture.invalid/", "Account source", true,
            false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, """{"loginUi":[{"name":"user"},{"name":"Settings","type":"button","action":"configure()"}]}""")
        val id = ImportedRuleSources.id(definition)
        var state by mutableStateOf(SourceManagementState(installed = listOf(InstalledRuleSource(definition,
            listOf(hnovel.network.NetworkGrant("https://fixture.invalid/")))), selected = id,
            registry = listOf(SourceListing(SourceMetadata(WebDataSourceItem(id, "Account source", "fixture"),
                setOf(SourceCapability.Login)), SourceStatus.Registered)), storedSettingsAvailable = true,
            loginStatus = LoginStatus.LoginSubmitted, accountName = "reader"))
        activity.get().setContent { MaterialTheme { SourcesScreen(state, model) {} } }
        compose.onNodeWithText("Sign-in submitted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("reader").assertExists()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        verify(exactly = 1) { model.logout(id) }
        compose.onNodeWithText("Sign in again").performScrollTo().performClick()
        verify(exactly = 1) { model.relogin(id) }
        compose.onNodeWithText("Open source panel").performScrollTo().performClick()
        verify(exactly = 1) { model.beginConfiguration(id) }
        verify(exactly = 0) { model.beginLogin(id) }
        compose.runOnIdle { state = state.copy(busy = true) }
        compose.onNodeWithText("Sign out").assertIsNotEnabled()
        compose.onNodeWithText("Sign in again").assertIsNotEnabled()
        compose.onNodeWithText("Open source panel").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false, loginStatus = LoginStatus.SessionSaved, accountName = null) }
        compose.onNodeWithText("Session saved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign-in submitted").assertDoesNotExist()
        compose.onNodeWithText("reader").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loginStatus = LoginStatus.Required) }
        compose.onNodeWithText("Sign-in required").assertExists()
        compose.onNodeWithText("Sign in again").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loginStatus = LoginStatus.LoggedOut) }
        compose.onNodeWithText("Signed out").assertExists()
        compose.onNodeWithText("Sign in").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
        compose.onNodeWithText("Open source panel").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state = state.copy(storedSettingsAvailable = false) }
        compose.onNodeWithText("Account status unavailable").assertExists()
        compose.onNodeWithText("Signed out").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        verify(exactly = 1) { model.select(id) }
        compose.runOnIdle { state = state.copy(storedSettingsAvailable = true, loginStatus = LoginStatus.LoginSubmitted,
            accountName = "reader", registry = emptyList()) }
        compose.onNodeWithText("reader").assertExists()
        compose.onNodeWithText("Sign out").assertDoesNotExist()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(accountName = "reader-with-a-very-long-account-name@example.com") }
        compose.onNodeWithText("reader-with-a-v…")
            .assertContentDescriptionEquals("reader-with-a-very-long-account-name@example.com")
        compose.onNodeWithText("reader-with-a-very-long-account-name@example.com").assertDoesNotExist()
    }
}
