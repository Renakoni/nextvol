package hnovel.imports

import kotlinx.serialization.json.*

class SourceEditException(val code: ImportCode, val field: String? = null) : Exception(code.name)

/** Local overrides retain the original import identity. Parsing never stores or executes a definition. */
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
