import io.nightfish.potatoepub.builder.EpubBuilder
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EpubExportSafetyTest {
    @Test fun rebuildingRetainsOneSpineEntryPerChapterAndDoesNotMutateEarlierResult() {
        val builder = EpubBuilder().apply {
            title = "Book"; modifier = LocalDateTime.of(2026, 9, 19, 0, 0)
            chapter { title("Same"); content { text("First") } }
            chapter { title("Same"); content { text("Second") } }
        }
        val first = builder.build()
        val second = builder.build()
        assertEquals(listOf("chapter_0", "chapter_1"), first.opfPackage.spine.itemrefList.map { it.idref })
        assertEquals(first.opfPackage.spine.itemrefList, second.opfPackage.spine.itemrefList)
        builder.chapter { title("Same"); content { text("Third") } }
        assertEquals(3, builder.build().opfPackage.spine.itemrefList.size)
        assertEquals(2, first.opfPackage.spine.itemrefList.size)
        assertEquals(2, first.documents.size)
    }

    @Test fun emptyBookThrowsRecoverableException() {
        assertThrows(IllegalArgumentException::class.java) { EpubBuilder().build() }
    }

    @Test fun cancellationIsCheckedWhileStreamingLargeResources() {
        val directory = kotlin.io.path.createTempDirectory("epub-cancel").toFile()
        try {
            val resource = File(directory, "resource.bin").apply { writeBytes(ByteArray(1024 * 1024)) }
            val epub = EpubBuilder().apply {
                title = "Book"; modifier = LocalDateTime.of(2026, 9, 19, 0, 0)
                res("large", "resource.bin", "application/octet-stream", resource)
                chapter { content { text("Body") } }
            }.build()
            var checks = 0
            assertThrows(CancellationException::class.java) {
                epub.save(File(directory, "cancelled.epub")) { if (++checks == 5) throw CancellationException() }
            }
            assertEquals(5, checks)
        } finally { directory.deleteRecursively() }
    }
}
