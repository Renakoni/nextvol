package hnovel.speech

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import java.io.StringReader

/** Consumes parser events directly. No resolver, composer, Java constructor, or load API is used. */
internal fun readSpeechPackageYaml(text: String, checkCancelled: () -> Unit): SpeechPackageValue = try {
    val options = LoaderOptions().apply { codePointLimit = Int.MAX_VALUE }
    val parser = ParserImpl(StreamReader(StringReader(text.removePrefix("\uFEFF"))), options)
    fun expect(id: Event.ID) {
        packageCheck(parser.event.eventId == id, SpeechPackageError.InvalidYaml)
    }
    fun value(): SpeechPackageValue {
        checkCancelled()
        return when (val event = parser.event) {
            is ScalarEvent -> SpeechPackageValue.Scalar(event.value, event.tag, event.scalarStyle == DumperOptions.ScalarStyle.PLAIN)
            is SequenceStartEvent -> {
                val values = mutableListOf<SpeechPackageValue>()
                while (!parser.checkEvent(Event.ID.SequenceEnd)) values += value()
                expect(Event.ID.SequenceEnd)
                SpeechPackageValue.Sequence(values.toList(), event.tag)
            }
            is MappingStartEvent -> {
                val values = linkedMapOf<String, SpeechPackageValue>()
                while (!parser.checkEvent(Event.ID.MappingEnd)) {
                    val key = value() as? SpeechPackageValue.Scalar
                        ?: throw SpeechPackageFailure(SpeechPackageError.InvalidStructure)
                    packageCheck(key.value !in values, SpeechPackageError.DuplicateKey)
                    values[key.value] = value()
                }
                expect(Event.ID.MappingEnd)
                SpeechPackageValue.Mapping(values.toMap(), event.tag)
            }
            else -> throw SpeechPackageFailure(if (event.eventId == Event.ID.Alias) SpeechPackageError.YamlAlias else SpeechPackageError.InvalidYaml)
        }
    }
    expect(Event.ID.StreamStart)
    expect(Event.ID.DocumentStart)
    val root = value()
    expect(Event.ID.DocumentEnd)
    expect(Event.ID.StreamEnd)
    root
} catch (_: YAMLException) {
    // Parser messages can contain source snippets and credentials.
    throw SpeechPackageFailure(SpeechPackageError.InvalidYaml)
}
