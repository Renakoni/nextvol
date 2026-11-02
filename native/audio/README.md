# Speech PCM processing

`PcmAudioProcessor` applies the MultiTTS 1.8.3 processor selection to mono PCM16:
200 is the upstream Java Sonic implementation; 201 is SoundTouch 2.3.2, built
from source into `libnextvol-audio.so` for all four Android ABIs. Source origins,
versions, modifications and licenses are recorded in `sources.json`.

The native build uses the application's NDK 28.2.13676358, API 24 and 16 KiB ELF
load alignment. It is independent of Sherpa and uses the existing
`matchaPython`, `matchaNdk`, `matchaCmake` and `matchaNinja` tool locations.
Libraries are built by `:app:buildSpeechAudioNative`; the separate
`:app:prepareSpeechAudioLicenses` task packages the source licenses.

The caller feeds bounded decoded blocks, calls `finish()` once at stream end,
and closes the processor on completion or cancellation. Default parameters at
the original sample rate bypass DSP. SoundTouch availability errors propagate;
the selected algorithm is never silently replaced.

MultiTTS's `H6.b` selects the processor and `c2.a.w` multiplies speed, pitch and
volume, resamples through the input/output rate ratio, and shortens a wholly
quiet output block (every PCM16 sample has magnitude at most 512) to one quarter
of its length. This is not leading/trailing silence trimming. Explicit pauses
are already in the output sample rate and bypass DSP and silence compression.
`lastUncompressedFrames` exposes the pre-compression output length for timing.

Two details are adapted for file-based processing: the APK's SoundTouch
flush/clear methods have no static callers, while our stream end drains the
buffer; and the original small-block `0xFFFE` byte-length mask is not used for
larger arrays. Static evidence alone does not establish audible tail loss in the APK.
SpeexBridge resampling/denoising stubs are not part of this processing path.

Sonic's fixed-point volume multiplication uses a `long` intermediate: the
upstream `int` multiplication can overflow before clipping for large imported
volume values (for example, sample 10000 at volume 64).

Original APK SHA-256:
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`.
REA method evidence:
`ev_d8feddcf3b2cbc764d7db52111d1dfe5988cdc1fa7b707053177ad5cd4a9c38e`
(selection),
`ev_a49898c7b3fec39e97a2bc0b344f8a438cd76341e74baf04d7a6d0536113e520`
(processing),
`ev_808a18bab697142d3e683c3e3109c3cbe2929249eaa04c132bf5e8f77306fc16`
(no static flush caller).
