package indi.renakoni.nextvol.data.update

import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
        val candidates = mutableListOf<JSONObject>()
        var page = 1
        do {
            val url = apiBase.newBuilder()
                .addPathSegments("repos/${ProjectLinks.REPOSITORY}/releases")
                .addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build()
            val body = get(httpClient, url, allowMissing = true) ?: return null
            val releases = JSONArray(body)
            for (index in 0 until releases.length()) {
                val item = releases.getJSONObject(index)
                if (!item.getBoolean("draft") && !item.getBoolean("prerelease") &&
                    Regex("v[0-9]+[.][0-9]+[.][0-9]+").matches(item.getString("tag_name"))) {
                    candidates += item
                }
            }
            page++
        } while (releases.length() == 100)
        val json = candidates.maxWithOrNull(compareBy<JSONObject>(
            { it.getString("tag_name").drop(1).split('.')[0].toLong() },
            { it.getString("tag_name").drop(1).split('.')[1].toLong() },
            { it.getString("tag_name").drop(1).split('.')[2].toLong() },
        )) ?: return null
        val tag = json.getString("tag_name")
        updatePhase.value = UpdatePhase(R.string.update_phase_github_download_link)
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        // The release contract is one universal APK, avoiding arbitrary ABI/build selection.
        if (apks.size != 1) throw IOException("Expected one NextVol release APK, found ${apks.size}")
        val asset = apks.single()
        val fileName = "NextVol-${tag.drop(1)}-release.apk"
        val downloadUrl = asset.getString("browser_download_url").toHttpUrl()
        if (asset.getString("name") != fileName ||
            downloadUrl.toString() != "${ProjectLinks.GITHUB}/releases/download/$tag/$fileName") {
            throw IOException("APK does not belong to ${ProjectLinks.REPOSITORY}")
        }
        val digest = asset.getString("digest")
        if (!Regex("sha256:[a-f0-9]{64}").matches(digest)) {
            throw IOException("Invalid GitHub APK digest")
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
        if (version <= 0 || "v$versionName" != tag) throw IOException("Release version does not match its tag")
        return Release(version, versionName, json.optString("body", ""), downloadUrl.toString(), digest.removePrefix("sha256:"))
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
