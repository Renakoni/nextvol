package indi.renakoni.nextvol.tts

/** One chapter's synthesis input and original UTF-16 ranges, shared by every session consumer. */
class SpeechPlan internal constructor(
    val chapter: SpeechChapter,
    val synthesis: SpeechSettings,
    val segments: List<SpeechSegment>,
) {
    internal fun matches(chapter: SpeechChapter, synthesis: SpeechSettings) =
        this.chapter == chapter && this.synthesis == synthesis

    internal fun startIndex(saved: SpeechBookmark?): Int {
        val offset = saved?.takeIf { it.bookId == chapter.bookId && it.chapterId == chapter.id &&
            it.fingerprint == chapter.fingerprint && !it.completed && it.offset in 0..chapter.text.length }?.offset ?: 0
        // End offsets are exclusive: a durable completion boundary must not replay
        // the finished segment, including whitespace before the next segment.
        return segments.indexOfFirst { it.end > offset }.takeIf { it >= 0 } ?: segments.size
    }

    internal fun bookmark(offset: Int) = SpeechBookmark(chapter.bookId, chapter.id,
        chapter.fingerprint, offset, chapter.bookTitle, chapter.title)
}

/** No plan is built by ordinary reading. Preparation is cancellable and limited to one bounded chapter. */
internal fun prepareSpeechPlan(chapter: SpeechChapter, synthesis: SpeechSettings, checkCancelled: () -> Unit): SpeechPlan {
    checkCancelled()
    if (chapter.text.length > 1_000_000) throw SpeechException(SpeechError.UnsupportedContent)
    return SpeechPlan(chapter, synthesis, speechSegments(chapter.text, checkCancelled = checkCancelled))
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
