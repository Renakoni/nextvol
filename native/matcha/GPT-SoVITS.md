# GPT-SoVITS offline speech

The app exposes `SpeechSynthesizers.gptSovits(voice)` for individual clips and
`downloadGptSovitsChapter(voice, chapter, output)` for a complete chapter. The
chapter writer takes a processed `SpeechChapter`, reuses a single model session,
and publishes a 32 kHz mono PCM16 WAV at a new destination. Cancel the calling
coroutine to stop generation. For the clip API, close the synthesizer after use.

`GptSovitsVoice` takes a local root, a V2 Pro or V2 Pro Plus character ID, a
reference recording (up to 30 seconds) and its Mandarin transcript. The initial
text frontend uses Android ICU for Mandarin and requires Android 10 or newer.
The native path uses the app's existing arm64 CPU ONNX Runtime.

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

The Mandarin phone table uses the OpenCPOP syllable mapping and V2 symbol
indices from GPT-SoVITS commit `6d51ffceafbf240145619af4c92408a6e54b7924`.
Its MIT license is included with the asset. The model layout and reference
conditioning follow the V2 flow in Voicebox. Model weights are supplied locally.

- https://github.com/RVC-Boss/GPT-SoVITS/tree/6d51ffceafbf240145619af4c92408a6e54b7924/GPT_SoVITS/text
- https://github.com/Renakoni/Voicebox/tree/7963c3511b92271bab89f5dacd39eb4a3ab71b9a/Android/app/src/main/java/dev/rinstel/genie_tts/inference
