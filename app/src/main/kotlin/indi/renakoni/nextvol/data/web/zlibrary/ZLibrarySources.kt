package indi.renakoni.nextvol.data.web.zlibrary

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.*
import hnovel.network.SourceSession
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class ZLibrarySettings(val enabled: Boolean = true,
    val origin: String = "https://z-lib.gd:443",
    val origins: List<String> = ZLibrarySources.MIRRORS + "https://s3proxy-alp2-covers.cdn-zlib.sk:443") {
    val available get() = enabled && origin in origins
}
data class ZLibraryState(val settings: ZLibrarySettings = ZLibrarySettings(),
    val denied: List<OriginDenial> = emptyList(), val restorationFailed: Boolean = false)

/** One built-in identity with host-approved origins; changing mirrors never changes book IDs. */
@Singleton
class ZLibrarySources @Inject constructor(@ApplicationContext private val context: Context,
    private val registry: WebSourceRegistry, private val cipher: StorageCipher,
    private val networkSettings: SourceNetworkSettings? = null) {
    private val directory = File(context.filesDir, "native-sources/zlibrary")
    private val snapshot = AtomicFile(File(directory, "settings.json"))
    private val lock = Mutex()
    private var restored = false
    private var registration: SourceRegistration? = null
    private var broker: SourceBroker? = null
    private var session: SourceSession? = null
    private val mutable = MutableStateFlow(ZLibraryState())
    val state = mutable.asStateFlow()

    suspend fun restore() = withContext(Dispatchers.IO) { lock.withLock {
        if (restored) return@withLock
        val settings = try {
            snapshot.openRead().use {
                require(it.channel.size() <= 32768)
                validate(Json.decodeFromString<ZLibrarySettings>(it.readBytes().toString(Charsets.UTF_8)))
            }
        } catch (_: java.io.FileNotFoundException) { ZLibrarySettings() }
        catch (_: Exception) {
            mutable.value = ZLibraryState(ZLibrarySettings(enabled = false), restorationFailed = true)
            restored = true
            return@withLock
        }
        if (settings.available) {
            lateinit var restoredRegistration: SourceRegistration
            restoredRegistration = registry.register(METADATA) {
                val nextBroker = SourceBroker(File(directory, "runtime").toPath(), cipher = cipher,
                    limits = BrokerLimits(concurrency = 3, minIntervalMillis = 350), route = networkSettings?.forSource(ID))
                try {
                    val nextSession = nextBroker.open(SourceScope(ID.namespace, ID.id, "zlibrary-eapi"), settings.origins.map { NetworkGrant(it) })
                    val source = ZLibrarySource(context, nextSession, ZLibraryClient(nextSession, settings.origin))
                    currentCoroutineContext().ensureActive()
                    lock.withLock {
                        check(registration === restoredRegistration) { "Source registration changed during preparation" }
                        broker = nextBroker; session = nextSession
                        source
                    }
                } catch (failure: Throwable) { nextBroker.close(); throw failure }
            }
            registration = restoredRegistration
        }
        mutable.value = ZLibraryState(settings)
        restored = true
    } }

    suspend fun refresh() {
        restore()
        lock.withLock { mutable.value = mutable.value.copy(denied = session?.deniedOrigins.orEmpty()) }
    }

    suspend fun update(settings: ZLibrarySettings) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val next = validate(settings)
            if (next == mutable.value.settings && !mutable.value.restorationFailed) return@withLock
            val before = mutable.value.settings
            var saved = false
            try { bind(next) { save(next); saved = true } }
            catch (failure: Exception) {
                if (saved) save(before)
                throw failure
            }
        }
    }

    private fun bind(settings: ZLibrarySettings, persist: () -> Unit) {
        val previousBroker = broker
        if (!settings.available) {
            persist()
            registration?.unregister()
            registration = null; broker = null; session = null
        } else {
            val nextBroker = SourceBroker(File(directory, "runtime").toPath(), cipher = cipher,
                limits = BrokerLimits(concurrency = 3, minIntervalMillis = 350), route = networkSettings?.forSource(ID))
            try {
                val nextSession = nextBroker.open(SourceScope(ID.namespace, ID.id, "zlibrary-eapi"), settings.origins.map { NetworkGrant(it) })
                val source = ZLibrarySource(context, nextSession, ZLibraryClient(nextSession, settings.origin))
                registration = registration?.let { registry.replace(it, source, METADATA, persist = persist) }
                    ?: run { persist(); registry.register(source, METADATA) }
                broker = nextBroker; session = nextSession
            } catch (failure: Exception) { nextBroker.close(); throw failure }
        }
        previousBroker?.close()
        mutable.value = ZLibraryState(settings)
    }

    private fun save(settings: ZLibrarySettings) {
        directory.mkdirs()
        val output = snapshot.startWrite()
        try { output.write(Json.encodeToString(settings).toByteArray(Charsets.UTF_8)); snapshot.finishWrite(output) }
        catch (failure: Exception) { snapshot.failWrite(output); throw failure }
    }

    internal suspend fun stop() = lock.withLock {
        registration?.unregister(); broker?.close()
        registration = null; broker = null; session = null
    }

    companion object {
        val ID = Identifier("builtin", "zlibrary")
        val MIRRORS = listOf("https://z-lib.gd:443", "https://z-lib.fo:443", "https://library-asia.sk:443")
        val METADATA = SourceMetadata(WebDataSourceItem(ID, "Z-Library", "Z-Library"),
            setOf(SourceCapability.Search, SourceCapability.BookInformation, SourceCapability.Images), builtIn = true,
            category = SourceCategory.General)

        private fun validate(settings: ZLibrarySettings): ZLibrarySettings {
            fun origin(value: String): String {
                val url = requireNotNull(value.trim().toHttpUrlOrNull())
                require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null)
                return requireNotNull(sourceOrigin(url.toString()))
            }
            require(settings.origins.size <= 32)
            return settings.copy(origin = origin(settings.origin), origins = settings.origins.map(::origin).distinct())
        }
    }
}
