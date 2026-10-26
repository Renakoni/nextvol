package hnovel.content

import hnovel.execution.ExecutionAuthority
import hnovel.network.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class StorageFailureTest {
    @Test fun realBookStateQuotaKeepsItsReasonAndPreviouslySavedState() {
        SourceBroker(Files.createTempDirectory("book-state-quota"),
            limits = BrokerLimits(maxBookStorageBytes = 1024)).use { broker ->
            val authority = ExecutionAuthority()
            val identity = authority.issue("fixture", "legado", "revision", "rules")
            val storage: SourceStateStorage = broker.open(SourceScope("rules", "fixture", "legado"), emptyList())
            val store = RuleBookStore(storage, authority, identity)
            val original = BookRecord("revision", RuleBook("book", title = "Saved"))
            store.write(original)
            val failure = runCatching { store.write(original.copy(book = original.book.copy(description = "x".repeat(2048)))) }
                .exceptionOrNull() as SourceContentException
            assertEquals(ContentError.Storage, failure.code)
            assertEquals(FailureCode.StorageQuota, failure.storageFailure)
            assertNull(failure.retry)
            assertEquals(original, store.read("book"))
            val partial = PartialDirectoryException(DirectorySnapshot(emptyList(), ScriptState()), failure)
            assertEquals(FailureCode.StorageQuota, partial.storageFailure)
        }
    }

    @Test fun brokerStorageErrorsDoNotBecomeNetworkErrors() {
        assertEquals(ContentError.Storage, FailureCode.StorageQuota.contentError())
        assertEquals(ContentError.Storage, FailureCode.StorageUnavailable.contentError())
    }
}
