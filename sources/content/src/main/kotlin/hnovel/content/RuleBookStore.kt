package hnovel.content

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.network.*
import kotlinx.serialization.json.Json

/** Source storage retains rule state and catalog snapshots across account changes. */
internal class RuleBookStore(private val storage: SourceStateStorage, private val authority: ExecutionAuthority,
    private val identity: ExecutionIdentity) {
    fun canonicalId(bookId: String): String = authority.authorized(identity) {
        var id = bookId
        val visited = mutableSetOf<String>()
        repeat(16) {
            if (!visited.add(id)) throw SourceContentException(ContentError.Storage, "bookAlias")
            val result = storage.read(StorageRequest(StorageArea.BookState, aliasKey(id)))
            if (result !is StorageResult.Value) throw storageException(result, "bookAlias")
            id = result.value ?: return@authorized id
        }
        throw SourceContentException(ContentError.Limit, "bookAlias")
    }
    fun read(bookId: String): BookRecord? = authority.authorized(identity) {
        val id = canonicalId(bookId)
        val current = storage.read(StorageRequest(StorageArea.BookState, key(id)))
        // Existing installations stored these snapshots beside small script settings.
        val result = if (current is StorageResult.Value && current.value == null)
            storage.read(StorageRequest(StorageArea.Config, key(id))) else current
        if (result !is StorageResult.Value) throw storageException(result, "bookState")
        result.value?.let { Json.decodeFromString(BookRecord.serializer(), it) }?.takeIf { it.book.id == id }
    }
    fun write(record: BookRecord, requestedId: String = record.book.id) =
        write(listOf(record), if (requestedId == record.book.id) emptyMap() else mapOf(requestedId to record.book.id))
    fun write(records: List<BookRecord>, aliases: Map<String, String> = emptyMap()) = authority.authorized(identity) {
        if (records.isEmpty()) return@authorized
        val values = records.associate { key(it.book.id) to Json.encodeToString(BookRecord.serializer(), it) } +
            aliases.mapKeys { aliasKey(it.key) }
        val result = storage.writeBookStates(values)
        if (result !is StorageResult.Value) throw storageException(result, "bookState")
        // Release the old quota only after the new batch has succeeded.
        values.keys.forEach {
            val cleared = storage.write(StorageRequest(StorageArea.Config, it))
            if (cleared !is StorageResult.Value) throw storageException(cleared, "bookState")
        }
        Unit
    }
    private fun storageException(result: StorageResult, field: String) =
        SourceContentException(ContentError.Storage, field, storageFailure = (result as? StorageResult.Failure)?.code)

    private fun key(bookId: String) = "content/book/" + digest(bookId)
    private fun aliasKey(bookId: String) = "content/alias/" + digest(bookId)
}
