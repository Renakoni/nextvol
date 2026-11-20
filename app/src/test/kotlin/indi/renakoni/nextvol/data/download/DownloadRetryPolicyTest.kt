package indi.renakoni.nextvol.data.download

import hnovel.network.BrokerResponse
import hnovel.network.RequestRetryHint
import hnovel.network.retryAfterMillis
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DownloadRetryPolicyTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
    private val policy = DownloadRetryPolicy({ now }, { it })

    @Test fun serverDeadlinesSurviveHeaderParsingWithoutClampingOrOverflow() {
        fun hint(value: String) = RequestRetryHint(retryAfterMillis(BrokerResponse(503, "https://example.test",
            mapOf("Retry-After" to listOf(value)), byteArrayOf(), "UTF-8", 0), now))
        for (value in listOf("90", "Mon, 28 Sep 2026 00:01:30 GMT"))
            assertEquals(now + 90_000, policy.nextAttemptAt(0, hint(value)))
        for (value in listOf("invalid", "-1", "0")) assertEquals(now + 30_000, policy.nextAttemptAt(0, hint(value)))
        assertEquals(now + 86_400_000, policy.nextAttemptAt(0, hint("86400")))
        for (value in listOf("86401", "9999999999999999999999999999")) assertNull(policy.nextAttemptAt(0, hint(value)))
    }

    @Test fun sourceStorageFailuresKeepTheirCategoryThroughImageLoading() {
        val error = io.nightfish.lightnovelreader.api.error.WebRequestError("", "",
            hnovel.content.SourceContentException(hnovel.content.ContentError.Storage, "book"))
        assertEquals(DownloadFailure.Storage, downloadFailure(error, DownloadStage.Details))
        val image = indi.renakoni.nextvol.data.image.SourceImageRequestException(error)
        assertEquals(DownloadFailure.Storage, downloadFailure(
            io.nightfish.lightnovelreader.api.error.WebRequestError("", "", image), DownloadStage.Image))
        assertNull(image.retry)
    }
    @Test fun quotaAndDiskFullAreStorageFailuresEvenAtADocumentOrImageStage() {
        val quota = hnovel.content.SourceContentException(hnovel.content.ContentError.Storage, "bookState",
            storageFailure = hnovel.network.FailureCode.StorageQuota)
        val error = io.nightfish.lightnovelreader.api.error.WebRequestError("", "", quota)
        assertEquals(DownloadFailure.StorageQuota, downloadFailure(error, DownloadStage.Directory))
        val image = indi.renakoni.nextvol.data.image.SourceImageRequestException(error)
        assertEquals(DownloadFailure.StorageQuota, downloadFailure(
            io.nightfish.lightnovelreader.api.error.WebRequestError("", "", image), DownloadStage.Image))
        assertNull(image.retry)
        assertEquals(DownloadFailure.SourceRequest, downloadFailure(
            io.nightfish.lightnovelreader.api.error.WebRequestError("", "", IllegalStateException("disk full")), DownloadStage.Body))
    }

}
