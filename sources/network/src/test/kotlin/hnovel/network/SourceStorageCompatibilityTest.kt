package hnovel.network

import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class SourceStorageCompatibilityTest {
    private fun legacyHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Test fun existingEncryptedFilesKeepTheirNamesAndAuthenticatedIdentity() {
        val root = Files.createTempDirectory("storage-compatibility")
        val namespace = listOf("rules", "书源🌙", "profile", "7")
        val identity = namespace.joinToString("") { "${it.length}:$it" }
        val key = "content/book/https://example.test/小说?q=é"
        val directory = Files.createDirectory(root.resolve(legacyHash(identity)))
        val file = directory.resolve(legacyHash(key))
        val secret = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val cipher = object : StorageCipher {
            override fun seal(bytes: ByteArray, identity: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, secret); updateAAD(identity.toByteArray(Charsets.UTF_8))
                iv + doFinal(bytes)
            }
            override fun open(bytes: ByteArray, identity: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(identity.toByteArray(Charsets.UTF_8)); doFinal(bytes.copyOfRange(12, bytes.size))
            }
        }
        try {
            Files.write(file, cipher.seal("existing".toByteArray(), identity + legacyHash(key)))
            val storage = SourceStorage(root, namespace, BrokerLimits(), cipher)
            assertEquals(StorageResult.Value("existing"), storage.read(key))
            assertEquals(StorageResult.Value("updated"), storage.write(key, "updated"))
            assertEquals("updated", cipher.open(Files.readAllBytes(file), identity + legacyHash(key)).toString(Charsets.UTF_8))
            assertEquals(1, Files.list(directory).use { it.count() }.toInt())
        } finally { root.toFile().deleteRecursively() }
    }

}
