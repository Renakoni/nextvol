package hnovel.rhino

import cn.hutool.crypto.symmetric.SymmetricCrypto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class ScriptCryptoTest {
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Crypto must stay local") })
    private val frame = ScriptFrame("a", "legado")
    private fun run(name: String, vararg args: String): JsonElement {
        val script = "java.$name(${args.joinToString(",") { JsonPrimitive(it).toString() }})"
        val result = engine.evaluate(script, frame)
        assertTrue(result.toString(), result is ScriptResult.Success)
        return Json.parseToJsonElement((result as ScriptResult.Success).json)
    }
    private fun bytes(value: ByteArray) = JsonArray(value.map { JsonPrimitive(it.toInt()) })

    @Test fun legacyAesEncryptionAndHexOrBase64DecryptionMatchReferenceCrypto() {
        val key = "0123456789abcdef"
        val iv = "abcdef0123456789"
        for (mode in listOf("AES/CBC/PKCS5Padding", "AES/CBC/ZeroPadding")) {
            // SunJCE requires a bare AES key algorithm, unlike the Android provider used upstream.
            val reference = SymmetricCrypto(mode, SecretKeySpec(key.toByteArray(), "AES")).setIv(iv.toByteArray())
            for (text in listOf("", "chapter", "\u4E2D\u6587")) {
                val encrypted = reference.encrypt(text)
                val base64 = Base64.getEncoder().encodeToString(encrypted)
                assertEquals(bytes(encrypted), run("aesEncodeToByteArray", text, key, mode, iv))
                assertEquals(JsonPrimitive(base64), run("aesEncodeToBase64String", text, key, mode, iv))
                assertEquals(bytes(base64.toByteArray()), run("aesEncodeToBase64ByteArray", text, key, mode, iv))
                for (method in listOf("aesDecodeToString", "aesBase64DecodeToString", "aesEncodeToString"))
                    assertEquals(JsonPrimitive(text), run(method, base64, key, mode, iv))
                for (method in listOf("aesDecodeToByteArray", "aesBase64DecodeToByteArray"))
                    assertEquals(bytes(text.toByteArray()), run(method, reference.encryptHex(text), key, mode, iv))
            }
        }
    }

    @Test fun aesBase64ArgumentOverloadsKeepTheirDifferentPinnedKeySemantics() {
        val raw = "0123456789abcdef"
        val encodedKey = Base64.getEncoder().encodeToString(raw.toByteArray())
        val reference = SymmetricCrypto("AES/ECB/PKCS5Padding", SecretKeySpec(raw.toByteArray(), "AES"))
        assertEquals(JsonPrimitive("data"), run("aesDecodeArgsBase64Str", reference.encryptBase64("data"), encodedKey, "ECB", "PKCS5Padding", ""))
        val encodeReference = SymmetricCrypto("AES/ECB/PKCS5Padding", SecretKeySpec(encodedKey.toByteArray(), "AES"))
        assertEquals(JsonPrimitive(encodeReference.encryptBase64("data")), run("aesEncodeArgsBase64Str", "data", encodedKey, "ECB", "PKCS5Padding", ""))
    }

    @Test fun desAndTripleDesReturnReferenceStringsAndDecodeKeysOnlyInSpecifiedOverloads() {
        val key = "01234567"
        val reference = SymmetricCrypto("DES/ECB/PKCS5Padding", key.toByteArray())
        assertEquals(JsonPrimitive(reference.encrypt("data").toString(Charsets.UTF_8)), run("desEncodeToString", "data", key, "DES/ECB/PKCS5Padding", ""))
        assertEquals(JsonPrimitive(reference.encryptBase64("data")), run("desEncodeToBase64String", "data", key, "DES/ECB/PKCS5Padding", ""))
        for (name in listOf("desDecodeToString", "desBase64DecodeToString"))
            assertEquals(JsonPrimitive("data"), run(name, reference.encryptBase64("data"), key, "DES/ECB/PKCS5Padding", ""))
        val tripleKey = "0123456789abcdefABCDEFGH"
        val iv = "01234567"
        val triple = SymmetricCrypto("DESede/CBC/PKCS5Padding", tripleKey.toByteArray()).setIv(iv.toByteArray())
        for (encoded in listOf(false, true)) {
            val argument = if (encoded) Base64.getEncoder().encodeToString(tripleKey.toByteArray()) else tripleKey
            val encode = if (encoded) "tripleDESEncodeArgsBase64Str" else "tripleDESEncodeBase64Str"
            val decode = if (encoded) "tripleDESDecodeArgsBase64Str" else "tripleDESDecodeStr"
            assertEquals(JsonPrimitive(triple.encryptBase64("data")), run(encode, "data", argument, "CBC", "PKCS5Padding", iv))
            assertEquals(JsonPrimitive("data"), run(decode, triple.encryptBase64("data"), argument, "CBC", "PKCS5Padding", iv))
        }
    }

    @Test fun paddingAliasAndInvalidArgumentsStayInsideBoundedRedactedJsErrors() {
        assertEquals(run("aesEncodeToBase64String", "data", "0123456789abcdef", "AES/ECB/PKCS5Padding", ""),
            run("aesEncodeToBase64String", "data", "0123456789abcdef", "AES/ECB/PKCS7Padding", ""))
        val result = engine.evaluate("try{java.aesDecodeToString('secret','short','AES/ECB/PKCS5Padding','')}catch(e){e.message}", frame)
        assertEquals("\"invalid tool argument\"", (result as ScriptResult.Success).json)
        val small = RhinoScriptEngine(HostBridge { _, _ -> JsonNull }, ScriptLimits(maxBridgeChars = 96))
        assertEquals(FailureCode.ResultTooLarge, (small.evaluate("java.aesEncodeToByteArray('a'.repeat(30),'0123456789abcdef','AES/ECB/PKCS5Padding','')", frame) as ScriptResult.Failure).code)
    }

}
