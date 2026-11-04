package indi.renakoni.nextvol.tts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.*
import hnovel.speech.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject

/** Explicit engine login reuses the isolated browser; package import never enters this path. */
class SpeechEngineLoginService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val browser: AndroidSourceBrowser,
    private val cipher: StorageCipher,
    private val network: SpeechNetworkCheck,
) {
    suspend fun login(engine: SpeechEngineConfig): SpeechEngineConfig = withContext(Dispatchers.IO) {
        val login = requireNotNull(speechEngineLogin(engine.parameters))
        val origin = requireNotNull(sourceOrigin(login.loginUrl))
        val keys = login.mapping.keys.filter { it.startsWith("localStorage.") }.map { it.removePrefix("localStorage.") }
        val script = "(function(){var storage={};${JsonArray(keys.map(::JsonPrimitive))}" +
            ".forEach(function(key){storage[key]=localStorage.getItem(key);});return JSON.stringify(storage);})()"
        network.check()
        SourceBroker(File(context.noBackupFilesDir, "speech-engine-login").toPath(), cipher = cipher, browser = browser).use { owner ->
            val session = owner.open(SourceScope("speech", engine.code, "engine-login"), listOf(NetworkGrant(origin)))
            session.configureSource(origin, cookiesEnabled = true)
            val result = session.execute(BrokerRequest("engine-login", login.loginUrl,
                headers = login.userAgent?.let { mapOf("User-Agent" to it) }.orEmpty(),
                browser = BrowserOptions(script = script, interactive = true, title = engine.name, waitForConfirmation = true)))
            val response = (result as? BrokerResult.Success)?.response ?: throw HttpSpeechException(HttpSpeechError.LoginRequired)
            val storage = Json.parseToJsonElement(response.text()).jsonObject.mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { key to it }
            }.toMap()
            engine.copy(parameters = applySpeechEngineLogin(engine.parameters, session.cookie(login.loginUrl), storage))
        }
    }
}
