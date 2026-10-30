# #791 acceptance

Validation date: 2026-10-29 (host local date).

## Build and provenance

- Built ONNX Runtime from the inherited #793 source tree, then built the selected
  Sherpa 1.12.10 sources and this JNI adapter. No downloaded runtime binary was
  linked or packaged.
- All 242 retained vendored files match the original fixed-version source trees after
  reversing the recorded patch (Git's line-ending conversion was normalized for
  this comparison); all four source archive SHA-256 values were checked.
  Compiler dependency records identified 126 unused files (714,232 bytes),
  which were removed. A fresh configure using only local sources passed.
  The build verifies the 243-file vendor/patch inventory.
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
| Vendor/patch inventory | `875d94874ceddd2afdd270c648ac8413c3d8b598cac150c79ab7e1cf4521133a` |
| `libnextvol-matcha.so` (9,031,256 bytes) | `2c4346b5669932f4aead88cf17b5d9082e08ab89e285161505484a039a9d2bdb` |
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

After pruning, the rebuilt APK produced a valid 22,050 Hz mono PCM16 WAV:
141,007 frames (6.394875 s), 282,058 bytes, normalized RMS 0.064084 and SHA-256
`881ae94ea56a3207c2c36fb1c917f8d2cdf5f1f942fc0032bbda61c73cfc08c3`.
A malformed `tokens.txt` then returned `EngineUnavailable`; restoring the resource
allowed another successful synthesis. The focused native reproducer exited 255
before the patch and caught the exception normally afterward. An injected ELF
export failure also confirmed that no success manifest is left behind.

The measurements below are the original lifecycle acceptance run. Its one-off
probe modes were subsequently removed; they are not part of the current debug
entry. The three lifecycle/resource JVM tests remain.

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

# #794 ordinary-package acceptance

Validation date: 2026-10-30 (host local date). MuMu A, API 32, 4 KiB pages.
The ordinary `indi.renakoni.nextvol.debug` APK was installed without an ABI
override or native feature flag; Android selected `arm64-v8a`. No probe activity,
external TTS app or adb model injection was used for this acceptance.

The system document picker opened the real external Sherpa Matcha/Kokoro ZIP
(817,152,513 bytes). Preview found 1 engine, 66 voices and 1,113 resource files.
Installing Baker copied only its 12 required files (92,568,146 bytes), verified
the graph hashes above, and retained the previously selected HTTP voice.
The catalog used voice ID
`e0545b1de5fc60d854ee78bc104a0baa6b3eeca844235602eefd4a1713760291`
and resource revision
`b1994ef1f7d8bafb43aa4c60bc19da27cc817dfde9ee81545015cb1360a11974`.

| Scenario | Observed result |
| --- | --- |
| Explicit Baker audition | Shared Media3 player completed a 22,050 Hz mono PCM16 file: 126,452 frames, normalized RMS 0.071844 |
| Persisted selection | Baker remained selected after app force-stop and emulator restart |
| Offline chapter | With Wi-Fi disabled and Android reporting no active default network, cached chapter “第一章 黑与白” in “魔法少女育成计划” synthesized and played through the ordinary reader |
| Playback controls | Media session changed from playing to paused and back; next-segment synthesis resumed; stopping removed the audio cache |
| Missing resource | Temporarily renaming `tokens.txt` showed its filename and disabled audition; restoring it made the voice usable |
| Cancel installation | Leaving during extraction removed staging files and published no voice |
| Duplicate import | Successful re-import retained one catalog entry and the same ID/revision |
| Delete during native initialization | Catalog became empty while the leased model still existed; after native cleanup only the catalog remained, with no audio files; re-import succeeded |
| Unsupported models | English Matcha and Kokoro stayed visible with unsupported-model/parameter explanations and no install action |
| Unsupported process ABI | The same APK installed with `--abi x86_64`; Baker selection/audition were disabled with a support explanation; existing “豆包 · 通用女声” HTTP selection and audition still worked |

The first captured offline chapter file contained 470,238 frames (21.325986 s),
940,520 bytes and normalized RMS 0.059500. Its SHA-256 was
`b2c6b721a3c60c5575794b82eefa5a65c1bd94047976840586fb3444c3d32bd6`.
Two further captured chapter clips were also valid 22,050 Hz mono WAVs.
These are waveform and player-state observations, not a subjective audio-quality
assessment. Wi-Fi and the ordinary arm64 installation were restored afterward.

Fresh native source build, debug/release assembly, debug lint, app unit-test
compilation and source-speech test compilation passed. Lint reported 452 warnings
and 8 hints, with no errors. Resource contracts and action YAML were checked.
APK library hashes matched the packaged source manifest; dependency licenses
were present and no ONNX weights were embedded. ELF LOAD alignment was 16 KiB,
including the three API-24 ABI markers; debug APK 16 KiB zip alignment passed.

Regression tests cover static path/parameter mapping, selected-resource changes,
unknown graph rejection, catalog persistence, idempotent imports, replacement and
deletion leases, and Android-style app-directory aliases. They were compiled,
not executed locally, under the repository's CI-only test-execution rule.
Physical phones, API 24 runtime behavior, 16 KiB devices and release-runtime
playback were not exercised here. Book-switch behavior retains the existing
session implementation and its tests; this device run exercised one cached book.
