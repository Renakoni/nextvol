package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Synthetic data shapes from the field inventory; no credentials or copied site scripts. */
class LoginFormDataTest {
    @Test fun strictAndGsonStyleArraysBuildTheSameForm() {
        val strict = """[{"name":"account","type":"text"},{"name":"secret","type":"password"},
            {"name":"sign in","type":"button","action":"login()","style":{"layout_flexGrow":1}}]"""
        val lenient = """[{name:'account',type:'text'},{name:'secret',type:'password'},
            {name:'sign in',type:'button',action:'login()',style:{layout_flexGrow:1}}]"""
        val form = LoginForm.parse(strict, "")
        assertEquals(form, LoginForm.parse(lenient, "").copy(id = form.id))
        assertEquals("sign in", LoginForm.parse(lenient, "").fields.last().name)
    }

    @Test fun escapedQuotesAndNestedStyleStayData() {
        val form = LoginForm.parse("""[{name:'reader\'s account',style:{extra:[{label:'text'}]}},
            {name:'action',type:'button',action:'source.put(\'key\',\'value\')'}]""", "")
        assertEquals("reader's account", form.fields.first().name)
        assertEquals("source.put('key','value')", form.fields.last().action)
    }

    @Test fun malformedOrNonArrayDataHasATypedRedactedLocation() {
        for (ui in listOf("[{name:'unfinished'}", "[{name:'a'}] trailing", "{}", "null", "false", "'not a form'", "https://example.invalid/account",
            "[{name:(function(){return 'a'})()}]")) {
            val failure = runCatching { LoginForm.parse(ui, "") }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SourceContentException)
            assertEquals(ContentError.InvalidRule, (failure as SourceContentException).code)
            assertEquals("loginUi", failure.field)
            assertFalse(failure.toString().contains("unfinished"))
        }
    }

    @Test fun lenientRowsSupportStandardControlsAndEnforceTheirValues() {
        for ((ui, field) in listOf(
            "[{name:'a',unknown:true}]" to "loginUi[0].unknown",
            "[{name:'a',type:'webview'}]" to "loginUi[0].type",
            "[{name:12}]" to "loginUi[0].name",
            "[{name:'a'},{name:'a',default:'conflict'}]" to "loginUi.name",
            "[{name:'region',type:'select',chars:[]}]" to "loginUi[0].chars",
            "[{name:'region',type:'select',chars:['east','east']}]" to "loginUi[0].chars",
            "[{name:'region',type:'select',chars:['east'],default:'west'}]" to "loginUi[0].default"
        )) {
            val failure = runCatching { LoginForm.parse(ui, "", extended = true) }.exceptionOrNull() as SourceContentException
            assertEquals(ContentError.InvalidRule, failure.code)
            assertEquals(field, failure.field)
        }
        val form = LoginForm.parse("[{name:'region',type:'select',chars:['east','west'],default:'west',viewName:'label()'}]", "")
        assertEquals("west", form.values["region"])
        assertEquals("label()", form.fields.single().viewName)
        assertTrue(runCatching { form.validate(mapOf("region" to "outside")) }.isFailure)
        val oversized = List(129) { "{name:'field$it'}" }.joinToString(",", "[", "]")
        assertEquals(ContentError.Limit, (runCatching { LoginForm.parse(oversized, "") }.exceptionOrNull() as SourceContentException).code)
    }

    @Test fun realPanelSizesFitWhileRowTextDepthAndSubmissionLimitsRemainExplicit() {
        for (count in listOf(12, 44, 58, 61, 128)) {
            val form = LoginForm.parse(List(count) { "{name:'field$it'}" }.joinToString(",", "[", "]"), "")
            assertEquals(count, form.fields.size)
            form.validate(form.values)
        }
        for (ui in listOf("[{name:'${"x".repeat(65536)}'}]", "[".repeat(65) + "]".repeat(65), " ".repeat(65537))) {
            val failure = runCatching { LoginForm.parse(ui, "") }.exceptionOrNull() as SourceContentException
            assertEquals(ContentError.Limit, failure.code)
        }
        val form = LoginForm.parse("[{name:'value'}]", "")
        assertEquals(ContentError.Limit, (runCatching { form.validate((0..128).associate { "k$it" to "v" }, true) }
            .exceptionOrNull() as SourceContentException).code)
        assertEquals(ContentError.Limit, (runCatching { form.validate(mapOf("value" to "x".repeat(16385))) }
            .exceptionOrNull() as SourceContentException).code)
    }

    @Test fun compatibleRepeatedInputsShareTheirBindingButButtonsHaveSeparateIdentities() {
        val form = LoginForm.parse("""[{name:'region',type:'select',chars:['east','west']},
            {name:'region',type:'select',chars:['east','west']},
            {name:'region',type:'button',action:'one()'},{name:'region',type:'button',action:'two()'}]""", "", true)
        assertEquals(4, form.fields.map { it.id }.distinct().size)
        assertEquals(mapOf("region" to "east"), form.values)
        assertEquals(mapOf("region" to "west"), form.withValues(mapOf("region" to "west")).values)
        form.validate(mapOf("region" to "west"))
        assertTrue(runCatching { form.validate(mapOf("region" to "invalid")) }.isFailure)
        assertTrue(runCatching { LoginForm.parse("""[{name:'region',type:'select',chars:['east']},
            {name:'region',type:'select',chars:['west']}]""", "", true) }.isFailure)
    }

    @Test fun labelsAndReorderingDoNotChangeTheIdentityOfDistinctActions() {
        val first = LoginForm.parse("[{name:'same',type:'button',action:'one()'},{name:'same',type:'button',action:'two()'}]", "")
        val second = LoginForm.parse("[{name:'renamed',type:'button',action:'two()'},{name:'renamed',type:'button',action:'one()'}]", "")
        assertEquals(first.fields.associate { it.action to it.id }, second.fields.associate { it.action to it.id })
        assertNotEquals(first.id, second.id)
    }

    @Test fun staticLenientFormDoesNotStartTheRuleInterpreter() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.afterRun = { error("Static loginUi is data") }
            fixture.source { raw -> JsonObject(raw + ("loginUi" to JsonPrimitive("[{name:'account'},{name:'secret',type:'password'}]"))) }.use { source ->
                assertEquals(listOf("account", "secret"), source.loginForm().fields.map { it.name })
                assertEquals(0, fixture.documents.get())
            }
        }
    }
}
