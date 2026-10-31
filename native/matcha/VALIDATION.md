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

# #794 general offline-package acceptance

Validation date: 2026-10-31 (host local date). This section supersedes the earlier
Baker-only #794 acceptance. The #791 measurements above describe that historical
backend; their model identifiers are not import requirements.

## Package and runtime behavior

- Static parsing accepted all 49 caller-supplied ZIPs and resolved installation
  plans for 1,589 voices across 16 engine families. This does not mean that every
  voice was synthesized or that arbitrary future SDK formats are supported.
- Import and installation use no model hash, CRC comparison or fixed-model
  allowlist. Paths, selected resources and actual byte lengths are checked;
  successful imports receive fresh random revisions. The SDK still interprets
  its own model format and package license parameters.
- Engine definitions have an independent entry in listening settings. Importing
  another voice preserves user-edited engine configuration; HTTP engine edits
  apply to all voices under the same imported engine code.
- The ordinary build fetches `offline-runtime-1` and packages 42 vendor libraries
  and runtime defaults, alongside the source-built Sherpa/ONNX backend and the
  official Microsoft client AAR. The runtime ZIP is 77.61 MiB; its native
  libraries are about 201.55 MiB uncompressed. No voice weights are bundled.
- The adapters synthesize complete WAV files for the existing application player.
  No Android TTS service is exposed to other apps.

## Device evidence

MuMu A, Android API 32, arm64 translation, 4 KiB pages. The normal debug package
is `indi.renakoni.nextvol.debug`; the install explicitly selects arm64 because
MuMu also advertises x86 ABIs.

The shell-only debug entry exercised the actual preview, selected-resource
installer, runtime router and complete-WAV synthesis. These representative
cases produced nonzero PCM samples:

| Family / variant | Observed output |
| --- | --- |
| Audio effects | 48,000 Hz, 1.608 s; fixed audio playback, not narration |
| Acapela | 22,050 Hz, 3.857 s |
| Baidu | 16,000 Hz, 3.024 s |
| CereProc | 24,000 Hz, 3.55 s |
| Google | 24,000 Hz, 3.01 s |
| IssTTS | 24,000 Hz, 2.365 s |
| Lark legacy / modern | 16,000 Hz, 3.211 / 2.68 s |
| MscTTS legacy xzhd / purextts | 16,000 Hz, 11.556 / 2.058 s |
| VCS | 16,000 Hz, 2.965 s |
| WeRead | 16,000 Hz, 2.93 s |
| Xiaomi | 16,000 Hz, 2.976 s |
| Microsoft Xiaoxiao | 24,000 Hz, 2.313 s |
| Microsoft classic Andrei / Hongyu | 24,000 Hz, 3.61 / 3.632 s; filename-case compatibility verified |
| Sogou | 16,000 Hz, 2.0 s |
| Vocalizer | 22,050 Hz, 2.873 s |
| Samsung | 24,000 Hz, 2.97 s |
| Sherpa Piper Dutch | 16,000 Hz, 2.544 s |
| Sherpa VITS Theresa | 22,050 Hz, 3.636 s |
| Sherpa Matcha Chinese / English | 22,050 Hz, 4.101 / 3.344 s |
| Sherpa Kokoro English / multilingual Chinese | 24,000 Hz, 3.034 / 5.191 s |

The normal UI separately imported the real Sogou ZIP through Android's document
picker (24 voices, 36 resources), installed Xiaobing's selected 22 MB resources,
selected the voice and completed audition through Media3. Engine-name edits
survived force-stop/relaunch and were restored after the check.

With Android reporting `Active default network: none`, the ordinary reader
played cached text from *魔法少女育成计划* with Sogou. Four captured chapter clips
were valid 16,000 Hz mono PCM16 WAVs, with durations 4.8, 25.0, 1.2 and 9.6 s and
nonzero samples. Media-session state changed from playing to paused and back;
stopping removed the session's audio files. Wi-Fi was re-enabled afterward.
These are waveform and player-state observations, not subjective audio-quality
or physical-device performance measurements.

A further network-disabled run covered 19 representative vendor cases, including
classic and neural Microsoft voices and both MSC/Lark variants. All produced
nonzero WAVs; the final Microsoft cases used the ordinary APK and unmodified
caller-supplied ZIPs. Sogou's offline check is the reader playback described above.
Android reported no default network during these checks; Wi-Fi was restored.

The final ordinary-build path downloaded the published runtime bundle and rebuilt
the source backend. Debug and Release assembly, Release R8/Vital Lint, full Debug
Lint, app unit-test compilation and source-speech test compilation passed. Debug
Lint reported 462 warnings and 8 hints, with no errors. No JVM tests were run locally.

A real Piper package with a deliberately damaged FAR file installed successfully
and returned `EngineUnavailable` during initialization instead of crashing the
process. A subsequent valid Piper import and synthesis produced a nonzero
16,000 Hz WAV.

Classic Microsoft model loading depends on case-insensitive filenames.
A controlled file-open observation showed `.INI` and `.APM` lookups against
lowercase package names, and a lowercase `.ini` lookup against an uppercase name.
The SDK reported these missing files as a wrong decryption key. Installation now
adds extension hard links before catalog publication. Hongyu additionally needs
the filename stems referenced by `Tokens.xml`, title-cased extra-language suffixes,
and lowercase NUSData domain suffixes. REA file-open observations confirmed these
SDK lookups; installation now adds their aliases, preserves original names and
existing alternate files, and does not duplicate model data.
The private model directories synthesized successfully with these aliases;
no key, model bytes, runtime binary or authentication rule was changed.

## Coverage limits

The supplied APK lacks Baidu's optional `libbd_ettl.so` variant. Google's supplied
runtime requires Android 8+, and some older vendor libraries only support 4 KiB
memory pages. Audio-effect packages play recordings. These are actual runtime
constraints, not model-identity restrictions.

Physical phones, API 24 runtime behavior and 16 KiB devices were not exercised.
JVM regression tests are compiled but left to remote CI for execution according
to the repository's testing rules.
