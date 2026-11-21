# GPT-SoVITS offline speech

Imported voices use the normal `SpeechSynthesizers.synthesizer()` and reader
playback pipeline, including its settings, PCM processing and cancellation.

`GptSovitsVoice` takes a local root, a V2 Pro or V2 Pro Plus character ID, a
reference recording (up to 30 seconds) and its transcript in Chinese, Japanese or English. The backend supports Android 10
or newer and a working GPU backend, established by the first-start inference probe.
The Mandarin frontend uses the Voicebox normalization, segmentation,
contextual pronunciation, tone sandhi and erhua before RoBERTa alignment.
On arm64 the default is CPU + WebGPU. Reference encoding and autoregressive
decoding use the CPU provider in the bundled ORT 1.27 build. The acoustic stage uses
WebGPU, with CPU fallback if the adapter or graph fails.

```text
<root>/
  CharacterModels/{v2Pro,v2ProPlus}/<character>/tts_models/
    prompt_encoder_fp32.onnx
    t2s_encoder_fp32.onnx
    t2s_first_stage_decoder_fp32.onnx
    t2s_stage_decoder_fp32.onnx
    vits_fp32.onnx
    <external weights referenced by these graphs>
<shared-models>/
  chinese-hubert-base/chinese-hubert-base.onnx
  chinese-hubert-base/chinese-hubert-base_weights.bin
  speaker_encoder.onnx
  roberta-wwm-ext-large-onnx/model.onnx
  roberta-wwm-ext-large-onnx/roberta_tokenizer/tokenizer.json
```

Use a separate model directory for each installed revision. Standard packed FP16
external weights for the prompt encoder, shared decoder, VITS and HuBERT are
expanded beside their graphs on first open, so these directories must be writable
when preparation is needed. The speech encoder, first decoder, cached decoder and
vocoder use the V2 ONNX graph inputs and outputs. Both decoder exports with fixed
sampling and exports with a `sampling` input are supported. A vocoder exposing
`speed` receives the voice speed multiplied by the reading rate mapped from
`0 / 1 / 2` to `0.6 / 1 / 1.65`, clamped to `0.6..1.65`. Reading pitch is ignored;
volume remains a PCM gain adjustment.

## Reader segmentation

At an effective model speed of 0.8× through 1.2×, the reader sizes each request
when the voice is ready. A request stays inside the current routed text segment,
after replacements, role selection and pause marks. The existing sentence and
clause rules can group short sentences. Mandarin prose with punctuation keeps
its clauses intact when fitting the timing budget. Within the same routed segment,
a comma phrase of up to three Han characters can join the adjacent clause.
The model cap remains 120 UTF-16 units.

Cold start assumes five UTF-16 units per second at 1× and an RTF of 0.75.
With an empty buffer the target is two seconds of speech, about 8 to 12 units
across the supported adaptive speeds. Playback can begin as soon as the first
processed WAV is ready. Model opening time is additional.

For later requests, the budget is the duration of ready files and pauses minus
the current playback position and a 300 ms reserve. Divide that budget by the
estimated generation milliseconds per unit. For example, two seconds ready at
1× and RTF 0.75 give about 11 units. Six seconds ready give about 38.
The target stays between one and twelve seconds of estimated speech. If less
than two seconds remain and the measured cost reaches real time, the target
floor rises to four seconds to amortize per-request work. An intact clause,
word or control mark can exceed the target. The first WAV after a voice or tempo
change updates only the audio duration estimate, since it can include backend
preparation. Subsequent WAVs update both generation cost and audio duration
per unit, averaging the previous estimate with the new observation.

One model request runs alongside playback. The usual queue holds two files.
While less than six seconds of ready audio remain, short GPT-SoVITS clips can
fill up to six queue slots, plus the playing clip and one producer in flight.
Playback bookmarks retain the logical segment and its transformed-text offset,
including when a replacement expands one source character into several clips.
Other model speeds use the existing chapter segments.

With detailed speech logging enabled, `adb logcat -s NextVolSoVITS:D` reports
the selected text length, ready audio at selection, generation time through PCM
processing and final WAV duration. Compare generation time with the buffer
available at selection to investigate audible gaps on a device.

## GPU and NPU

For standard V2 Pro and V2 Pro Plus exports the app supplies weight-free graph
templates. Before writing a derived graph alongside the weights it verifies the
source VITS SHA-256. The HTP graphs are prepared when the device probe succeeds.
A separately prepared local voice may also include Voicebox's hash-bound `split-vits.json`,
`split-encoder.onnx` and `split-hifigan.onnx`. In this layout the encoder runs on
CPU and the FP32 HiFiGAN runs on WebGPU. For an original unsplit export the
backend requests WebGPU on the original VITS graph. On a provider failure the
CPU vocoder reuses the semantic tokens already produced for that segment.

