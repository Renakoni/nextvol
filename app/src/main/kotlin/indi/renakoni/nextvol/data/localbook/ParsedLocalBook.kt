package indi.renakoni.nextvol.data.localbook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
sealed interface LocalBookBlock {
    @Serializable @SerialName("text") data class Text(val value: String) : LocalBookBlock
    @Serializable @SerialName("image") data class Image(val path: String) : LocalBookBlock
}

@Serializable
data class LocalBookChapter(
    val title: String,
    val volume: String = "",
    val blocks: List<LocalBookBlock>,
)

data class ParsedLocalBook(
    val title: String,
    val chapters: List<LocalBookChapter>,
    val author: String = "",
    val description: String = "",
    val publishingHouse: String = "",
    val coverPath: String? = null,
    val encoding: String? = null,
)

@Serializable
data class LocalChapterIndex(val title: String, val volume: String)

/** Only the catalogue and statistics survive preparation; chapter bodies stay in the draft. */
class PreparedLocalBook internal constructor(
    val title: String,
    val chapters: List<LocalChapterIndex>,
    internal val directory: File,
    val wordCount: Long = 0,
    internal val mappingDigest: String = "",
    val author: String = "",
    val description: String = "",
    val publishingHouse: String = "",
    val coverPath: String? = null,
    val encoding: String? = null,
) {
    internal fun chapter(number: Int, directory: File = this.directory): LocalBookChapter =
        Json.decodeFromString(File(directory, "$number.json").readText())
}
