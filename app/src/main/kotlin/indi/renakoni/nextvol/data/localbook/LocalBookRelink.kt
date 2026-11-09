package indi.renakoni.nextvol.data.localbook

import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.local.room.entity.ImportedBookEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes

enum class LocalBookRelinkMatch { Exact, DifferentFile, DifferentMapping, MissingManifest }

/** A preview belongs to one target and one staged copy; publication rechecks its database snapshot. */
class LocalBookRelinkPreview internal constructor(
    val book: SourceBookId,
    val parsed: PreparedLocalBook,
    val match: LocalBookRelinkMatch,
    internal val draft: LocalBookDraft,
    internal val manifest: LocalBookFileManifest,
    internal val expectedManifest: LocalBookFileManifest?,
    internal val expectedOwner: ImportedBookEntity?,
    internal val information: BookInformation?,
    internal val volumes: BookVolumes?,
) {
    val canRelink get() = match == LocalBookRelinkMatch.Exact
}
