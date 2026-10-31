package indi.renakoni.nextvol.tts

import androidx.annotation.StringRes
import indi.renakoni.nextvol.R

val SpeechError.messageResource: Int
    @StringRes get() = when (this) {
        SpeechError.NoEngine -> R.string.tts_error_no_engine
        SpeechError.EngineUnavailable -> R.string.tts_error_engine
        SpeechError.MissingVoice -> R.string.tts_error_voice
        SpeechError.MissingLanguageData -> R.string.tts_error_language
        SpeechError.InitializationTimeout -> R.string.tts_error_init_timeout
        SpeechError.SynthesisTimeout -> R.string.tts_error_synthesis_timeout
        SpeechError.SynthesisFailed -> R.string.tts_error_synthesis
        SpeechError.Network -> R.string.tts_error_network
        SpeechError.InvalidAudio -> R.string.tts_error_audio
        SpeechError.Storage -> R.string.tts_error_storage
        SpeechError.SourceUnavailable -> R.string.tts_error_source
        SpeechError.SourceTimeout -> R.string.tts_error_source_timeout
        SpeechError.EmptyText -> R.string.tts_error_empty
        SpeechError.UnsupportedContent -> R.string.tts_error_content
        SpeechError.Playback -> R.string.tts_error_playback
        SpeechError.ServiceUnavailable -> R.string.tts_error_service
        SpeechError.HttpSourceUnavailable -> R.string.tts_error_http_source
        SpeechError.HttpPermission -> R.string.tts_error_http_permission
        SpeechError.HttpUnsupported -> R.string.tts_error_http_unsupported
        SpeechError.HttpLogin -> R.string.tts_error_http_login
        SpeechError.OfflineUnavailable -> R.string.tts_offline_unavailable
    }

val SpeechPhase.labelResource: Int
    @StringRes get() = when (this) {
        SpeechPhase.Stopped -> R.string.tts_stopped
        SpeechPhase.Preparing -> R.string.tts_preparing
        SpeechPhase.Playing -> R.string.tts_playing
        SpeechPhase.Paused -> R.string.tts_paused
        SpeechPhase.Buffering -> R.string.tts_buffering
        SpeechPhase.Completed -> R.string.tts_completed
        SpeechPhase.Failed -> R.string.tts_failed
    }
