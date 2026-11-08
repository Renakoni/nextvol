package hnovel.content

import hnovel.rules.*
import kotlinx.serialization.json.*

internal suspend fun bookFields(context: RuleEvaluation, input: RuleValue, rules: JsonObject, prefix: String, seed: RuleBook,
    overview: Boolean = false, extracted: Map<String, String>? = null): RuleBook {
    val priorTitle = context.book.metadata["name"]?.jsonPrimitive?.content ?: seed.title
    val priorAuthor = context.book.metadata["author"]?.jsonPrimitive?.content ?: seed.author
    // BookList creates an empty SearchBook before evaluating its first field.
    context.bookField("name", priorTitle)
    suspend fun field(name: String, prior: String, metadata: String = name): String {
        val rule = rules.string(name)
        val valueFromRule = extracted?.getValue(name) ?: try {
            if (name == "kind" && rule.isNotBlank()) context.value(rule, input, "$prefix.$name", OutputKind.TextList)
                .items().joinToString(",") { it.text() }
            else context.text(rule, input, "$prefix.$name")
        } catch (failure: SourceContentException) {
            // BookList/BookInfo tolerate optional metadata errors. Keep the trace, and
            // still surface cancellation, limits, missing dependencies and login/permissions.
            if (name !in setOf("kind", "wordCount", "updateTime", "lastChapter", "intro", "coverUrl") || failure.code != ContentError.InvalidRule) throw failure
            ""
        }
        val value = valueFromRule.ifBlank {
            context.book.metadata[metadata]?.jsonPrimitive?.content ?: prior
        }
        context.bookField(metadata, value)
        return value
    }
    suspend fun coverUrl(): String = try {
        field("coverUrl", seed.coverUrl).let { if (it.isBlank()) "" else sourceLink(context.baseUrl, it) }
    } catch (failure: SourceContentException) {
        // Legado treats cover extraction as optional; a bad cover must not discard a novel.
        if (failure.code != ContentError.InvalidRule) throw failure
        ""
    }
    val title = field("name", seed.title)
    if (prefix != "ruleBookInfo" && title.isBlank()) return seed.copy(title = title, state = context.book)
    if (overview) {
        val cover = coverUrl()
        context.bookField("coverUrl", cover)
        return seed.copy(title = title, coverUrl = cover, state = context.book)
    }
    val author = field("author", seed.author)
    val preserveNames = prefix == "ruleBookInfo" && rules.string("canReName").isBlank()
    val finalTitle = if (preserveNames && priorTitle.isNotBlank()) priorTitle else title
    val finalAuthor = if (preserveNames && priorAuthor.isNotBlank()) priorAuthor else author
    context.bookField("name", finalTitle); context.bookField("author", finalAuthor)
    val kind = field("kind", seed.tags.joinToString(","))
    val wordCount = field("wordCount", seed.wordCount)
    val latest = field("lastChapter", seed.latestChapter, "latestChapterTitle")
    val intro = field("intro", seed.description)
    val cover = coverUrl()
    context.bookField("coverUrl", cover)
    val time = field("updateTime", seed.updateTime)
    return seed.copy(title = finalTitle, author = finalAuthor, description = intro, coverUrl = cover,
        tags = if (kind.isBlank()) seed.tags else kind.split('\n', ','), wordCount = wordCount,
        latestChapter = latest, updateTime = time, state = context.book).preservingValidMetadata(seed)
}
