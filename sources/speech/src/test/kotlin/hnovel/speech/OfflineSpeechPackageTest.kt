package hnovel.speech

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OfflineSpeechPackageTest {
    @Test fun engineMetadataIsPreservedSeparatelyFromVoiceParameters() {
        fun fields(vararg values: Pair<String, String>) = SpeechPackageValue.Mapping(values.associate { (key, value) ->
            key to SpeechPackageValue.Scalar(value, "tag:yaml.org,2002:str", false)
        }, null)
        val voice = SpeechPackageVoice("voice", "Voice", fields("param" to "4"))
        val engine = SpeechPackageEngine("xiaomi", "My Xiaomi", "inner",
            fields("param" to "{\"shared\":true}", "note" to "A saved note"), listOf(voice),
            SpeechPackageSupport.Unsupported, emptyList(), emptyList())
        val preview = SpeechPackagePreview("package", engines = listOf(engine),
            resources = listOf(SpeechPackageResource("xiaomi/voice/model.bin", 100)))
        val plan = previewOfflineVoice(preview, engine, voice).plan!!
        assertEquals(OfflineEngineConfig("xiaomi", "My Xiaomi", parameters = "{\"shared\":true}", note = "A saved note"),
            plan.engineConfiguration)
        assertEquals("4", plan.configuration.parameters)
        assertEquals("", plan.configuration.engineParameters)
        assertEquals(plan.engineConfiguration,
            Json.decodeFromString<OfflineEngineConfig>(Json.encodeToString(plan.engineConfiguration)))
    }

    private fun preview(engineCode: String, param: String = "", paths: List<String>, extra: Map<String, String> = emptyMap()): OfflinePackagePreview {
        val values = mapOf("param" to param, "locale" to "en-US", "sampleRate" to "48000", "speed" to "0.8",
            "pitch" to "1.2", "volume" to "3", "type" to "2", "gender" to "2") + extra
        val voice = SpeechPackageVoice("voice", "Voice", SpeechPackageValue.Mapping(values.mapValues {
            SpeechPackageValue.Scalar(it.value, "tag:yaml.org,2002:str", false)
        }, null))
        val engine = SpeechPackageEngine(engineCode, engineCode, null, null, listOf(voice), SpeechPackageSupport.Unsupported, emptyList(), emptyList())
        val preview = SpeechPackagePreview("package", engines = listOf(engine), resources = paths.map {
            SpeechPackageResource("$engineCode/$it", 100)
        })
        return previewOfflineVoice(preview, engine, voice)
    }

    @Test fun engineParametersAndSharedResourcesSurviveImportPlanningAndCatalogSerialization() {
        val samples = mapOf("audio" to "scene.mp3", "acapela" to "", "cereproc" to "", "google" to "",
            "isstts" to "62915", "larktts" to "1", "msctts" to "5ef02a7c,77010006", "vcstts" to "",
            "vocalizer" to "", "weread" to "", "samsung" to "", "xiaomi" to "4",
            "microsoft" to "Microsoft Xiaoxiao (Natural) - Chinese (Simplified, China)")
        samples.forEach { (engine, param) ->
            val type = if (engine == "microsoft") "0" else "2"
            val paths = when (engine) {
                "audio" -> listOf(param)
                "xiaomi", "samsung", "microsoft", "vocalizer", "cereproc" -> listOf("voice/cfg", "voice/model.bin")
                "msctts" -> listOf("5ef02a7c/common.jet", "5ef02a7c/voice.jet")
                "larktts" -> listOf("larktrf.trf", "libspeaker_xiaowu.so", "acoustic/voice.trf")
                "vcstts" -> listOf("idst/asr/shared.bin", "acoustic/voice")
                else -> listOf("shared/dictionary.bin", "voices/voice.data")
            }
            val plan = preview(engine, param, paths, mapOf("type" to type)).plan!!
            assertEquals(engine + "\u0000voice", plan.id)
            assertEquals(engine, plan.directory)
            assertEquals(paths.size, plan.resources.size)
            val config = plan.configuration
            assertEquals(param, config.parameters)
            assertEquals(48000, config.sampleRate)
            assertEquals("en-US", config.locale)
            assertEquals(.8f, config.speed, 0f)
            assertEquals(1.2f, config.pitch, 0f)
            assertEquals(3f, config.volume, 0f)
            assertEquals(type.toInt(), config.type)
            assertEquals(2, config.gender)
            assertEquals(config, Json.decodeFromString<OfflineVoiceConfig>(Json.encodeToString(config)))
        }
        val sherpa = preview("sherpa", paths = listOf("voice/model.onnx", "voice/tokens.txt"),
            param = """{"speakerId":"@sid"}""", extra = mapOf("extendUI" to """[{"code":"sid","value":"7"}]""")).plan!!
        assertEquals("sherpa/voice", sherpa.directory)
        assertEquals(7, sherpa.configuration.sherpa!!.speakerId)
    }

    @Test fun baiduAndSogouKeepSharedDependenciesAndOnlyTheSelectedAcousticResources() {
        val baidu = preview("bdetts", "6082724,com.baidu.searchbox.tts.plugin,base,138,custom-text",
            listOf("6082724/custom-text.dat", "6082724/licence.dat", "6082724/acoustic/voice.voc.dat",
                "6082724/acoustic/base.ext.dat", "6082724/acoustic/voice.res.dat", "6082724/acoustic/other.voc.dat")).plan!!
        assertEquals(5, baidu.resources.size)
        assertFalse(baidu.resources.any { it.path.endsWith("other.voc.dat") })
        val sogou = preview("sougou", paths = listOf("dict_zh.dat", "engdict.dat", "acoustic/voice.bin", "acoustic/other.bin")).plan!!
        assertEquals(3, sogou.resources.size)
        assertEquals("sougou", sogou.configuration.engine)
        val missing = preview("sougou", paths = listOf("dict_zh.dat", "acoustic/voice.bin"))
        assertEquals(MatchaPackageError.MissingResource, missing.error)
        assertEquals("engdict.dat", missing.detail)
    }

    @Test fun independentVoiceDirectoriesAndAudioDoNotInstallOtherVoices() {
        listOf("microsoft", "vocalizer").forEach { engine ->
            val plan = preview(engine, paths = listOf("voice/model", "voice/config", "other/model"),
                extra = mapOf("type" to "0")).plan!!
            assertEquals(listOf("$engine/voice/config", "$engine/voice/model"), plan.resources.map { it.path })
        }
        val audio = preview("audio", "effects/selected.mp3", listOf("effects/selected.mp3", "effects/other.mp3")).plan!!
        assertEquals(listOf("audio/effects/selected.mp3"), audio.resources.map { it.path })
        assertEquals(MatchaPackageError.MissingResource,
            preview("vocalizer", paths = listOf("other/model")).error)
    }

    @Test fun cereProcKeepsMetadataAndReferencedLicenseAlongsideTheSelectedVoice() {
        val paths = listOf("voice/model.voice", "other/model.voice", "Product_Records.json", "licenses/selected.lic", "licenses/other.lic")
        val plan = preview("cereproc", """{"licenseFile":"licenses/selected.lic"}""", paths).plan!!
        assertEquals(setOf("cereproc/voice/model.voice", "cereproc/Product_Records.json", "cereproc/licenses/selected.lic"),
            plan.resources.map { it.path }.toSet())
        assertEquals(MatchaPackageError.MissingResource,
            preview("cereproc", """{"licenseFile":"missing.lic"}""", paths).error)
        assertEquals(MatchaPackageError.UnsafePath,
            preview("cereproc", """{"licenseFile":"../outside.lic"}""", paths).error)
    }

    @Test fun mscLarkAndVcsKeepSharedDataWithoutOtherAcousticModels() {
        val mscPaths = listOf("app/common.jet", "app/voice.jet", "app/override.jet", "other/common.jet")
        val inferred = preview("msctts", paths = mscPaths).plan!!
        assertEquals(listOf("msctts/app/common.jet", "msctts/app/voice.jet"), inferred.resources.map { it.path })
        val overridden = preview("msctts", "app,channel,override", mscPaths).plan!!
        assertEquals(listOf("msctts/app/common.jet", "msctts/app/override.jet"), overridden.resources.map { it.path })
        val larkPaths = listOf("larktrf.trf", "libspeaker_xiaowu.so", "libTtsVoiceCommon.so", "acoustic/voice.irf", "acoustic/voice.trf", "acoustic/other.trf")
        assertEquals(listOf("larktts/larktrf.trf", "larktts/libspeaker_xiaowu.so", "larktts/acoustic/voice.trf"),
            preview("larktts", "1", larkPaths).plan!!.resources.map { it.path })
        assertEquals(preview("larktts", "0", larkPaths).plan!!.resources,
            preview("larktts", paths = larkPaths).plan!!.resources)
        assertEquals(listOf("larktts/libTtsVoiceCommon.so", "larktts/acoustic/voice.irf"),
            preview("larktts", "0", larkPaths).plan!!.resources.map { it.path })
        val vcs = preview("vcstts", paths = listOf("acoustic/voice", "acoustic/other", "idst/asr/tts/dictionary.bin", "idst/rsc/shared.bin")).plan!!
        assertEquals(setOf("vcstts/acoustic/voice", "vcstts/idst/asr/tts/dictionary.bin", "vcstts/idst/rsc/shared.bin"),
            vcs.resources.map { it.path }.toSet())
        assertEquals(MatchaPackageError.MissingResource,
            preview("msctts", "app", listOf("app/voice.jet")).error)
    }

    @Test fun enginePathsAndParameterSelectedResourcesCannotEscapeTheirInstallation() {
        assertEquals(MatchaPackageError.UnsafePath,
            preview("microsoft", paths = listOf("voice/data.bin", "../other/file"), extra = mapOf("type" to "0")).error)
        assertEquals(MatchaPackageError.UnsafePath,
            preview("bdetts", "../other,package", paths = listOf("voice.dat")).error)
        assertEquals(MatchaPackageError.Ambiguous,
            preview("google", paths = listOf("voice/data.bin", "voice/data.bin")).error)
        assertEquals(MatchaPackageError.UnsupportedModel,
            preview("http", paths = listOf("data")).error)
        assertEquals(MatchaPackageError.UnsupportedModel,
            preview("microsoft", paths = listOf("voice/data.bin"), extra = mapOf("type" to "1")).error)
    }
}
