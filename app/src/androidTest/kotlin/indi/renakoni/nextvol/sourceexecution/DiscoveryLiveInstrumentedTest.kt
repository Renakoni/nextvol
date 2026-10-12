package indi.renakoni.nextvol.sourceexecution

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.SourceContentException
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.NextVolApplication
import indi.renakoni.nextvol.ui.home.discovery.discovery
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.sourcebrowser.SourceVerificationDebugEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in device diagnostic. Input/grants are supplied locally; reports omit URLs, cookies and book text. */
@RunWith(AndroidJUnit4::class)
class DiscoveryLiveInstrumentedTest {
    @Test fun compareSearchCatalogAndHomepageInInstalledApp(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveDiscovery") == "true")
        val application = instrumentation.targetContext.applicationContext as NextVolApplication
        val sources = application.importedRuleSources
        sources.restore()
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(application, SourceVerificationDebugEntryPoint::class.java)
        val input = File(application.filesDir, "discovery-audit-input.json")
        require(input.length() in 1..2_000_000)
        val cases = Json.parseToJsonElement(input.readText()).jsonArray
        require(cases.size in 1..20)
        val reports = mutableListOf<JsonObject>()
        val output = File(application.filesDir, "discovery-audit-results.json")
        val temporarySources = mutableListOf<io.nightfish.lightnovelreader.api.identifier.Identifier>()
        try {
            for (value in cases) {
                val case = value.jsonObject
                val raw = case.getValue("source").jsonObject
                val name = raw.getValue("bookSourceName").jsonPrimitive.content
                suspend fun measure(stage: String, block: suspend () -> JsonObject) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    android.util.Log.i("DiscoveryAudit", "start $name $stage")
                    val result = try { withTimeout(when (stage) {
                        "verification" -> 300_000
                        "directory" -> 180_000
                        else -> 45_000
                    }) { block() } }
                    catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        buildJsonObject {
                            put("error", (error as? SourceContentException)?.code?.name ?: error.javaClass.simpleName)
                            (error as? SourceContentException)?.let {
                                put("field", it.field)
                                it.verification?.kind?.let { kind -> put("verificationKind", kind.name) }
                                it.denial?.let { denial -> put("deniedOrigin", denial.origin) }
                                it.diagnostic?.let { diagnostic -> put("workerCode", diagnostic.code.name) }
                                it.verification?.certificate?.let { certificate -> put("certificateIssue", certificate.issue.name) }
                            }
                        }
                    }
                    val report = JsonObject(result + mapOf("source" to JsonPrimitive(name), "stage" to JsonPrimitive(stage),
                        "elapsedMs" to JsonPrimitive(android.os.SystemClock.elapsedRealtime() - started)))
                    reports += report
                    output.writeText(JsonArray(reports).toString())
                    instrumentation.sendStatus(0, Bundle().apply { putString("discoveryAudit", report.toString()) })
                }
                var identity: io.nightfish.lightnovelreader.api.identifier.Identifier? = null
                measure("install") {
                    val preview = sources.importer.preview(raw.toString())
                    check(preview.issues.isEmpty()) { "Import rejected" }
                    val candidate = preview.candidates.single()
                    val installed = sources.installedSources().singleOrNull {
                        it.definition.importKey == candidate.importKey && it.definition.profile == candidate.profile
                    }
                    // An audit may replace an inactive sample from an earlier run. Never silently
                    // run that stale definition, or overwrite a user's active edited source.
                    if (installed != null) check(installed.definition.rawJson == candidate.rawJson) { "Active definition differs from audit input" }
                    val reference = installed?.definition?.reference() ?: sources.importer.commit(preview,
                        listOf(ImportSelection(candidate.index, candidate.existing?.let { ImportDecision.Replace(it) }
                            ?: ImportDecision.Add))).items.single().reference!!
                    val grants = case.getValue("origins").jsonArray.map { NetworkGrant(it.jsonPrimitive.content) }
                    identity = if (installed != null) ImportedRuleSources.id(installed.definition).also { id ->
                        val approved = (installed.origins + grants).distinctBy { it.origin }
                        if (approved != installed.origins) entry.revisions().updatePermissions(id, approved)
                    } else {
                        sources.activate(reference, grants).also {
                            if (case["cleanup"] == JsonPrimitive(true)) temporarySources += it
                            sources.setPreferences(it, enabled = true, discoveryVisible = true)
                        }
                    }
                    buildJsonObject { put("status", "ready"); put("id", identity.id); put("revision", reference.revision) }
                }
                val id = identity ?: continue
                if (args.getString("discoveryAction") == "install") continue
                val source = sources.loginTarget(id).rules
                case["loginValues"]?.jsonObject?.let { values ->
                    measure("login") {
                        source.loginForm()
                        source.login(values.mapValues { it.value.jsonPrimitive.content },
                            case["loginAction"]?.jsonPrimitive?.content)
                        buildJsonObject { put("status", "submitted") }
                    }
                }
                if (args.getString("discoveryAction") == "login") {
                    androidx.test.core.app.ActivityScenario.launch(
                        indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity::class.java).use {
                        source.loginForm()
                        source.login(emptyMap())
                    }
                    continue
                }
                suspend fun readChapter(bookId: String, chapterId: String, vip: Boolean) = buildJsonObject {
                    val content = source.content(bookId, chapterId)
                    val text = content.parts.mapNotNull { it.text }.joinToString("\n")
                    put("chars", text.length); put("images", content.parts.count { it.image != null })
                    put("privateUseChars", text.count { it in '\uE000'..'\uF8FF' }); put("vip", vip)
                    put("textDigest", java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                        .joinToString("") { "%02x".format(it) })
                }
                // A targeted retry must not precede the chapter with another burst of list requests.
                case["chapterUrl"]?.jsonPrimitive?.content?.let { chapterId ->
                    val bookId = case.getValue("bookUrl").jsonPrimitive.content
                    var verification: hnovel.content.SourceVerification? = null
                    measure("content:requested") {
                        try { readChapter(bookId, chapterId, false) }
                        catch (failure: SourceContentException) { verification = failure.verification; throw failure }
                    }
                    if (args.getString("discoveryAction") == "verifyContent") verification?.let { challenge ->
                        check(challenge.certificate == null) { "Certificate exceptions require the normal approval UI" }
                        androidx.test.core.app.ActivityScenario.launch(
                            indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity::class.java).use {
                            measure("verification") {
                                challenge.complete()
                                buildJsonObject { put("status", "completed") }
                            }
                            measure("content:afterVerification") { readChapter(bookId, chapterId, false) }
                        }
                    }
                }
                if (case["chapterUrl"] != null) continue
                var searchBook: String? = null
                var discoveryBook: String? = null
                measure("search") {
                    val books = source.search(case["keyword"]?.jsonPrimitive?.content ?: "斗罗", 1)
                    searchBook = books.firstOrNull()?.id
                    buildJsonObject { put("books", books.size) }
                }
                val provider = entry.registry().discovery(id).get()
                if (provider == null) {
                    measure("catalog") { buildJsonObject { put("error", "Unsupported") } }
                    continue
                }
                val discovery = provider.forSession("device-audit-${id.id}")
                var categories = emptyList<indi.renakoni.nextvol.data.web.SourceDiscoveryCategory>()
                measure("catalog") {
                    val result = discovery.catalog()
                    result.get()?.let { catalog ->
                        categories = catalog.categories
                        buildJsonObject {
                            put("entries", categories.size); put("controls", catalog.filters.size); put("buttons", catalog.buttons.size)
                            put("firstTitles", JsonArray(categories.take(8).map { JsonPrimitive(it.title) }))
                        }
                    } ?: buildJsonObject {
                        put("error", result.getError().toString()); put("field", discovery.failureField)
                        put("deniedOrigin", discovery.permissionFailure?.origin)
                    }
                }
                measure("feed") {
                    val result = discovery.feed()
                    result.get()?.let { sections -> buildJsonObject {
                        discoveryBook = sections.firstNotNullOfOrNull { it.books.firstOrNull()?.id?.remoteId }
                        put("sections", sections.size); put("previewBooks", sections.sumOf { it.books.size })
                        put("previewFailures", JsonArray(sections.mapNotNull { section -> section.previewFailure?.let { failure ->
                            buildJsonObject {
                                put("title", section.title); put("error", failure.error.name); put("field", failure.field)
                                put("deniedOrigin", failure.permission?.origin)
                            }
                        } }))
                    } } ?: buildJsonObject {
                        put("error", result.getError().toString()); put("field", discovery.failureField)
                        put("deniedOrigin", discovery.permissionFailure?.origin)
                    }
                }
                for (category in categories.filter { it.target.target.isNotBlank() }.take(2)) {
                    val page = discovery.open(category.target)
                    var firstCount = 0
                    var hasMore = false
                    measure("entry:${category.title}") {
                        val result = page.loadMore()
                        result.get()?.let {
                            if (discoveryBook == null) discoveryBook = it.books.firstOrNull()?.id?.remoteId
                            firstCount = it.books.size; hasMore = it.nextCursor != null
                            buildJsonObject { put("books", firstCount); put("hasMore", hasMore) }
                        } ?: buildJsonObject {
                            put("error", result.getError().toString()); put("field", page.failureField)
                            put("deniedOrigin", page.permissionFailure?.origin)
                        }
                    }
                    if (hasMore && case["checkPagination"] == JsonPrimitive(true)) measure("page2:${category.title}") {
                        val result = page.loadMore()
                        result.get()?.let { buildJsonObject {
                            put("books", it.books.size); put("newBooks", it.books.size - firstCount); put("hasMore", it.nextCursor != null)
                        } } ?: buildJsonObject {
                            put("error", result.getError().toString()); put("field", page.failureField)
                            put("deniedOrigin", page.permissionFailure?.origin)
                        }
                    }
                }
                if (case["readBooks"] == JsonPrimitive(true)) {
                    val bookId = case["bookUrl"]?.jsonPrimitive?.content ?: discoveryBook ?: searchBook
                    if (bookId == null) measure("information") { buildJsonObject { put("error", "NoSampleBook") } }
                    else {
                        measure("information") {
                            val book = source.information(bookId, inferUpdate = false)
                            buildJsonObject { put("titlePresent", book.title.isNotBlank()); put("identityPreserved", book.id == bookId) }
                        }
                        var chapters = emptyList<hnovel.content.RuleChapter>()
                        measure("directory") {
                            chapters = source.directory(bookId).filterNot { it.isVolume }
                            buildJsonObject { put("chapters", chapters.size); put("vipChapters", chapters.count { it.isVip || it.isPay }) }
                        }
                        val firstFree = chapters.indexOfFirst { !it.isVip && !it.isPay }.coerceAtLeast(0)
                        for (index in listOf(firstFree, chapters.size / 2, chapters.lastIndex).distinct().filter { it in chapters.indices }) {
                            val chapter = chapters[index]
                            measure("content:$index") {
                                readChapter(bookId, chapter.id, chapter.isVip || chapter.isPay)
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { temporarySources.forEach { sources.remove(it) } }
        }
        // This diagnostic records individual failures; a successful runner is not a source pass rate.
        assertTrue(reports.isNotEmpty())
    }
}
