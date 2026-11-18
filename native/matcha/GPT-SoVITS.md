# GPT-SoVITS offline speech

The app exposes `SpeechSynthesizers.gptSovits(voice)` for individual clips and
`downloadGptSovitsChapter(voice, chapter, output)` for a complete chapter. The
chapter writer takes a processed `SpeechChapter`, reuses a single model session,
and publishes a 32 kHz mono PCM16 WAV at a new destination. Cancel the calling
coroutine to stop generation. For the clip API, close the synthesizer after use.

`GptSovitsVoice` takes a local root, a V2 Pro or V2 Pro Plus character ID, a
reference recording (up to 30 seconds) and its Mandarin transcript. The backend supports Android 10
or newer. MuMu's x86 Houdini translator cannot load the runtime's thread-local
state during model initialization, so this backend reports unavailable there.
The Mandarin frontend uses the Voicebox normalization, segmentation,
contextual pronunciation, tone sandhi and erhua before RoBERTa alignment.
On arm64 the default is CPU + WebGPU. Reference encoding and autoregressive
decoding use the CPU provider in the bundled ORT 1.27 build. The acoustic stage uses
WebGPU, with CPU fallback if the adapter or graph fails. Both the clip
and chapter entry points use this policy.

```text
<root>/
  CharacterModels/{v2Pro,v2ProPlus}/<character>/tts_models/
    prompt_encoder_fp32.onnx
    t2s_encoder_fp32.onnx
    t2s_first_stage_decoder_fp32.onnx
    t2s_stage_decoder_fp32.onnx
    vits_fp32.onnx
    <external weights referenced by these graphs>
  RuntimeAssets/
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
`speed` receives unit speed before NextVol's audio processing.

## GPU and NPU

For standard V2 Pro and V2 Pro Plus exports the app supplies weight-free graph
templates. Before writing a derived graph alongside the weights it verifies the
source VITS SHA-256. The HTP graphs are prepared when the device probe succeeds.
A separately prepared local voice may also include Voicebox's hash-bound `split-vits.json`,
`split-encoder.onnx` and `split-hifigan.onnx`. In this layout the encoder runs on
CPU and the FP32 HiFiGAN runs on WebGPU. For an original unsplit export the
backend requests WebGPU on the original VITS graph. On a provider failure the
CPU vocoder reuses the semantic tokens already produced for that segment.

After the first frame of the app, an IO worker runs the Qualcomm HTP arithmetic
probe with CPU fallback disabled. An earlier speech request shares the same
probe. The result is cached against the system build and APK update. Compatible voices with
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
buffers across runtime boundaries. Gradle packages its matching Qualcomm
2.42.0 libraries from Maven Central; the installed app works offline.

The Mandarin frontend and dictionaries follow Voicebox. The OpenCPOP syllable mapping and V2 symbol
indices come from GPT-SoVITS commit `6d51ffceafbf240145619af4c92408a6e54b7924`.
The asset directory includes the third-party notices. The model layout and reference
conditioning follow the V2 flow in Voicebox. Model weights are supplied locally.

- https://github.com/RVC-Boss/GPT-SoVITS/tree/6d51ffceafbf240145619af4c92408a6e54b7924/GPT_SoVITS/text
- https://github.com/Renakoni/Voicebox/tree/7963c3511b92271bab89f5dacd39eb4a3ab71b9a/Android/app/src/main/java/dev/rinstel/genie_tts/inference
