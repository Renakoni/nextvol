package indi.renakoni.nextvol.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem

enum class SourceCapability {
    Search, BookInformation, Directory, ChapterContent, Explore, Categories, Login, Images
}

data class SourceMetadata(
    val item: WebDataSourceItem,
    val capabilities: Set<SourceCapability>,
    val builtIn: Boolean = false,
    val revision: String = "1",
    val accountGeneration: Long = 0,
    val category: SourceCategory? = null,
) {
    val id: Identifier get() = item.id
    val supportsReading: Boolean get() = SourceCapability.Directory in capabilities && SourceCapability.ChapterContent in capabilities
}

enum class SourceStatus { Registered, Initializing, Ready, Failed }
enum class SourceRestoration { Restoring, Ready, Failed }

data class SourceListing(val metadata: SourceMetadata, val status: SourceStatus, val generation: Long = 0,
    val resolvedFeed: Boolean? = null, val discoveryGeneration: Long = 0)

/** Removing an old owner must not unregister a newer source with the same ID. */
class SourceRegistration internal constructor(
    val metadata: SourceMetadata,
    private val removeRegistration: () -> Unit,
) {
    internal var owner: Any? = null
    fun unregister() = removeRegistration()
}

sealed interface SourceResolution {
    data class Ready(val runtime: SourceRuntime) : SourceResolution
    data class Missing(val id: Identifier) : SourceResolution
    data class Unavailable(val id: Identifier, val cause: Exception) : SourceResolution
}

class SourceUnavailableException(val sourceId: Identifier) :
    IllegalStateException("Source is no longer available: $sourceId")