After the first frame of the app, an IO worker runs a 234-byte arithmetic graph
on WebGPU and Qualcomm HTP with CPU fallback disabled. The graph and inference
backends are in the APK; no runtime download is needed. An earlier speech request
shares the same probe. Results persist in app-private, non-backed-up storage
across app updates. Unsupported GPUs disable GPT downloads and hide its tab.
Device labels are read once from Vulkan physical-device properties and the bundled
QNN backend's `deviceGetPlatformInfo` (HTP architecture), then cached alongside
the probe results. An existing capability cache only needs the metadata query;
it does not repeat inference. Missing metadata displays an unknown model and does
not change support. Android product/SoC strings are never used as accelerator names.
Vulkan labels describe the devices exposed by the driver, not an ORT adapter handle.
`acceleration_info.cpp` uses only the metadata ABI prefix from QNN 2.42.0.251225
(core API 2.32); its declarations must be checked against the SDK when upgrading
the bundled backend. Other ABI versions return unknown metadata.
Supported GPU and NPU backends default to enabled. Playback settings can disable
NPU, or disable GPU after a warning and allow CPU synthesis. Compatible voices with
`v2-htp-{stem,body,head}.onnx`
automatically use the NPU for latents of at least 192 frames. Shorter utterances
continue on GPU. HTP uses 64-frame windows with eight frames of context and
FP32 stem/head stages. V2 Pro uses 512/16 stem/body-output channels and V2 Pro
Plus uses 768/24. A failed HTP context falls back to the GPU for that voice.

To prepare the optional graphs from an exported voice on a workstation with
Python and ONNX installed, run the copied V2 tools against
its `tts_models` directory:

```sh
python native/sovits/split_vits_acoustic.py --model-dir <tts_models>
python native/sovits/split_v2_htp.py <tts_models>
```

The APK includes the arm64 runtime from Voicebox's V2 WebGPU build of ORT
1.27.0 (input AAR SHA-256
`db6d7eba5c8c2f1debc61874b2e9abafd6b581a35c679ff5390b0442de89e698`).
`native/sovits/package_webgpu.py` reproduces the slim AAR from that build and
the matching ORT source notices. It gives WebGPU its own library name so the
existing CPU runtime and Java WebGPU binding can coexist. The HTP bridge opens
the separate Microsoft QNN 1.27.0 runtime by absolute path and passes float
buffers across runtime boundaries. The APK also contains the matching Qualcomm
2.42.0 SDK libraries from Maven Central. Device drivers alone do not replace these
libraries. Probes and synthesis share the same backends.

The JNI bridge, including Open JTalk, is compiled with the APK. The on-demand
runtime installer includes graph templates and pronunciation data, including the
English and Japanese dictionaries, in one 34 MB ZIP. Converted voices live separately.

The English CMU/name lexicons, homograph tagger, fallback G2P model and Japanese
Open JTalk frontend follow Voicebox. The source archive preserves the original
Open JTalk/MeCab sources and licenses from its Android model module. The data
archive includes the dictionaries' and models' notices.

The Mandarin frontend and dictionaries follow Voicebox. The OpenCPOP syllable mapping and V2 symbol
indices come from GPT-SoVITS commit `6d51ffceafbf240145619af4c92408a6e54b7924`.
The asset directory includes the third-party notices. The model layout and reference
conditioning follow the V2 flow in Voicebox. Speaker weights are supplied locally.

## Importing a voice

Installing both the speech runtime and GPT-SoVITS base models on a GPU-supported
device pins a GPT-SoVITS tab immediately after System speech,
including when it has no voices. Its menu opens the weight importer. Enter a voice
name, select the matching GPT `.ckpt`, SoVITS `.pth` and reference audio in the
system document picker, then enter the transcript or use the audio filename
without its final extension. Automatic reference language detection uses the
transcript (kana, Han characters, then Latin letters). Select Japanese manually
for a Japanese transcript written entirely in kanji. The selected language is
saved with the voice. Chapter text defaults to Chinese, including Chinese
number and measurement readings. English words use English phones. English and
Japanese phones receive zero BERT features alongside the Chinese RoBERTa spans. Android filters by
MIME type and can offer both binary weight files in either picker. Each pick
replaces the file in its field. A mismatched extension shows the required suffix
in the filename line and disables import until corrected. The app
validates and converts V2 Pro / V2 Pro Plus checkpoints locally using the Voicebox
checkpoint reader, tensor conversion and graph templates from the commit below.
Other architectures, including V5, are rejected before template access.
The reader retains Zip4j 2.11.5: Android's standard `ZipFile` rejects some real
PyTorch ZIP64 checkpoints with `invalid CEN header`.

The app packages four common ONNX graphs under `assets/gpt-sovits/graphs` and
a VITS graph and tensor manifest per variant under `checkpoint-v2pro{,plus}`.
The installed speech runtime supplies the GPU and HTP graphs. The separate base-model
entry in Speech runtimes installs the five files pinned in `runtime-models.json`
(about 973 MB), SHA-256 checked and atomically published under
`noBackupFilesDir/speech-runtimes/sovits-models`. Imports reuse these files and
never download shared models. The converter applies the template's FP16 rounding while writing
each FP32 tensor directly to the file used by its graph. The reference audio
must decode successfully and the selected reference frontend must accept the transcript
before the catalog publishes the voice. Cancellation or failure removes the
staged speaker. Installed base models remain available for later imports.
Converted voices use normal offline selection, audition, editing, copying,
deletion and resource leases.

- https://github.com/Renakoni/Voicebox/tree/071110d052e5ffb66be4f53d6c13b663123c1b05/Android/model/gptsovits/src/main
- https://github.com/RVC-Boss/GPT-SoVITS/tree/6d51ffceafbf240145619af4c92408a6e54b7924/GPT_SoVITS/text
- https://github.com/Renakoni/Voicebox/tree/7963c3511b92271bab89f5dacd39eb4a3ab71b9a/Android/app/src/main/java/dev/rinstel/genie_tts/inference
