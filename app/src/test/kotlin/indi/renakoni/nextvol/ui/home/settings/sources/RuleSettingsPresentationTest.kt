package indi.renakoni.nextvol.ui.home.settings.sources

import hnovel.imports.*
import hnovel.content.LoginForm
import indi.renakoni.nextvol.data.web.rules.SourceLoginService
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleSettingsPresentationTest {
    private fun declaration(fields: Map<String, JsonElement>, profile: String = LEGADO_PROFILE) =
        RuleSettingsPresentation.read(SourceDefinition("fixture", "legado", profile, "https://example.invalid/", "Fixture",
            true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1,
            JsonObject(mapOf("bookSourceUrl" to JsonPrimitive("https://example.invalid/")) + fields).toString()))

    @Test fun absentAndCheckOnlyAndEmptyFormsDoNotInventLogin() {
        for (fields in listOf(emptyMap(), mapOf("loginCheckJs" to JsonPrimitive("@js:throw 'never run'")),
            mapOf("loginUi" to JsonPrimitive("[]")))) {
            val state = declaration(fields + ("variableComment" to JsonPrimitive("Author description only")))
            assertFalse(state.loginDeclared)
            assertNull(state.loginErrorField)
            assertEquals("Author description only", state.variableDescription)
        }
    }

    @Test fun staticFormsAndBrowserDeclarationsUseExistingFormSemantics() {
        val form = declaration(mapOf("loginUi" to JsonPrimitive("""[{"name":"account"},{"name":"action","type":"button","action":"doWork()"}]""")))
        assertTrue(form.loginDeclared)
        assertTrue(form.configurationDeclared)
        assertNull(form.loginErrorField)
        val browser = declaration(mapOf("loginUrl" to JsonPrimitive("/login"), "browserRead" to JsonPrimitive(true)))
        assertTrue(browser.loginDeclared)
        assertFalse(browser.configurationDeclared)
        assertTrue(browser.nativeBrowser)
        assertNull(browser.loginErrorField)
    }

    @Test fun invalidDeclarationsHaveFieldLocationsAndDynamicFormsStayUnevaluated() {
        for (ui in listOf("{}", "not a form", "https://example.invalid/login")) {
            assertEquals("loginUi", declaration(mapOf("loginUi" to JsonPrimitive(ui))).loginErrorField)
        }
        val unknown = """[{"name":"account","type":"unknown"}]"""
        assertEquals("loginUi[0].type", declaration(mapOf("loginUi" to JsonPrimitive(unknown))).loginErrorField)
        val dynamic = mapOf("loginUi" to JsonPrimitive("@js:throw 'this cannot run while opening settings'"))
        assertEquals("loginUi", declaration(dynamic).loginErrorField)
        assertNull(declaration(dynamic, EXTENSION_PROFILE).loginErrorField)
        assertTrue(declaration(dynamic, EXTENSION_PROFILE).loginDeclared)
        assertTrue(declaration(dynamic, EXTENSION_PROFILE).configurationDeclared)
    }

    @Test fun directLoginUsesOnlyTheUniqueConventionalActionAndPreservesCredentialForms() {
        val login = """{"name":"Translated account button","type":"button","action":" login(); "}"""
        val form = LoginForm.parse("[$login,{\"name\":\"Preferences\"}]", "")
        assertEquals(form.fields.first().id, SourceLoginService.directLoginAction(form))
        for (extra in listOf("""{"name":"user"}""", """{"name":"secret","type":"password"}""",
            """{"name":"user"},{"name":"email"}""", login)) {
            assertNull(SourceLoginService.directLoginAction(LoginForm.parse("[$login,$extra]", "")))
        }
        assertNull(SourceLoginService.directLoginAction(LoginForm.parse("""[{"name":"Login","type":"button","action":"updateSource()"}]""", "")))
    }
}
