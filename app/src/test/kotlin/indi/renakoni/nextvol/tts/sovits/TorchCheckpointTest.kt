package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorchCheckpointTest {
    @Test fun refusesExecutablePickleGlobalsAndInvalidTensorStorage() {
        val pickle = byteArrayOf(0x80.toByte(), 2) + "cos\nsystem\n(S'echo unsafe'\ntR.".toByteArray()
        val failure = assertThrows(SovitsImportException::class.java) { TorchPickle(pickle).read() }
        assertEquals(R.string.sovits_checkpoint_object_unsupported, failure.text.resource)
        assertThrows(IllegalArgumentException::class.java) { TorchPickle(byteArrayOf(0x80.toByte(), 2, 'X'.code.toByte(), 1)).read() }
        assertThrows(IllegalArgumentException::class.java) {
            TorchTensor(TorchStorage("HalfStorage", "0", 4), 2, listOf(4), listOf(1)).validate()
        }
    }

    @Test fun readsRealTorchExportsWithStorageOffsetsAndRejectsNonContiguousTensors() {
        for (protocol in listOf(2, 4)) {
            TorchCheckpoint(File("src/test/resources/sovits/torch-protocol-$protocol.ptfixture")).use { checkpoint ->
                assertEquals(listOf(2L, 3L), checkpoint.tensor("half").shape)
                assertEquals("FloatStorage", checkpoint.tensor("float").storage.type)
                assertEquals("BFloat16Storage", checkpoint.tensor("bfloat").storage.type)
                val slice = checkpoint.tensor("slice")
                assertEquals(2L, slice.offset)
                assertEquals(0x40, checkpoint.open(slice).use { it.readBytes() }[1].toInt())
                assertThrows(IllegalArgumentException::class.java) { checkpoint.tensor("transpose") }
            }
        }
    }

}
