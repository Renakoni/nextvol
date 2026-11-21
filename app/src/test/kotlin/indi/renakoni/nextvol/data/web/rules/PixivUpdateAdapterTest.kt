package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import hnovel.imports.*
import indi.renakoni.nextvol.data.web.SourceCatalog
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal fun pixivUpdateFixture(name: String = "pixiv-v284.json"): JsonObject =
    PixivUpdateAdapterTest::class.java.getResourceAsStream("/source-updates/$name")!!.bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonObject
    }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class PixivUpdateAdapterTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalog = SourceCatalog(RuntimeEnvironment.getApplication())
    private val adapter = PixivUpdateAdapter(catalog)
    private fun importer() = SourceDefinitionImporter(SourceDefinitionStore(folder.newFolder().toPath()))
    private fun adapted() = Json.parseToJsonElement(catalog.definitions(setOf(PixivUpdateAdapter.KEY))).jsonArray.single().jsonObject

    @Test fun knownUpstreamMapsToTheEntireMaintainedAdaptation() {
        val importer = importer()
        val original = pixivUpdateFixture()
        assertTrue(original.toString().contains("备份恢复"))
        val preview = importer.preview(original.toString(), EXTENSION_PROFILE)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        val result = adapter.adapt(preview)
        assertEquals(catalog.definitions(setOf(PixivUpdateAdapter.KEY)), result)
        listOf("调试模式", "备份恢复", "发送评论", "反馈问题", "updateSourceHtml", "updateSourceLink").forEach {
            assertFalse(it, result.contains(it))
        }
        listOf("阅读与搜索", "发现页设置", "屏蔽管理").forEach { assertTrue(it, result.contains(it)) }
        assertEquals(adapted(), Json.parseToJsonElement(result).jsonArray.single())
    }

    @Test fun reorderingJsonKeysDoesNotInvalidateAReviewedRelease() {
        val original = pixivUpdateFixture()
        val reversed = JsonObject(original.entries.reversed().associate { it.key to it.value })
        assertEquals(adapter.adapt(importer().preview(original.toString(), EXTENSION_PROFILE)),
            adapter.adapt(importer().preview(reversed.toString(), EXTENSION_PROFILE)))
    }

    @Test fun changedCodeOrVersionIsRejectedBeforeExecution() {
        val original = pixivUpdateFixture()
        val mutations = listOf(
            original + ("jsLib" to JsonPrimitive(original.getValue("jsLib").jsonPrimitive.content + "\nvar unreviewed = true;")),
            original + ("loginUi" to JsonPrimitive("@js:throw new Error('must never execute');")),
            original + ("lastUpdateTime" to JsonPrimitive(original.getValue("lastUpdateTime").jsonPrimitive.long + 1)),
            original + ("ruleContent" to JsonObject(original.getValue("ruleContent").jsonObject + ("content" to JsonPrimitive("@js:sendComment()"))))
        )
        mutations.forEach { raw ->
            val failure = runCatching { adapter.adapt(importer().preview(JsonObject(raw).toString(), EXTENSION_PROFILE)) }.exceptionOrNull()
            assertEquals(RevisionError.UpstreamNotAdapted, (failure as RevisionException).code)
        }
    }

    @Test fun duplicateIdentityCannotSilentlyChooseTheFirstEntry() {
        val original = pixivUpdateFixture()
        val preview = importer().preview(JsonArray(listOf(original, original)).toString(), EXTENSION_PROFILE)
        val failure = runCatching { adapter.adapt(preview) }.exceptionOrNull() as RevisionException
        assertEquals(RevisionError.UpstreamNotAdapted, failure.code)
    }

}
