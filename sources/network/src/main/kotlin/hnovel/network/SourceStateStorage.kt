package hnovel.network

/** State access bound to one host session; requests cannot select another storage owner. */
interface SourceStateStorage {
    fun read(request: StorageRequest): StorageResult
    fun write(request: StorageRequest): StorageResult

    /** One batch entry point, with per-key replacement and no whole-batch rollback guarantee. */
    fun writeBookStates(values: Map<String, String>): StorageResult
}
