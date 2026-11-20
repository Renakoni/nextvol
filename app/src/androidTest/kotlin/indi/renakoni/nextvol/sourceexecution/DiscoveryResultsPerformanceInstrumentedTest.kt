package indi.renakoni.nextvol.sourceexecution

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.NextVolApplication
import indi.renakoni.nextvol.data.web.SourceSessionManager
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.sourcebrowser.SourceVerificationDebugEntryPoint
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.ui.home.discovery.*
import io.nightfish.lightnovelreader.api.Route
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Opt-in timing of an installed source through the actual result ViewModel and Compose screen.
 * Reports contain counts and timings only. Display timing includes test synchronization, not just a frame. */
@RunWith(AndroidJUnit4::class)
class DiscoveryResultsPerformanceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()

    @Test fun measureEntryToDisplayedBooks() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("discoveryUiPerformance") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NextVolApplication
        val sources = app.importedRuleSources
        val source = runBlocking { sources.restore(); sources.installedSources() }.map { ImportedRuleSources.id(it.definition) }
            .single { it.id == args.getString("discoveryUiSource") }
        val target = requireNotNull(args.getString("discoveryUiTarget"))
        val repeats = (args.getString("discoveryRepeats") ?: "2").toInt().also { require(it in 1..5) }
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(app, SourceVerificationDebugEntryPoint::class.java)
        // The diagnostic never changes accounts; the registry supplies the installed account generation.
        val accounts = SourceSessionManager()
        var current by mutableStateOf<DiscoveryResultsViewModel?>(null)
        compose.setContent {
            NextVolTheme("Disabled", false, "light_default", "dark_default") {
                current?.let { model -> key(model) {
                    val state by model.state.collectAsState()
                    DiscoveryResultsScreen(state, model::filter, model::loadMore, model::refresh,
                        model::scroll, {}, {}, {}, {})
                } }
            }
        }
        val reports = mutableListOf<JsonObject>()
        for (iteration in 0..repeats) {
            val previewStarted = SystemClock.elapsedRealtimeNanos()
            val previewId = args.getString("discoveryUiPreviewId")
            if (previewId != null) runBlocking {
                val home = requireNotNull(entry.registry().discovery(source).get()).forSession("ui-home-$iteration")
                assertTrue(home.homepageCatalog(refresh = true).get() != null)
                val preview = requireNotNull(home.preview(previewId).get())
                assertTrue(preview.previewFailure == null && preview.books.size == 6)
            }
            val previewMillis = (SystemClock.elapsedRealtimeNanos() - previewStarted) / 1_000_000.0
            val dataReady = AtomicLong()
            val started = SystemClock.elapsedRealtimeNanos()
            lateinit var model: DiscoveryResultsViewModel
            compose.runOnIdle {
                model = DiscoveryResultsViewModel(entry.registry(), accounts, SavedStateHandle(),
                    Route.Main.DiscoveryResults(source.namespace, source.id, target, "Discovery", "ui-$iteration"))
                current = model
                model.viewModelScope.launch {
                    model.state.first { it.loaded || it.error != null }
                    dataReady.set(SystemClock.elapsedRealtimeNanos())
                }
                model.setActive(true)
            }
            var displayed: Long? = null
            var failure: String? = null
            try {
                compose.waitUntil(45_000) { model.state.value.loaded || model.state.value.error != null }
                val page = model.state.value
                failure = page.error?.name ?: if (page.books.isEmpty()) "Empty" else null
                if (failure == null) {
                    compose.onAllNodesWithText(page.books.first().title).onFirst().assertIsDisplayed()
                    displayed = SystemClock.elapsedRealtimeNanos()
                }
            } catch (error: Exception) { failure = error.javaClass.simpleName }
            finally { compose.runOnIdle { model.setActive(false); model.viewModelScope.cancel(); current = null } }
            val report = buildJsonObject {
                put("label", args.getString("discoveryLabel") ?: "local")
                put("iteration", iteration); put("sample", if (iteration == 0) "first" else "repeat")
                put("books", model.state.value.books.size)
                put("result", failure ?: "Success")
                if (previewId != null) put("previewWarmupMs", previewMillis)
                dataReady.get().takeIf { it > 0 }?.let { put("dataReadyMs", (it - started) / 1_000_000.0) }
                displayed?.let { put("booksDisplayedByMs", (it - started) / 1_000_000.0) }
            }
            reports += report
            File(app.filesDir, "discovery-ui-results.json").writeText(JsonArray(reports).toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("discoveryUiPerformance", report.toString()) })
        }
        assertTrue("Inspect each diagnostic result separately", reports.isNotEmpty())
    }
}
