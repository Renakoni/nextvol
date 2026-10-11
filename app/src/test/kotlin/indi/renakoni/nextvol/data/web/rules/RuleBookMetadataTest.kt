package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.getOrElse
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.data.book.UNKNOWN_BOOK_UPDATE_TIME
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleBookMetadataTest {
    @Test fun foregroundInformationSkipsCatalogueWhileBackgroundStillChecksForUpdates(): Unit = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val adapter = RuleWebBookDataSource(Identifier("rules", "metadata"), source)
            val id = fixture.server.url("/book/one").toString()
            kotlinx.coroutines.withContext(indi.renakoni.nextvol.data.web.ForegroundSourceRequest()) {
                assertEquals("Same title", adapter.getBookInformation(id).getOrElse { error(it.toString()) }.title)
            }
            assertEquals(1, fixture.server.requestCount)
            adapter.getBookInformation(id).getOrElse { error(it.toString()) }
            assertEquals(4, fixture.server.requestCount)
            val requests = fixture.server.requestCount
            assertEquals(2, adapter.getBookVolumes(id).getOrElse { error(it.toString()) }.volumes.single().chapters.size)
            assertEquals(requests, fixture.server.requestCount)
        } }
    }

    @Test fun invalidRefreshKeepsLastValidMetadataAcrossRestartWithoutChangingRawValues(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            var words = "1.23万"
            var updated = "2026-09-14 08:00:00"
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setBody("<h1>Title</h1><b>Author</b><i>$words</i><time>$updated</time>")
            }
            fun definition(raw: JsonObject) = JsonObject(raw + ("ruleBookInfo" to
                JsonObject(raw.getValue("ruleBookInfo").jsonObject + mapOf(
                    "wordCount" to JsonPrimitive("i@text"), "updateTime" to JsonPrimitive("time@text")
                ))))
            val id = fixture.server.url("/book/one").toString()
            val information = fixture.source(customize = ::definition).use { source ->
                val adapter = RuleWebBookDataSource(Identifier("rules", "metadata"), source)
                adapter.getBookInformation(id).getOrElse { error(it.toString()) }
                words = "unknown"; updated = "2026-02-30"
                adapter.getBookInformation(id).getOrElse { error(it.toString()) }
            }
            val requests = fixture.server.requestCount
            fixture.source(customize = ::definition).use { source ->
                val adapter = RuleWebBookDataSource(Identifier("rules", "metadata"), source)
                val display = adapter.informationForDisplay(id, information)
                assertEquals(12300, display.wordCount.count)
                assertEquals(LocalDateTime.of(2026, 9, 14, 8, 0), display.lastUpdated)
                assertEquals("unknown", source.cachedInformation(id)!!.wordCount)
                assertEquals("2026-02-30", source.cachedInformation(id)!!.updateTime)
                assertEquals(requests, fixture.server.requestCount)
                words = "2万"; updated = "2026-09-15"
                val next = adapter.getBookInformation(id).getOrElse { error(it.toString()) }
                val nextDisplay = adapter.informationForDisplay(id, next)
                assertEquals(20000, nextDisplay.wordCount.count)
                assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0), nextDisplay.lastUpdated)
                // A discovery URL can return a detail directly, with no list rule.
                words = "unknown"; updated = "invalid"
                source.discovery(id)
                val afterDiscovery = adapter.informationForDisplay(id, next)
                assertEquals(20000, afterDiscovery.wordCount.count)
                assertEquals(nextDisplay.lastUpdated, afterDiscovery.lastUpdated)
            }
        }
    }

    private fun metadata(raw: JsonObject) = JsonObject(raw + ("ruleBookInfo" to
        JsonObject(raw.getValue("ruleBookInfo").jsonObject + mapOf(
            "wordCount" to JsonPrimitive("@js:'1.23万'"),
            "updateTime" to JsonPrimitive("@js:'2026-09-14 08:00:00'")
        ))))

    @Test fun displayUsesSourceDateWithoutChangingTheObservationMarkerOrFetching(): Unit = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = ::metadata).use { source ->
            val adapter = RuleWebBookDataSource(Identifier("rules", "metadata"), source)
            val id = fixture.server.url("/book/one").toString()
            val information = adapter.getBookInformation(id).getOrElse { error(it.toString()) }
            assertEquals(12300, information.wordCount.count)
            val observed = source.cachedInformation(id)!!.observedUpdate
            val requests = fixture.server.requestCount
            val display = adapter.informationForDisplay(id, information)
            assertEquals(LocalDateTime.of(2026, 9, 14, 8, 0), display.lastUpdated)
            assertEquals(information.wordCount, display.wordCount)
            assertEquals(requests, fixture.server.requestCount)
            assertEquals(observed, source.cachedInformation(id)!!.observedUpdate)
            assertEquals(information, adapter.getBookInformation(id).getOrElse { error(it.toString()) })
        } }
    }

    @Test fun absentMetadataDoesNotDisplayTheDirectoryObservationTime(): Unit = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val adapter = RuleWebBookDataSource(Identifier("rules", "metadata"), source)
            val id = fixture.server.url("/book/one").toString()
            val information = adapter.getBookInformation(id).getOrElse { error(it.toString()) }
            assertTrue(information.lastUpdated.year > 1970)
            val requests = fixture.server.requestCount
            val display = adapter.informationForDisplay(id, information)
            assertEquals(0, display.wordCount.count)
            assertEquals(UNKNOWN_BOOK_UPDATE_TIME, display.lastUpdated)
            assertEquals(requests, fixture.server.requestCount)
            fixture.extraChapter = true
            val updated = adapter.getBookInformation(id).getOrElse { error(it.toString()) }
            assertTrue(updated.lastUpdated.isAfter(information.lastUpdated))
            assertEquals(UNKNOWN_BOOK_UPDATE_TIME, adapter.informationForDisplay(id, updated).lastUpdated)
        } }
    }

    @Test fun persistedMetadataRepairsOldDisplayValuesWithoutNetworkAccess(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            val id = fixture.server.url("/book/one").toString()
            val information = fixture.source(customize = ::metadata).use { source ->
                RuleWebBookDataSource(Identifier("rules", "metadata"), source)
                    .getBookInformation(id).getOrElse { error(it.toString()) }
            }
            val requests = fixture.server.requestCount
            fixture.status = 503
            fixture.source(customize = ::metadata).use { source ->
                val display = RuleWebBookDataSource(Identifier("rules", "metadata"), source)
                    .informationForDisplay(id, information.copy(wordCount = WordCount(0)))
                assertEquals(12300, display.wordCount.count)
                assertEquals(LocalDateTime.of(2026, 9, 14, 8, 0), display.lastUpdated)
                assertEquals(requests, fixture.server.requestCount)
            }
        }
    }
}
