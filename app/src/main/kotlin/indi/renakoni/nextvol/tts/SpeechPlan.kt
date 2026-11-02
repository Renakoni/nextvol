package indi.renakoni.nextvol.tts

import kotlinx.serialization.json.Json
import java.util.Locale

/** One chapter's synthesis input and original UTF-16 ranges, shared by every session consumer. */
class SpeechPlan internal constructor(
    val chapter: SpeechChapter,
    val synthesis: SpeechSettings,
    val segments: List<SpeechSegment>,
    val structuredText: String,
    private val mappedSegments: List<SpeechMappedText>,
    val context: SpeechContext = SpeechContext(),
    val nextContext: SpeechContext = context,
    private val maxInputLength: Int = Int.MAX_VALUE,
    private val locale: Locale = Locale.getDefault(),
) {
    private val replacementKey = synthesis.textProcessingKey(maxInputLength, locale)
    internal fun matches(chapter: SpeechChapter, synthesis: SpeechSettings, context: SpeechContext, maxInputLength: Int) =
        this.chapter == chapter && this.synthesis == synthesis && this.context == context &&
            this.maxInputLength == maxInputLength && locale == Locale.getDefault()

    internal fun startIndex(saved: SpeechBookmark?): Int {
        val matching = saved?.takeIf { it.bookId == chapter.bookId && it.chapterId == chapter.id &&
            it.fingerprint == chapter.fingerprint && !it.completed && it.offset in 0..chapter.text.length }
        // Expansion/capture reordering can give several clips overlapping original ranges.
        matching?.segmentIndex?.takeIf { matching.replacementKey == replacementKey && it in 0..segments.size }?.let { return it }
        val offset = matching?.offset ?: 0
        // End offsets are exclusive: a durable completion boundary must not replay
        // the finished segment, including whitespace before the next segment.
        return segments.indexOfFirst { it.end > offset }.takeIf { it >= 0 } ?: segments.size
    }

    internal fun bookmark(offset: Int, index: Int? = null) = SpeechBookmark(chapter.bookId, chapter.id,
        chapter.fingerprint, offset, chapter.bookTitle, chapter.title,
        replacementKey = replacementKey, segmentIndex = index, context = context)

    internal fun position(index: Int, start: Int = 0, end: Int = segments[index].text.length, anchor: Int = start): SpeechPosition {
        val mapped = mappedSegments[index]
        val range = mapped.range(start, end)
        val originalAnchor = mapped.anchor(anchor)
        return SpeechPosition(chapter.bookId, chapter.id, chapter.fingerprint, range.first, range.second,
            originalAnchor.coerceIn(range.first, range.second - 1))
    }
}

