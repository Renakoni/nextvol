package indi.renakoni.nextvol.tts

import android.content.Context
import hnovel.network.*
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechEngineLoginServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun loginReadsCookiesForTheCompletedPage() = runBlocking {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns temporary.root
        val network = mockk<SpeechNetworkCheck>()
        coEvery { network.check() } returns Unit
        val browser = mockk<AndroidSourceBrowser>()
        val finalUrl = "https://source.invalid/account/home"
        coEvery { browser.execute(any(), any(), any(), any(), any()) } coAnswers {
            firstArg<SourceSession>().updateNativeBrowserCookies(finalUrl,
                listOf("sid=current; Path=/account; Secure; HttpOnly"))
            BrokerResult.Success(BrokerResponse(200, finalUrl, emptyMap(), "{}".toByteArray(), "UTF-8", 0))
        }
        val engine = SpeechEngineConfig("login", "Login", parameters =
            """{"webView":{"loginUrl":"https://source.invalid/login","mapping":{"cookies.sid":"token"}}}""")

        val result = SpeechEngineLoginService(context, browser, StorageCipher.Plain, network).login(engine)

        assertEquals("current", Json.parseToJsonElement(result.parameters).jsonObject.getValue("token").jsonPrimitive.content)
    }
}
