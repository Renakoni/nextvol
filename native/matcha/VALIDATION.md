# #791 acceptance

Validation date: 2026-10-29 (host local date).

## Build and provenance

- Built ONNX Runtime from the inherited #793 source tree, then built the selected
  Sherpa 1.12.10 sources and this JNI adapter. No downloaded runtime binary was
  linked or packaged.
- All 368 vendored files match the original fixed-version source trees after
  reversing the recorded patch (Git's line-ending conversion was normalized for
  this comparison). The build verifies the 369-file vendor/patch inventory.
- `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, opt-in
  `:app:assembleDebug`, and `:app:lintDebug` passed. Lint reports warnings but no
  errors; the adb probe uses Android Log, which triggers the advisory
  `LogNotTimber` rule.
- JVM regression tests were compiled, not executed locally, per the repository's
  rule to leave overlapping test execution to CI. Three focused tests cover
  cancelled initialization and the instance slot, destruction during synthesis,
  and a readable non-empty resource symlink escaping the installation.
- ELF inspection confirmed AArch64, `0x4000` LOAD alignment, no RPATH/RUNPATH,
  static libc++, and only ONNX Runtime plus Android system shared dependencies.
- The packaged libraries and build manifest were compared byte-for-byte with
  the source-build outputs. `zipalign -c -P 16 4` passed on the APK.

| Artifact | SHA-256 |
| --- | --- |
| Vendor/patch inventory | `2d608e84f30d1a575fee3be89bc6607e0f66e529446fc779be375981c59fe734` |
| `libnextvol-matcha.so` (9,031,248 bytes) | `3d5d4f3fdec2d11a33e0b216613131b224469c12862929b0ed4a11549a11d69d` |
| `libonnxruntime.so` | `eb5887b2fa1734a471af760f0002e77d42a8f44de71ef032fbc55bb124317f50` |
| External acoustic model | `afcf35609e5698e8a06bd30482ac1e47dfbbb9003d10a2eb837541b42572ff02` |
| External HiFi-GAN vocoder | `a41d404cce7924493540238da5b30a4bc14b6ddaf1a37f3c79fa4f59548c19f0` |

## Device scope

The controlled entry ran in `indi.renakoni.nextvol.matchaprobe` on MuMu at
`127.0.0.1:16448`, Android API 32, using its arm64 translation support and 4 KiB
pages. The independent application ID lets the probe coexist with normal debug
installations. No physical phone or NPU workload was used.

These are single acceptance observations, not a performance benchmark or a
16 KiB-device qualification. Cancellation is cooperative between native sentence
batches; a current graph or initializer must finish before deletion. PSS includes
the whole application and emulator translation overhead, not just model memory.
The session replacement check uses the real `ReadAloudSession` and a capturing
playback sink, validating the delivered WAV and rejecting stale clips.

## Results from the isolated application

| Scenario | Observed result |
| --- | --- |
| Chinese sample | Initialization 7,651 ms; complete-file synthesis 3,905 ms; release 213 ms |
| Cancel initialization | Cancel-to-completion including cleanup 5,639 ms; a fresh instance then generated a valid WAV |
| Cancel synthesis | Cancel-to-completion 980 ms; release 327 ms; no cancelled WAV or `.part`; fresh-instance synthesis passed |
| Stop and replace a session during synthesis | Only the replacement clip was delivered; zero stale clips |
| Corrupt acoustic model | `EngineUnavailable`, followed by successful synthesis with valid resources |
| HiFi-GAN graph supplied as the acoustic model | Graph signature rejected as `EngineUnavailable`, followed by successful synthesis with valid resources |

The sample text was “这是一次离线语音验证。我们正在使用导入的语音包，在应用内部生成声音。”
The captured file was PCM16 mono at 22,050 Hz, 141,704 frames (6.426485 s),
283,452 bytes, normalized RMS 0.062267 and peak 0.439301. Its SHA-256 was
`8cfe2dd1e753d16d32afe2b0024b97b28817b637b6245ce4c46d56d7ddb16951`.
Matcha synthesis is stochastic; this hash identifies the observed artifact,
not an expected byte-identical output for future runs.

Whole-process PSS was 460,664 KiB after initialization and 251,978 KiB after
release in the sample run. The one-instance bound is enforced by the adapter's
semaphore, while the existing session's bounded file queue is retained. These
two memory observations are not a peak-memory measurement or a leak test.
