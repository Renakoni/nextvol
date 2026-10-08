package indi.renakoni.nextvol

import android.app.Application
import android.content.Context
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import indi.renakoni.nextvol.data.logging.LogLevel
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceNetworkSettings
import indi.renakoni.nextvol.data.web.WebBookDataSourceManager
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import javax.inject.Inject

@HiltAndroidApp
class NextVolApplication : Application(), Configuration.Provider, coil3.SingletonImageLoader.Factory {
    @Inject lateinit var sourceImageInterceptor: indi.renakoni.nextvol.data.image.SourceImageInterceptor
    @Inject lateinit var importedRuleSources: indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
    @Inject lateinit var zLibrarySources: indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
    @Inject lateinit var localBooks: indi.renakoni.nextvol.data.localbook.LocalBookStore
    @Inject lateinit var bangumiSync: javax.inject.Provider<indi.renakoni.nextvol.data.bangumi.BangumiSyncScheduler>

    override fun newImageLoader(context: Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components {
            add(sourceImageInterceptor)
            add(indi.renakoni.nextvol.data.image.SourceImageFetcher.Factory())
            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = {
                okhttp3.OkHttpClient.Builder().addInterceptor(hnovel.network.DefaultUserAgentInterceptor()).build()
            }))
        }
        .build()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var loggerRepository: LoggerRepository
    @Inject lateinit var userDataRepository: UserDataRepository
    @Inject lateinit var webBookDataSourceManager: WebBookDataSourceManager
    @Inject lateinit var sourceNetworkSettings: SourceNetworkSettings
    @Inject lateinit var wenku8SearchSupport: indi.renakoni.nextvol.defaultplugin.wenku8.search.Wenku8SearchSupport

    override val workManagerConfiguration: Configuration
        get()  =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    private inline fun startupPhase(name: String, block: () -> Unit) {
        if (!BuildConfig.BENCHMARK) { block(); return }
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        android.util.Log.i("NextVolStartup", "begin phase=$name timeNs=$start")
        try { block() } finally {
            val end = android.os.SystemClock.elapsedRealtimeNanos()
            android.util.Log.i("NextVolStartup", "end phase=$name timeNs=$end durationNs=${end - start}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
    @ExperimentalSerializationApi
    override fun onCreate() {
        // Hilt's generated super.onCreate injects host repositories. An isolated service has
        // a different UID and must not initialize app files, WorkManager or sources.
        if (android.os.Process.myUid() != applicationInfo.uid) return
        val process = java.io.File("/proc/self/cmdline").inputStream().use { input ->
            input.readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
        }
        if (process.endsWith(":source_browser") || process.endsWith(":source_browser_native")) return
        startupPhase("host-injection") { super.onCreate() }
        // The new Compose text context menu asks MIUI's action mode to treat the
        // Compose root as a TextView, which leaves a stale "Select all" toolbar.
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
        }
        // Discovery treats the first registry snapshot as authoritative, including imported sources.
        // Async startup must add explicit registration readiness before exposing missing/empty states.
        startupPhase("sources") { runBlocking {
            startupPhase("builtin-source") {
                webBookDataSourceManager.loadBuiltInSource(
                    Wenku8Api(wenku8SearchSupport) { id -> sourceNetworkSettings.forSource(id).snapshot() },
                    SourceCategory.Anime,
                )
            }
            startupPhase("imported-sources") { importedRuleSources.restore() }
            startupPhase("zlibrary-source") { zLibrarySources.restore() }
        } }
        if (BuildConfig.BENCHMARK) {
            android.util.Log.i("NextVolStartup", "snapshot timeNs=${android.os.SystemClock.elapsedRealtimeNanos()} " +
                "count=${webBookDataSourceManager.registry.sources.value.size} " +
                "importedFailed=${importedRuleSources.restorationFailed} zlibraryFailed=${zLibrarySources.state.value.restorationFailed}")
        }
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { startupPhase("local-metadata") { localBooks.restoreMetadata() } }
                .onFailure { android.util.Log.e("LocalBookStore", "Cannot restore local library metadata", it) }
        }
        bangumiSync.get().start(coroutineScope)
        coroutineScope.launch(Dispatchers.IO) {
            loggerRepository.logLevel = LogLevel.from(userDataRepository.stringUserData(UserDataPath.Settings.Data.LogLevel.path).getOrDefault("none"))
            loggerRepository.startLogging()
        }
    }
}
