package indi.renakoni.nextvol.tts

import kotlinx.serialization.json.Json

/** One chapter's synthesis input and original UTF-16 ranges, shared by every session consumer. */
class SpeechPlan internal constructor(
    val chapter: SpeechChapter,
    val synthesis: SpeechSettings,
    val segments: List<SpeechSegment>,
    val structuredText: String = chapter.text,
    private val mappedSegments: List<SpeechMappedText> = emptyList(),
) {
    private val replacementKey = speechDigest("speech-replacements-v1\u0000" + Json.encodeToString(synthesis.replacements))
    internal fun matches(chapter: SpeechChapter, synthesis: SpeechSettings) =
        this.chapter == chapter && this.synthesis == synthesis

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
        replacementKey = replacementKey.takeIf { index != null }, segmentIndex = index)

    internal fun position(index: Int, start: Int = 0, end: Int = segments[index].text.length, anchor: Int = start): SpeechPosition {
        val segment = segments[index]
        val mapped = mappedSegments.getOrNull(index)
        val range = mapped?.range(start, end) ?: (segment.start + start to segment.start + end)
        val originalAnchor = mapped?.anchor(anchor) ?: (segment.start + anchor)
        return SpeechPosition(chapter.bookId, chapter.id, chapter.fingerprint, range.first, range.second,
            originalAnchor.coerceIn(range.first, range.second - 1))
    }
}

/** No plan is built by ordinary reading. Preparation is cancellable and limited to one bounded chapter. */
internal suspend fun prepareSpeechPlan(chapter: SpeechChapter, synthesis: SpeechSettings,
    replaceRegex: (suspend (String, String, String) -> List<hnovel.rules.ReplacementPart>)? = null,
    checkCancelled: () -> Unit): SpeechPlan {
    checkCancelled()
    if (chapter.text.length > 1_000_000) throw SpeechException(SpeechError.UnsupportedContent)
    // Preserve the existing single-voice plan when no replacement has been enabled.
    if (synthesis.replacements.none { it.activate })
        return SpeechPlan(chapter, synthesis, speechSegments(chapter.text, checkCancelled = checkCancelled))
    val replacements = SpeechReplacements(synthesis.replacements, replaceRegex, checkCancelled)
    val structured = replacements.apply(SpeechMappedText.original(chapter.text), wholeText = true)
    val segments = mutableListOf<SpeechSegment>()
    val mappings = mutableListOf<SpeechMappedText>()
    for (part in speechStructure(structured, checkCancelled)) {
        checkCancelled()
        // The current plan uses one selected voice. Structure/person clues are fixed before correction;
        // later per-part voice/language selection consumes this same boundary.
        val corrected = replacements.apply(part.content, wholeText = false)
        for (segment in speechSegments(corrected.text, checkCancelled = checkCancelled)) {
            val mapped = corrected.slice(segment.start, segment.end)
            val (start, end) = mapped.range()
            if (start < end) {
                segments += segment.copy(start = start, end = end, dialogue = part.dialogue, speaker = part.speaker)
                mappings += mapped
            }
        }
    }
    return SpeechPlan(chapter, synthesis, segments, structured.text, mappings)
}

/** Reuse the selected identities, including the installed offline resource revision, without a new schema. */
internal fun SpeechSettings.synthesisSettings(): SpeechSettings {
    val selected = validated().copy(playbackSpeed = 1f)
    return when {
        selected.offlineVoice != null -> selected.copy(engine = "", voice = "", pitch = null, rate = selected.rate ?: 1f)
        selected.httpSource != null -> selected.copy(engine = "", voice = "", pitch = null)
        else -> selected
    }
}
