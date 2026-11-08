package indi.renakoni.nextvol.data.update

import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object NextvolApiParser : UpdateParser {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    override fun parser(updatePhase: MutableStateFlow<UpdatePhase>): Release? {
        updatePhase.value = UpdatePhase(R.string.update_phase_nextvol_api)
        return fetchRelease(client, ProjectLinks.UPDATE_API.toHttpUrl())
    }

    internal fun fetchRelease(httpClient: OkHttpClient, endpoint: HttpUrl): Release? {
        val request = Request.Builder().url(endpoint).header("User-Agent", "NextVol")
            .header("Cache-Control", "no-cache").build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Nextvol api HTTP ${response.code}")
            val json = JSONObject(response.body.string())
            val name = json.getString("versionName")
            val tag = json.getString("tag")
            val version = json.getInt("versionCode")
            val hash = json.getString("sha256")
            val url = json.getString("downloadUrl").toHttpUrl()
            if (json.getInt("schemaVersion") != 1 ||
                json.getString("packageName") != "indi.renakoni.nextvol" ||
                !Regex("[0-9]+[.][0-9]+[.][0-9]+").matches(name) || tag != "v$name" ||
                version <= 0 || !Regex("[a-f0-9]{64}").matches(hash) ||
                url.toString() != "${ProjectLinks.DOWNLOADS}/releases/$tag/NextVol-$name-release.apk") {
                throw IOException("Invalid Nextvol api release metadata")
            }
            Release(version, name, json.getString("releaseNotes"), url.toString(), hash)
        }
    }
}
