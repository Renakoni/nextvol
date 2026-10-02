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

/** Only published, stable releases of NextVol are eligible for app updates. */
object GithubParser : UpdateParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    data class GithubRelease(
        override val version: Int,
        override val versionName: String,
        override val releaseNotes: String,
        override val downloadUrl: String,
    ) : Release

    override fun parser(updatePhase: MutableStateFlow<UpdatePhase>): Release? = fetchRelease(
        updatePhase, client, "https://api.github.com/".toHttpUrl(),
        "https://raw.githubusercontent.com/".toHttpUrl(),
    )

    internal fun fetchRelease(
        updatePhase: MutableStateFlow<UpdatePhase>,
        httpClient: OkHttpClient,
        apiBase: HttpUrl,
        rawBase: HttpUrl,
    ): Release? {
        updatePhase.value = UpdatePhase(R.string.update_phase_github_release)
        val latest = apiBase.newBuilder()
            .addPathSegments("repos/${ProjectLinks.REPOSITORY}/releases/latest").build()
        val body = get(httpClient, latest, allowMissing = true) ?: return null
        val json = JSONObject(body)
        if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
        val tag = json.getString("tag_name")
        updatePhase.value = UpdatePhase(R.string.update_phase_github_download_link)
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        // The release contract is one universal APK, avoiding arbitrary ABI/build selection.
        if (apks.size != 1) throw IOException("Expected one NextVol release APK, found ${apks.size}")
        val downloadUrl = apks.single().getString("browser_download_url").toHttpUrl()
        if (downloadUrl.scheme != "https" || downloadUrl.host != "github.com" ||
            downloadUrl.port != 443 || downloadUrl.username.isNotEmpty() || downloadUrl.password.isNotEmpty() ||
            downloadUrl.pathSegments.take(5) != listOf("Renakoni", "nextvol", "releases", "download", tag)) {
            throw IOException("APK does not belong to ${ProjectLinks.REPOSITORY}")
        }

        updatePhase.value = UpdatePhase(R.string.update_phase_github_version)
        val gradleUrl = rawBase.newBuilder().addPathSegments(ProjectLinks.REPOSITORY)
            .addPathSegments("refs/tags").addPathSegment(tag)
            .addPathSegments("app/build.gradle.kts").build()
        val gradle = get(httpClient, gradleUrl)!!
        val version = Regex("(?m)^\\s*versionCode\\s*=\\s*([0-9_]+)\\s*(?://.*)?$")
            .find(gradle)?.groupValues?.get(1)?.replace("_", "")?.toIntOrNull()
            ?: throw IOException("Release tag has no valid versionCode")
        val versionName = Regex("(?m)^\\s*versionName\\s*=\\s*\"([^\"]+)\"")
            .find(gradle)?.groupValues?.get(1)
            ?: throw IOException("Release tag has no valid versionName")
        return GithubRelease(version, versionName, json.optString("body", ""), downloadUrl.toString())
    }

    private fun get(client: OkHttpClient, url: HttpUrl, allowMissing: Boolean = false): String? {
        val request = Request.Builder().url(url).header("User-Agent", "NextVol")
            .header("Accept", "application/vnd.github+json").build()
        return client.newCall(request).execute().use { response ->
            if (allowMissing && response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            response.body.string()
        }
    }
}
