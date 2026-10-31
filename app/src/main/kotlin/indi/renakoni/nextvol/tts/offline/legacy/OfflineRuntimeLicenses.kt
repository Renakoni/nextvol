package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import org.json.JSONObject
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException

/** Runtime-supplied SDK data, with optional application-private overrides for local validation. */
internal fun offlineRuntimeLicenses(context: Context): JSONObject {
    val bundled = try { context.assets.open("offline-native/runtime-licenses.json").bufferedReader().use { it.readText() } }
        catch (_: FileNotFoundException) { "{}" }
    val file = File(context.filesDir, "offline-runtime-licenses.json")
    return try {
        JSONObject(bundled).also { data ->
            if (file.isFile) {
                val overrides = JSONObject(file.readText())
                overrides.keys().forEach { key -> data.put(key, overrides.get(key)) }
            }
        }
    } catch (_: JSONException) { error("Offline runtime license data is not valid JSON") }
}
