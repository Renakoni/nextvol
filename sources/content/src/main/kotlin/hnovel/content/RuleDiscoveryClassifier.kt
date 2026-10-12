package hnovel.content

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.util.Locale

internal data class RuleDiscoveryCapabilities(val hasFeed: Boolean, val hasCategories: Boolean)

/** Catalogue semantics only. Targets, list rules, ordering and page execution belong to the source. */
object RuleDiscoveryClassifier {
    private const val MAX_AUTOMATIC_PREVIEWS = 8

    fun feed(catalog: RuleDiscoveryCatalog): List<RuleDiscoveryRow> {
        // An explicit empty homepage also opts out of inference. The complete catalogue remains tags.
        catalog.homepage?.let { return it }
        var heading = ""
        return catalog.rows.mapNotNull { row ->
            when {
                row.type != "url" -> null
                row.url.isBlank() -> { heading = row.title; null }
                isFeedTitle(row.title) -> row
                isFeedTitle(heading) && row.title.isNotBlank() -> row.copy(title = "$heading · ${row.title}")
                else -> null
            }
        }.take(MAX_AUTOMATIC_PREVIEWS)
    }

    internal fun capabilities(spec: RuleSourceDefinition): RuleDiscoveryCapabilities {
        val rows = staticRows(spec.exploreUrl, "exploreUrl")
        val controls = staticRows(spec.exploreScreen, "exploreScreen")
        val categories = rows == null || controls == null ||
            (rows + controls).any { if (it.type == "url") it.showInCategories && it.url.isNotBlank() else it.targetPrefixes.isEmpty() }
        val feed = if (spec.homepageModules.isNotBlank()) {
            // Invalid or unresolved declarations retain a tab so the runtime can report their field.
            try {
                val modules = RuleDiscoveryCatalogParser.homepageModules(spec.homepageModules)
                // Optional categories are unknown until their settings-dependent script runs.
                if (rows == null && modules?.any { it.jsonObject.string("url").isBlank() } == true) true
                else RuleDiscoveryCatalogParser.homepage(modules, rows.orEmpty()).orEmpty().isNotEmpty()
            } catch (_: SourceContentException) { true }
            catch (_: IllegalArgumentException) { true }
        } else rows == null || (rows.any { it.type == "url" && it.viewName.isNotBlank() } &&
            rows.any { it.type == "url" && it.url.isNotBlank() }) ||
            feed(RuleDiscoveryCatalog(rows, emptyMap())).isNotEmpty()
        return RuleDiscoveryCapabilities(feed, categories)
    }

    private fun staticRows(rule: String, field: String): List<RuleDiscoveryRow>? {
        // Source listing must not run JavaScript, request a website or depend on a temporary account result.
        if (rule.trimStart().let { it.startsWith("@js:", true) || it.startsWith("<js>", true) }) return null
        return try { RuleDiscoveryCatalogParser.rows(JsonPrimitive(rule), field) }
        catch (_: SourceContentException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    private fun isFeedTitle(title: String): Boolean {
        val text = Normalizer.normalize(title.replace(markup, ""), Normalizer.Form.NFKC)
            .filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
        return chineseFeed.containsMatchIn(text) || englishFeed.matches(text)
    }

    private val markup = Regex("<[^>]*>")
    private val chineseFeed = Regex("最新|更新|新书|上架|入库|热门|最热|热销|畅销|人气|推荐|排行|榜单|" +
        "点击|收藏|月票|(?:日|周|月|年|总|完本|完结|评分|字数|潜力|搜索|评论|阅读|必读|鲜花|打赏|订阅|粉丝)榜")
    private val englishFeed = Regex("recent(?:updates?)?|latest(?:books|chapters|updates)?|new(?:books|releases|arrivals)?|" +
        "popular|hot|rank(?:ing|ings)?|recommend(?:ed|ations)?|bestsellers?|top(?:books|rated)?")
}
