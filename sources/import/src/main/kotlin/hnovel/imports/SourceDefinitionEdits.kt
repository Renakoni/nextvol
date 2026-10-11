package hnovel.imports

import hnovel.network.sourcePermissionOrigin
import kotlinx.serialization.json.*
import java.net.URI

class SourceEditException(val code: ImportCode, val field: String? = null) : Exception(code.name)

data class SourceFieldChange(val field: String, val before: String, val after: String)
data class SourceDomainPreview(val rawJson: String, val changes: List<SourceFieldChange>, val remaining: List<String>)

/** Local overrides retain the original import identity. Previewing never stores or executes a definition. */
object SourceDefinitionEdits {
    private val pretty = Json { prettyPrint = true }

    fun prepare(before: SourceDefinition, text: String, revision: Long = before.revision + 1): SourceDefinition {
        val raw = parse(text)
        val parsed = try { LegadoSourceAdapter().validate(raw) }
        catch (failure: ImportFailure) { throw SourceEditException(failure.code, failure.field) }
        for ((section, field) in mapOf("ruleToc" to "chapterList", "ruleContent" to "content")) {
            val rule = (raw[section] as? JsonObject)?.get(field) as? JsonPrimitive
            if (rule?.isString != true || rule.content.isBlank())
                throw SourceEditException(ImportCode.InvalidField, "$section.$field")
        }
        val json = canonical(raw).toString()
        if (json == before.rawJson) return before
        require(revision > before.revision)
        return before.copy(displayName = parsed.name, enabled = parsed.enabled, enabledExplore = parsed.enabledExplore,
            contentDigest = digest(json), revision = revision, rawJson = json)
    }

    fun format(text: String): String = pretty.encodeToString(JsonObject.serializer(), parse(text))

    /** Replace domain literals (also common JS regex escapes), keeping surrounding request syntax. */
    fun previewDomain(text: String, oldDomain: String, newDomain: String): SourceDomainPreview {
        val old = domain(oldDomain)
        val next = domain(newDomain)
        require(old != next)
        val changes = mutableListOf<SourceFieldChange>()
        val remaining = mutableListOf<String>()
        val escapedOld = old.replace(".", "\\.")
        val replacements = listOf(escapedOld to next.replace(".", "\\."), old to next).map { (from, to) ->
            Regex("(?<![a-z0-9_-])${Regex.escape(from)}(?![a-z0-9_.-]|\\\\\\.)", RegexOption.IGNORE_CASE) to to
        }
        fun visit(value: JsonElement, path: String): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, child) -> visit(child, if (path.isEmpty()) key else "$path.$key") })
            is JsonArray -> JsonArray(value.mapIndexed { index, child -> visit(child, "$path[$index]") })
            is JsonPrimitive -> if (!value.isString) value else {
                val before = value.content
                val after = replacements.fold(before) { content, (pattern, replacement) -> pattern.replace(content) { replacement } }
                if (before != after) changes += SourceFieldChange(path, before, after)
                if (after.contains(old, true) || after.contains(escapedOld, true)) remaining += path
                JsonPrimitive(after)
            }
        }
        val raw = visit(parse(text), "").jsonObject
        return SourceDomainPreview(pretty.encodeToString(JsonObject.serializer(), raw), changes, remaining)
    }

    private fun domain(text: String): String {
        val address = text.trim().let { if ("://" in it) it else "https://$it" }
        val uri = URI(address)
        require(uri.rawPath.orEmpty() in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null && uri.port == -1)
        val host = URI(requireNotNull(sourcePermissionOrigin(address))).host
        require(host.contains('.') && host.all { it.isLetterOrDigit() || it in ".-" })
        return host.lowercase()
    }

    private fun parse(text: String): JsonObject {
        val limits = ImportLimits()
        if (text.length > limits.maxBytes || text.toByteArray(Charsets.UTF_8).size > limits.maxBytes)
            throw SourceEditException(ImportCode.TooLarge)
        return try { parseDefinitionJson(text, limits.maxDepth) as? JsonObject
            ?: throw SourceEditException(ImportCode.InvalidShape) }
        catch (failure: ImportFailure) { throw SourceEditException(failure.code, failure.field) }
        catch (failure: SourceEditException) { throw failure }
        catch (_: Exception) { throw SourceEditException(ImportCode.InvalidJson) }
    }
}
