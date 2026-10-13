package indi.renakoni.nextvol.data.web.rules

import hnovel.imports.EXTENSION_PROFILE
import hnovel.imports.ImportPreview
import hnovel.imports.SourceDefinition
import indi.renakoni.nextvol.data.web.SourceCatalog
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** A reviewed upstream release maps to a complete adaptation, never a mixture of script generations. */
internal class PixivUpdateAdapter(private val catalog: SourceCatalog) {
    private val adapted by lazy { catalog.definitions(setOf(KEY)) }
    private val adaptedDigest by lazy { digest(Json.parseToJsonElement(adapted).jsonArray.single()) }

    fun manages(definition: SourceDefinition): Boolean = accepts(definition)

    fun accepts(definition: SourceDefinition): Boolean =
        definition.profile == EXTENSION_PROFILE && definition.importKey == KEY &&
            digest(Json.parseToJsonElement(definition.rawJson)) == adaptedDigest

    fun adapt(preview: ImportPreview): String {
        // The upstream collection also contains alternate and comic sources. Never select by index.
        val candidate = preview.candidates.singleOrNull { it.importKey == KEY }
            ?: throw RevisionException(RevisionError.UpstreamNotAdapted)
        if (candidate.profile != EXTENSION_PROFILE || candidate.duplicateIndexes.isNotEmpty() ||
            preview.issues.any { it.index == null || it.index == candidate.index } ||
            digest(Json.parseToJsonElement(candidate.rawJson)) != UPSTREAM_284)
            throw RevisionException(RevisionError.UpstreamNotAdapted)
        return adapted
    }

    private fun digest(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    companion object {
        const val KEY = "https://www.pixiv.net/novel"
        const val UPDATE_URL = "https://raw.githubusercontent.com/DowneyRem/PixivSource/main/pixiv.json"
        // Upstream 284, 2026-09-21. Updating this requires reviewing and testing the complete adaptation.
        private const val UPSTREAM_284 = "7e2acfc99886fa084433dc67a3f7f050cedae94a7135ba8564664e3708a2faf9"
    }
}
