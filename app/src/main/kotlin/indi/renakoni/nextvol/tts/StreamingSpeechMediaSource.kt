package indi.renakoni.nextvol.tts

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlinx.coroutines.runBlocking

/** The service's existing ExoPlayer consumes the processed PCM; there is no second audio output. */
internal fun streamingSpeechMediaSource(item: MediaItem, stream: SpeechStream): ProgressiveMediaSource =
    ProgressiveMediaSource.Factory({ SpeechPcmDataSource(stream) }, ExtractorsFactory { arrayOf(SpeechPcmExtractor(stream.sampleRate)) })
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
        .createMediaSource(item)

private class SpeechPcmDataSource(private val stream: SpeechStream) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var block = byteArrayOf()
    private var offset = 0

    override fun open(dataSpec: DataSpec): Long {
        check(dataSpec.position == 0L)
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (this.offset == block.size) {
            val next = runBlocking { stream.blocks.receiveCatching() }
            next.exceptionOrNull()?.let { throw java.io.IOException("Speech stream failed", it) }
            block = next.getOrNull() ?: return C.RESULT_END_OF_INPUT
            this.offset = 0
        }
        val count = minOf(length, block.size - this.offset)
        block.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        bytesTransferred(count)
        return count
    }

    override fun getUri() = uri
    override fun close() {
        stream.cancel()
        block = byteArrayOf()
        if (uri != null) { uri = null; transferEnded() }
    }
}

private class SpeechPcmExtractor(private val sampleRate: Int) : Extractor {
    private lateinit var track: TrackOutput
    private var bytes = 0L

    override fun sniff(input: ExtractorInput) = true
    override fun init(output: ExtractorOutput) {
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(sampleRate)
            .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build())
        output.endTracks()
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val count = track.sampleData(input, 8192, true)
        if (count == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT
        track.sampleMetadata(bytes / 2 * 1_000_000 / sampleRate, C.BUFFER_FLAG_KEY_FRAME, count, 0, null)
        bytes += count
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) { bytes = 0 }
    override fun release() = Unit
}
