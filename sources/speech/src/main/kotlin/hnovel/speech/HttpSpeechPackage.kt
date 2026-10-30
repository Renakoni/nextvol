package hnovel.speech

import hnovel.rules.RuleBudget
import hnovel.rules.RuleLimits
import hnovel.rules.RuleLocation
import hnovel.rules.RuleParser
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class HttpSpeechPackageError { UnsupportedEngine, UnsupportedRequest, RequiresParameters, InvalidConfiguration }

class HttpSpeechPackageImport(
    val sources: List<HttpSpeechDefinition> = emptyList(),
    val error: HttpSpeechPackageError? = null,
    val hasLocalAudioSettings: Boolean = false,
)

/** Converts request data only. Scripts still run exclusively through HttpSpeechClient after explicit playback. */
fun previewHttpSpeechPackage(engine: SpeechPackageEngine): HttpSpeechPackageImport {
    fun failed(error: HttpSpeechPackageError) = HttpSpeechPackageImport(error = error)
    if (engine.type != "http") return failed(HttpSpeechPackageError.UnsupportedEngine)
    val definition = engine.raw ?: return failed(HttpSpeechPackageError.InvalidConfiguration)
    val template = definition.values["url"].text()?.trim().orEmpty()
    if (template.isEmpty() || engine.voices.isEmpty() || engine.voices.any { it.code.isBlank() })
        return failed(HttpSpeechPackageError.InvalidConfiguration)
    if (definition.values["param"].requiresInput() || engine.voices.any {
        it.raw.values["param"].requiresInput() || it.raw.values["extendUI"].requiresInput()
    }) return failed(HttpSpeechPackageError.RequiresParameters)
    val rawHeader = definition.values["header"]
    if (rawHeader != null && rawHeader !is SpeechPackageValue.Scalar)
        return failed(HttpSpeechPackageError.UnsupportedRequest)
    val header = rawHeader.text().orEmpty()
    if (header.isNotBlank()) {
        val headers = try { Json.parseToJsonElement(header) as? JsonObject } catch (_: IllegalArgumentException) { null }
        if (headers == null || headers.values.any { it !is JsonPrimitive || !it.isString || "{{" in it.content })
            return failed(HttpSpeechPackageError.UnsupportedRequest)
    }
    val expression = try { packageGetExpression(template) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { return failed(HttpSpeechPackageError.UnsupportedRequest) }
    return try {
        val sources = engine.voices.map { voice ->
            // The existing 1x binding is 10; MultiTTS's request defaults are speed=50 and volume=50.
            // Speaker speed/volume/pitch are separate local audio effects, retained below as data.
            val script = "@js:(function(speakText,speakSpeed){" +
                "var voiceCode=${JsonPrimitive(voice.code)},params=[],speakVolume=50;" +
                "speakSpeed=speakSpeed*5;return $expression;})(speakText,speakSpeed)"
            HttpSpeechDefinition.parse(buildJsonObject {
                put("name", voice.name.ifBlank { voice.code })
                put("url", script)
                if (header.isNotBlank()) put("header", header)
                put("multiTts", buildJsonObject {
                    put("engine", definition.jsonData())
                    put("voice", voice.raw.jsonData())
                })
            })
        }.distinctBy { it.id }
        HttpSpeechPackageImport(sources, hasLocalAudioSettings = engine.voices.any { voice ->
            listOf("speed", "volume", "pitch").any { field ->
                voice.raw.values[field].text()?.toDoubleOrNull()?.let { it != 1.0 } == true
            }
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { failed(HttpSpeechPackageError.InvalidConfiguration) }
    // YAML preview is iterative, but the existing HTTP JSON representation uses recursive serialization.
    catch (_: StackOverflowError) { failed(HttpSpeechPackageError.InvalidConfiguration) }
}

private fun SpeechPackageValue?.requiresInput(): Boolean =
    this != null && (this !is SpeechPackageValue.Scalar || !text().isNullOrBlank())

/** Reuses balanced template scanning, but leaves every expression unevaluated until the worker runs it. */
private fun packageGetExpression(template: String): String {
    require(template.startsWith("https://", true) || template.startsWith("http://", true))
    val parser = RuleParser()
    val budget = RuleBudget(RuleLimits(maxRuleChars = template.length))
    val parts = mutableListOf<String>()
    var offset = 0
    while (offset < template.length) {
        val start = template.indexOf("{{", offset).let { if (it < 0) template.length else it }
        val literal = template.substring(offset, start)
        require(!Regex(",\\s*\\{").containsMatchIn(literal))
        if (literal.isNotEmpty()) parts += JsonPrimitive(literal).toString()
        if (start == template.length) break
        val end = parser.balancedEnd(template, start, RuleLocation("speech.url"), budget)
        require(template.substring(end - 2, end) == "}}")
        parts += "String((${template.substring(start + 2, end - 2)}))"
        offset = end
    }
    return parts.joinToString("+")
}

private fun SpeechPackageValue.jsonData(): JsonElement = when (this) {
    is SpeechPackageValue.Scalar -> text()?.let(::JsonPrimitive) ?: JsonNull
    is SpeechPackageValue.Sequence -> JsonArray(values.map { it.jsonData() })
    is SpeechPackageValue.Mapping -> JsonObject(values.mapValues { it.value.jsonData() })
}
