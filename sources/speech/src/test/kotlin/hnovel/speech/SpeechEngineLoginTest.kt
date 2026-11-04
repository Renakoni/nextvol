package hnovel.speech

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SpeechEngineLoginTest {
    @Test fun packageMappingUpdatesOnlyItsEngineFieldsAndStorageWinsOverCookieValues() {
        val parameters = """{"audioFormat":"mp3","unknown":{"keep":true},"webView":{"loginUrl":"https://example.org/login","userAgent":"package-agent","mapping":{"cookies.sid":"session","cookies":"cookie","localStorage.token":"token","cookies.token":"token","localStorage.missing":"expired"}}}"""
        val login = speechEngineLogin(parameters)!!
        assertEquals("https://example.org/login", login.loginUrl)
        assertEquals("package-agent", login.userAgent)
        val original = Json.parseToJsonElement(parameters).jsonObject
        val updated = Json.parseToJsonElement(applySpeechEngineLogin(parameters, "sid=a=b; token=old",
            mapOf("token" to "current", "unmapped" to "ignore"))).jsonObject
        assertEquals("a=b", updated.string("session"))
        assertEquals("sid=a=b; token=old", updated.string("cookie"))
        assertEquals("current", updated.string("token"))
        assertEquals("null", updated.string("expired"))
        assertEquals(original["webView"], updated["webView"])
        assertEquals(original["unknown"], updated["unknown"])
        assertEquals("mp3", updated.string("audioFormat"))
        assertFalse(updated.containsKey("unmapped"))
        assertNull(speechEngineLogin("{audioFormat:'mp3'}"))
    }
}
