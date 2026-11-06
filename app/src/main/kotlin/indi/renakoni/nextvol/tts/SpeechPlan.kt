package indi.renakoni.nextvol.tts

import kotlinx.serialization.json.Json
import indi.renakoni.nextvol.R
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
    val logId: Long? = null,
    private val trimmed: Boolean = false,
) {
    private val replacementKey = synthesis.textProcessingKey(maxInputLength, locale)
    internal fun matches(chapter: SpeechChapter, synthesis: SpeechSettings, context: SpeechContext, maxInputLength: Int) =
        !trimmed && this.chapter == chapter && this.synthesis == synthesis && this.context == context &&
            this.maxInputLength == maxInputLength && locale == Locale.getDefault()

    /** Resolve the bookmark once, then rebuild audio from its original position inside the sentence. */
    internal fun resumeAt(saved: SpeechBookmark?): Pair<SpeechPlan, Int> {
        val matching = saved?.takeIf { it.bookId == chapter.bookId && it.chapterId == chapter.id &&
            it.fingerprint == chapter.fingerprint && !it.completed && it.offset in 0..chapter.text.length }
            ?: return this to 0
        // Expansion/capture reordering can give several clips overlapping original ranges.
        val index = matching.segmentIndex?.takeIf { matching.replacementKey == replacementKey && it in 0..segments.size }
        // End offsets are exclusive: a durable completion boundary must not replay
        // the finished segment, including whitespace before the next segment.
            ?: segments.indexOfFirst { it.end > matching.offset }.takeIf { it >= 0 } ?: segments.size
        val segment = segments.getOrNull(index) ?: return this to index
        if (matching.offset !in segment.start + 1 until segment.end) return this to index
        val mapped = mappedSegments[index]
        val start = mapped.indexAtOriginal(matching.offset)
        if (start == 0) return this to index
        val remaining = mapped.slice(start, mapped.text.length)
        val range = remaining.range()
        return SpeechPlan(chapter, synthesis, segments.toMutableList().also {
            it[index] = segment.copy(start = range.first, end = range.second, text = remaining.text)
        }, structuredText, mappedSegments.toMutableList().also { it[index] = remaining }, context,
            nextContext, maxInputLength, locale, logId, trimmed = true) to index
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
    characters: SpeechCharacterAssignment? = null,
    detectLanguage: (suspend (String, Set<String>) -> String?)? = null,
    trace: SpeechPlanTrace? = null,
    checkCancelled: () -> Unit): SpeechPlan {
    checkCancelled()
    trace?.input(chapter)
    if (chapter.text.length > 1_000_000) throw SpeechException(SpeechError.UnsupportedContent)
    val settings = synthesis.text
    val locale = Locale.getDefault()
    val original = speechTrim(SpeechMappedText.original(chapter.text))
    // The original service filters before normalization, replacement and emoji expansion.
    if (settings.filterUnspoken && speechIsUnspoken(original.text))
        return SpeechPlan(chapter, synthesis, emptyList(), "", emptyList(), context = context,
            maxInputLength = maxInputLength, locale = locale, logId = trace?.id)
    // R6.i.b requires both selections before parsing any dialogue; i.g only requires narration.
    if (synthesis.hasVoiceRouting) {
        if (synthesis.selectedNarrators.isEmpty()) throw SpeechException(SpeechError.MissingNarrationVoice)
        if (settings.dialogue && synthesis.dialogueVoices.isEmpty()) throw SpeechException(SpeechError.MissingDialogueVoice)
    }
    val replacements = SpeechReplacements(synthesis.replacements, replaceRegex, trace, checkCancelled)
    val normalized = speechNormalize(original, checkCancelled)
    trace?.text(R.string.tts_log_normalized, normalized.text)
    val structured = replacements.apply(normalized, wholeText = true)
    trace?.text(R.string.tts_log_full_replacement, structured.text)
    val routing = SpeechVoiceRouting(synthesis, chapter.bookId, voices, context, maxInputLength)
    val languageRouting = SpeechLanguageRouting(synthesis.roleRules, settings.detectLanguage, detectLanguage)
    val roles = SpeechRoleRules(synthesis.roleRules, synthesis.roleContextPattern ?: "__ROLE__", replaceRegex, checkCancelled)
    suspend fun resolve(name: String, direction: SpeechContextDirection, key: String): SpeechPersonVoice? =
        if (characters != null) characters.resolve(name, direction, key, roles)
        else roles.resolve(name, direction)?.personVoice()
            ?: if (direction == SpeechContextDirection.Explicit) routing.explicit(name) else null
    // Context retains person identity, but an edited/disabled rule must not retain its old voice.
    val inputContext = routing.refreshContext(context) { old ->
        if (characters != null) resolve(old.person, SpeechContextDirection.Explicit, "refresh:${old.person}")
        else if (old.roleMatch != null) roles.refresh(old.roleMatch)?.personVoice()
        else roles.resolve(old.person, SpeechContextDirection.Explicit)?.personVoice() ?: routing.explicit(old.person)
    }
    val structure = speechStructure(structured, settings, inputContext,
        resolveUnit = if (synthesis.hasVoiceRouting || characters != null) ::resolve else null,
        textRules = roles.splitText(structured).map { span ->
            span.copy(match = span.match?.let { characters?.allocate(it) ?: it })
        },
        checkCancelled = checkCancelled)
    val segments = mutableListOf<SpeechSegment>()
    val mappings = mutableListOf<SpeechMappedText>()
    val marks = SpeechMarks()
    val paragraphEnds = if (settings.paragraphPauseMs > 0) chapter.text.indices.filter { chapter.text[it] == '\n' } else emptyList()
    var previousSpoken: SpeechSegment? = null
    var previousParagraph: Int? = null
    fun pause(durationMs: Int, origin: SpeechMappedText) {
        // The original negative duration crashes while allocating PCM. Report it through the existing audio error.
        if (durationMs < 0) throw SpeechException(SpeechError.AudioProcessing)
        if (durationMs == 0) return
        val offset = previousSpoken?.end ?: 0
        segments += SpeechSegment(offset, offset, "", pauseMs = durationMs)
        mappings += origin
    }
    for (part in structure.parts) {
        checkCancelled()
        trace?.structure(part)
        if (part.controlMark) {
            marks.accept(part.content.text)?.let { pause(it, part.content) }
            continue
        }
        var fallback: Pair<SpeechResolvedVoice, SpeechVoiceReason>? = null
        val manual = part.dialogue && (part.personVoice?.roleMatch?.origin == SpeechPersonOrigin.Manual ||
            synthesis.speakerBookId == chapter.bookId && part.speaker in synthesis.speakerVoices)
        val detect = languageRouting.active && part.textRule == null && !manual
        // P.f.i applies Latin separation only to spans not claimed by a text rule.
        val languages = speechLatinSegments(part.content, settings.separateEnglish && part.textRule == null, checkCancelled)
        for (language in languages) {
            val units = if (detect) languageRouting.split(language.content, locale, checkCancelled)
                else listOf(language.content to null)
            for ((unit, detected) in units) {
                checkCancelled()
                val languageMatch = detected?.role?.let { characters?.allocateLanguage(it) ?: it }
                val (voice, reason) = if (languageMatch != null)
                    routing.select(part.copy(textRule = languageMatch)).first to SpeechVoiceReason.Language
                else fallback ?: routing.select(part).also { fallback = it }
                var corrected = replacements.apply(unit, wholeText = false)
                if (settings.replaceEmoji) corrected = SpeechEmojiNames.replace(corrected, checkCancelled, locale)
                val paragraphs = if (paragraphEnds.isNotEmpty()) speechParagraphChunks(corrected, paragraphEnds) else listOf(corrected)
                for (sentence in paragraphs.flatMap { speechSentenceChunks(it, settings.maxSentenceLength, locale, checkCancelled) }) {
                    for (mapped in speechHardChunks(sentence, voice.maxInputLength, checkCancelled)) {
                        if (mapped.text.all { Character.isWhitespace(it) } || settings.filterUnspoken && speechIsUnspoken(mapped.text)) continue
                        val (start, end) = mapped.range()
                        if (start < end) {
                            if (paragraphEnds.isNotEmpty()) {
                                val paragraph = speechParagraphIndex(paragraphEnds, mapped.anchor(0))
                                if (previousParagraph != null && paragraph != previousParagraph) pause(settings.paragraphPauseMs, mapped)
                                previousParagraph = paragraph
                            }
                            segments += SpeechSegment(start, end, mapped.text, part.dialogue, part.speaker,
                                part.speakerHint, detected?.let { "lang:${it.language}" } ?: language.language, part.beforeText, part.afterText,
                                part.rememberedText, part.previousVoice, part.changeVoice, voice, reason,
                                languageMatch ?: part.textRule ?: part.personVoice?.roleMatch?.takeIf { part.dialogue },
                                audioParameters = marks.parameters)
                            previousSpoken = segments.last()
                            mappings += mapped
                            trace?.segment(segments.lastIndex, segments.last())
                        }
                    }
                }
            }
        }
    }
    return SpeechPlan(chapter, synthesis, segments, structured.text, mappings, context,
        if (synthesis.hasVoiceRouting) structure.nextContext.copy(narrationIndex = routing.narrationIndex,
            dialogueIndex = routing.dialogueIndex) else structure.nextContext, maxInputLength, locale, trace?.id)
}

internal fun SpeechSettings.textProcessingKey(maxInputLength: Int, locale: Locale = Locale.getDefault()) =
    speechDigest("speech-text-v5\u0000" + schemeId + "\u0000" + Json.encodeToString(replacements) + "\u0000" +
        Json.encodeToString(text) + "\u0000" + maxInputLength + "\u0000" + locale.toLanguageTag() +
        "\u0000" + Json.encodeToString(narrationVoices) + Json.encodeToString(dialogueVoices) +
        Json.encodeToString(speakerVoices) + speakerBookId + voiceRevision + voiceRoutingConfigured +
        Json.encodeToString(roleRules) + roleContextPattern + Json.encodeToString(voiceItems) + autoAssignRole + maxCacheVoiceNum)

/** Reuse the selected identities, including the installed offline resource revision, without a new schema. */
internal fun SpeechSettings.synthesisSettings(): SpeechSettings {
    val selected = validated().let { it.copy(playbackSpeed = 1f, allowMultipleVoices = false, diagnosticLogging = false,
        schemeName = "", text = it.text.copy(filterLanguage = false), backgroundMusic = SpeechSchemeMusic()) }
    if (selected.hasVoiceRouting) return selected
    return when {
        selected.offlineVoice != null -> selected.copy(engine = "", voice = "", rate = selected.rate ?: 1f, httpVolume = 1f)
        selected.httpSource != null -> selected.copy(engine = "", voice = "")
        else -> selected.copy(httpVolume = 1f)
    }
}