/** No plan is built by ordinary reading. Preparation is cancellable and limited to one bounded chapter. */
internal suspend fun prepareSpeechPlan(chapter: SpeechChapter, synthesis: SpeechSettings,
    replaceRegex: (suspend (String, String, String) -> List<hnovel.rules.ReplacementPart>)? = null,
    context: SpeechContext = SpeechContext(), maxInputLength: Int = Int.MAX_VALUE,
    voices: Map<String, SpeechResolvedVoice> = emptyMap(),
    checkCancelled: () -> Unit): SpeechPlan {
    checkCancelled()
    if (chapter.text.length > 1_000_000) throw SpeechException(SpeechError.UnsupportedContent)
    val settings = synthesis.text
    val locale = Locale.getDefault()
    val original = speechTrim(SpeechMappedText.original(chapter.text))
    // The original service filters before normalization, replacement and emoji expansion.
    if (settings.filterUnspoken && speechIsUnspoken(original.text))
        return SpeechPlan(chapter, synthesis, emptyList(), "", emptyList(), context = context, maxInputLength = maxInputLength, locale = locale)
    // R6.i.b requires both selections before parsing any dialogue; i.g only requires narration.
    if (synthesis.hasVoiceRouting) {
        if (synthesis.selectedNarrators.isEmpty()) throw SpeechException(SpeechError.MissingNarrationVoice)
        if (settings.dialogue && synthesis.dialogueVoices.isEmpty()) throw SpeechException(SpeechError.MissingDialogueVoice)
    }
    val replacements = SpeechReplacements(synthesis.replacements, replaceRegex, checkCancelled)
    val structured = replacements.apply(speechNormalize(original, checkCancelled), wholeText = true)
    val routing = SpeechVoiceRouting(synthesis, chapter.bookId, voices, context, maxInputLength)
    val roles = SpeechRoleRules(synthesis.roleRules, synthesis.roleContextPattern ?: "__ROLE__", replaceRegex, checkCancelled)
    // Context retains person identity, but an edited/disabled rule must not retain its old voice.
    val inputContext = routing.refreshContext(context) { old ->
        if (old.roleMatch != null) roles.refresh(old.roleMatch)?.personVoice()
        else roles.resolve(old.person, SpeechContextDirection.Explicit)?.personVoice() ?: routing.explicit(old.person)
    }
    val structure = speechStructure(structured, settings, inputContext,
        resolve = if (synthesis.hasVoiceRouting) { name, direction ->
            roles.resolve(name, direction)?.personVoice()
                ?: if (direction == SpeechContextDirection.Explicit) routing.explicit(name) else null
        } else null,
        textRules = roles.splitText(structured),
        checkCancelled = checkCancelled)
    val segments = mutableListOf<SpeechSegment>()
    val mappings = mutableListOf<SpeechMappedText>()
    for (part in structure.parts) {
        checkCancelled()
        val (voice, reason) = routing.select(part)
        val languages = if (part.controlMark) listOf(SpeechLanguageSegment(part.content, "lang:mark"))
            // P.f.i applies Latin separation only to spans not claimed by a text rule.
            else speechLatinSegments(part.content, settings.separateEnglish && part.textRule == null, checkCancelled)
        for (language in languages) {
            var corrected = language.content
            if (!part.controlMark) {
                corrected = replacements.apply(corrected, wholeText = false)
                if (settings.replaceEmoji) corrected = SpeechEmojiNames.replace(corrected, checkCancelled, locale)
            }
            for (sentence in speechSentenceChunks(corrected, settings.maxSentenceLength, locale, checkCancelled)) {
                for (mapped in speechHardChunks(sentence, voice.maxInputLength, checkCancelled)) {
                    if (mapped.text.all { Character.isWhitespace(it) } || settings.filterUnspoken && speechIsUnspoken(mapped.text)) continue
                    val (start, end) = mapped.range()
                    if (start < end) {
                        segments += SpeechSegment(start, end, mapped.text, part.dialogue, part.speaker,
                            part.speakerHint, language.language, part.beforeText, part.afterText,
                            part.rememberedText, part.previousVoice, part.changeVoice, voice, reason,
                            part.textRule ?: part.personVoice?.roleMatch?.takeIf { part.dialogue })
                        mappings += mapped
                    }
                }
            }
        }
    }
    return SpeechPlan(chapter, synthesis, segments, structured.text, mappings, context,
        if (synthesis.hasVoiceRouting) structure.nextContext.copy(narrationIndex = routing.narrationIndex,
            dialogueIndex = routing.dialogueIndex) else structure.nextContext, maxInputLength, locale)
}

internal fun SpeechSettings.textProcessingKey(maxInputLength: Int, locale: Locale = Locale.getDefault()) =
    speechDigest("speech-text-v4\u0000" + Json.encodeToString(replacements) + "\u0000" +
        Json.encodeToString(text) + "\u0000" + maxInputLength + "\u0000" + locale.toLanguageTag() +
        "\u0000" + Json.encodeToString(narrationVoices) + Json.encodeToString(dialogueVoices) +
        Json.encodeToString(speakerVoices) + speakerBookId + voiceRevision + voiceRoutingConfigured +
        Json.encodeToString(roleRules) + roleContextPattern)

/** Reuse the selected identities, including the installed offline resource revision, without a new schema. */
internal fun SpeechSettings.synthesisSettings(): SpeechSettings {
    val selected = validated().copy(playbackSpeed = 1f, allowMultipleVoices = false)
    if (selected.hasVoiceRouting) return selected
    return when {
        selected.offlineVoice != null -> selected.copy(engine = "", voice = "", rate = selected.rate ?: 1f, httpVolume = 1f)
        selected.httpSource != null -> selected.copy(engine = "", voice = "")
        else -> selected.copy(httpVolume = 1f)
    }
}
