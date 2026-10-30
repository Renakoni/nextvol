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
    expect(Event.ID.StreamStart)
    expect(Event.ID.DocumentStart)
    val collections = ArrayDeque<Pair<Event, MutableList<SpeechPackageValue>>>()
    var root: SpeechPackageValue
    while (true) {
        checkCancelled()
        val value = when (val event = parser.event) {
            is ScalarEvent -> SpeechPackageValue.Scalar(event.value, event.tag, event.scalarStyle == DumperOptions.ScalarStyle.PLAIN)
            is SequenceStartEvent, is MappingStartEvent -> {
                collections.addLast(event to mutableListOf())
                continue
            }
            else -> {
                if (event.eventId == Event.ID.Alias) throw SpeechPackageFailure(SpeechPackageError.YamlAlias)
                packageCheck(event.eventId == Event.ID.SequenceEnd || event.eventId == Event.ID.MappingEnd, SpeechPackageError.InvalidYaml)
                val (start, children) = collections.removeLastOrNull()
                    ?: throw SpeechPackageFailure(SpeechPackageError.InvalidYaml)
                if (start is SequenceStartEvent) {
                    packageCheck(event.eventId == Event.ID.SequenceEnd, SpeechPackageError.InvalidYaml)
                    SpeechPackageValue.Sequence(children.toList(), start.tag)
                } else {
                    packageCheck(event.eventId == Event.ID.MappingEnd && children.size % 2 == 0, SpeechPackageError.InvalidYaml)
                    val values = linkedMapOf<String, SpeechPackageValue>()
                    for (index in children.indices step 2) {
                        checkCancelled()
                        val key = children[index] as? SpeechPackageValue.Scalar
                            ?: throw SpeechPackageFailure(SpeechPackageError.InvalidStructure)
                        packageCheck(key.value !in values, SpeechPackageError.DuplicateKey)
                        values[key.value] = children[index + 1]
                    }
                    SpeechPackageValue.Mapping(values.toMap(), (start as MappingStartEvent).tag)
                }
            }
        }
        if (collections.isEmpty()) {
            root = value
            break
        }
        collections.last().second += value
    }
    expect(Event.ID.DocumentEnd)
    expect(Event.ID.StreamEnd)
    root
} catch (_: YAMLException) {
    // Parser messages can contain source snippets and credentials.
    throw SpeechPackageFailure(SpeechPackageError.InvalidYaml)
}
