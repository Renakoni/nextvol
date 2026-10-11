package indi.renakoni.nextvol.data.web

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.imports.SourceDefinition
import indi.renakoni.nextvol.R
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class SourceCategory(val title: Int) {
    Platforms(R.string.source_category_platforms),
    Female(R.string.source_category_female),
    Anime(R.string.source_category_anime),
    Literature(R.string.source_category_literature),
    General(R.string.source_category_general),
    Adult(R.string.source_category_adult),
}

/** Stored preferences name their category. A retired name, such as the former Official Sites, reads as none
 *  so restoring still succeeds; the catalog then supplies the source's current category. */
internal object StoredSourceCategorySerializer : KSerializer<SourceCategory?> {
    private val names = String.serializer().nullable
    override val descriptor = names.descriptor
    override fun serialize(encoder: Encoder, value: SourceCategory?) = names.serialize(encoder, value?.name)
    override fun deserialize(decoder: Decoder) = names.deserialize(decoder)?.let { name -> SourceCategory.entries.firstOrNull { it.name == name } }
}

@Serializable
data class CatalogSource(val key: String, val category: SourceCategory, val name: String,
    val subtitle: String, val index: Int, val host: String = "", val available: Boolean = true,
    val replaces: Set<String> = emptySet())

/** Presentation metadata is separate from the original, explicitly imported rule definitions. */
@Singleton
class SourceCatalog @Inject constructor(@ApplicationContext private val context: Context) {
    private val allEntries: List<CatalogSource> by lazy {
        context.assets.open("source-catalog/catalog.json").bufferedReader().use {
            Json.decodeFromString<List<CatalogSource>>(it.readText())
        }
    }
    val entries: List<CatalogSource> by lazy { allEntries.filter { it.available } }
    private val byKey by lazy { allEntries.associateBy { it.key } }

    // Match the complete import identity, never a display name, group label or hostname.
    fun entry(definition: SourceDefinition): CatalogSource? = byKey[definition.importKey]

    /** Only an exact, unmodified bundled revision is eligible for a bundled repair. */
    internal fun replacement(definition: SourceDefinition): String? {
        val entry = entry(definition)?.takeIf { definition.contentDigest in it.replaces } ?: return null
        return definitions(listOf(entry))
    }

    fun definitions(keys: Set<String>): String {
        val selected = entries.filter { it.key in keys }
        require(selected.isNotEmpty() && selected.size == keys.size)
        return definitions(selected)
    }

    private fun definitions(selected: List<CatalogSource>): String {
        val batches = selected.map { it.category }.distinct().associateWith { category ->
            context.assets.open("source-catalog/${category.name}.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonArray
            }
        }
        return JsonArray(selected.map { entry ->
            batches.getValue(entry.category)[entry.index].also {
                check(it.jsonObject.getValue("bookSourceUrl").jsonPrimitive.content == entry.key)
            }
        }).toString()
    }
}
