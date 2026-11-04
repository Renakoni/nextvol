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

A further network-disabled run covered 18 representative vendor cases, including
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

Google's supplied runtime requires Android 8+, and some older vendor libraries only support 4 KiB
memory pages. Audio-effect packages play recordings. These are actual runtime
constraints, not model-identity restrictions.

Physical phones, API 24 runtime behavior and 16 KiB devices were not exercised.
JVM regression tests are compiled but left to remote CI for execution according
to the repository's testing rules.

# #879 / #880 / #883 / #884 backend acceptance

Validation date: 2026-11-03 (host local date). MuMu A, Android API 32, arm64
translation, 4 KiB pages. These checks called the actual package preview,
selected-resource installer and backend directly from temporary instrumentation;
they did not use the NextVol UI or an external TTS app to synthesize audio.
The final debug APK SHA-256 is
`b8f1c8b23bcc294af8b30798e2732f6b7451a5b13219cda8eac0a6906521a6c2`.

## Reference and build identity

REA/JADX inspected the supplied MultiTTS 1.8.3(84) APK, SHA-256
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`.
REA/Ghidra also inspected the native eSpeak synthesis boundary, SpeechEngine
JNI/resource path, Baidu JNI and Cronet initialization. The 1.8.1 and 1.8.2 APKs
were separately inventoried: all three carry identical SpeechEngine and the
three shipped Baidu libraries, and none carries `libbd_ettl.so`. #881 was closed
as not planned; its obsolete 37111357 adapter was removed.

Relevant REA anchors include `n4.a.<init>`
`ev_42537f9614e067f0b53f6a9e692f68b24f2288ca166739a7dddd9505148db1a4`,
SpeechEngine environment initialization
`ev_fe7183887dcad57506bd9e82c1063dbe8d99e603e5d004ec1faf7e4350cee1f0`
and the installed original app/log observation
`ev_6689e5194ca8b99872f4facf9a7793eb75c887195b066ae0f8805ec6bd7df9ea`.

The published `offline-runtime-2` bundle contains 46 arm64 libraries and retains
the SDK bytes unchanged. Its 91,419,587-byte ZIP has SHA-256
`b3087fe1109aaa4614776e45fb4ebefb83324c7fbed79aa9ec5c9aab392d653e`;
the ordinary fetch tool downloaded and verified the published asset. Its manifest
records the source APK and each library hash. SpeechEngine is 1.1.5.3, with
TTNet Java 4.1.127.6-tob from the official repository. Other vendor kernels remain
binary SDKs identified by this manifest; where a semantic version is unknown,
passing synthesis does not establish one or imply source reconstruction.
The entry-library/companion mapping remains in `../offline/README.md`.

## Real speech results

Thirty selected package/voice cases produced complete mono PCM16 WAVs with
nonzero samples. The first 26 successful cases below, including ByteDance midu,
ran with Android reporting no default network. The four additional ByteDance
resource-repair cases ran after connectivity was restored. ByteDance always used
offline work mode 2048; there is no online synthesis fallback. Successful cases
were retained without repeating the whole matrix.

| Backend / voice | Hz | Seconds |
| --- | ---: | ---: |
| eSpeak en-US male / f1 variant | 22,050 | 2.584 / 2.575 |
| Melo Chinese / English (speaker 1) | 44,100 | 5.769 / 5.058 |
| Coqui Dutch CSS10 | 22,050 | 3.755 |
| Kitten nano v0.1 FP16 (speaker 7) | 24,000 | 4.602 |
| ByteDance BV001_24k, midu | 24,000 | 4.126 |
| ByteDance BV001 / BV002, repaired resource mapping | 16,000 | 3.972 / 3.648 |
| ByteDance BV004 / BV104, midu selected without a midu param | 16,000 | 3.448 / 3.917 |
| Acapela Tracy, empty param | 22,050 | 5.059 |
| Baidu 90013 xiaonvhai / 6082724 along with extension model | 16,000 | 6.160 / 4.864 |
| CereProc Mailin | 24,000 | 7.409 |
| Google cmn-cn-x-cca | 24,000 | 6.630 |
| IssTTS huidi_assist | 24,000 | 5.747 |
| Lark xiaokun legacy / tangseng modern | 16,000 | 6.902 / 7.180 |
| MSC xiaorong xzhd / xiaoyuan standard | 16,000 | 5.937 / 4.105 |
| Microsoft classic Andrei / Hongyu / neural Xiaoxiao | 24,000 | 4.925 / 6.556 / 5.150 |
| Samsung zh_cn_f00 | 24,000 | 5.619 |
| Sogou lvxiaobing | 16,000 | 4.400 |
| VCS linzhilingyuyin | 16,000 | 6.282 |
| Vocalizer Cantonese Sinji | 22,050 | 6.210 |
| WeRead fn | 16,000 | 5.990 |
| Xiaomi mitang | 16,000 | 6.276 |

Representative Chinese text: “夜色渐深，窗外传来轻轻的脚步声。她放下书，转身望向门口。”
These are waveform observations, not subjective audio-quality assessments.

Melo and Coqui came from the supplied `【离线】 Sherpa-onnx.zip`; Kitten came
from `【在线】 Sherpa-onnx示例包（Kitten TTS模型）.zip`, whose actual engine is
offline Sherpa. Model identities:

| Model | SHA-256 |
| --- | --- |
| `vits-melo-tts-zh_en/model.onnx` | `87a22783d9b5b08d66dcaaeb2a40b85a9ecb3c207a32fa06a8e73f6e77da8c3b` |
| `vits-melo-tts-en_v2/model.onnx` | `9b471a552991ef1e677d9efdba1cfc81683408f454218304ffe974f08a305c68` |
| `vits-coqui-nl-css10/model.onnx` | `9f059842bdac4c31db63523ebc686ad565daf859dead250d7de7637f186dbf6e` |
| `kitten-nano-en-v0_1-fp16/model.fp16.onnx` | `6b42d25df767db408d95738b464f02168a9cfb76367c1b2b9e90095485981407` |

The Coqui directory lacked `espeak-ng-data`; its acceptance fixture explicitly
supplied that data from `vits-piper-en_US-hfc_female-medium` in the same ZIP.
The original Coqui package alone was not marked as passing. eSpeak used that
same Piper data with separate male and f1 configurations. These hashes
identify evidence, not model admission requirements.

## ByteDance original-app comparison and repair

The supplied `【离线】 字节跳动.zip` has SHA-256
`b5d6d78f9a3b80dc6d58de57fd40128631a877ef99aceb7562c92c280e09200e`.
Its 15 voices use two resource directories, `fanqie` and `midu`; those are not
speaker names. The installed original MultiTTS 1.8.3 was exercised separately.
BV001 and BV002 failed because it looked for absent `midu/BV001_streaming` and
`midu/BV002_streaming`; BV004 and BV104 had corresponding midu models and emitted
audio. REA's constructor decompilation confirms flat-directory/midu selection,
independent of the speaker's `param`.

The adapter now prefers midu models and, for the two legacy-only voices, gives
the SDK separate paths to the unchanged fanqie voice model and the same package's
midu base data/license. Both repaired voices passed direct synthesis through the
actual importer. No model/SDK patch, license replacement or alternate online
engine was needed. A package missing its required shared data/license still
reports missing resources. All 15 definitions are retained; representative
synthesis does not claim that every voice was individually exercised.

## Lifecycle and limits

eSpeak invalid-voice initialization, cancellation after the first PCM callback,
release and fresh-instance recovery passed. A Kitten instance (speaker 0) stayed
open while eSpeak failed, cancelled and terminated; it then emitted a valid
24,000 Hz WAV, confirming the two eSpeak native states are independent.

ByteDance missing-resource initialization recovered, cancellation after PCM
unblocked the waiter without a WAV or `.part`, and a fresh instance generated
two consecutive paragraphs at rate 1.2. The second paragraph tolerates the SDK's
already-working START result, matching the original app. Settings/environment
initialization prevents the earlier missing-Cronet-context crash during cleanup.
Wi-Fi was restored and Android reported a validated default network afterward.

Acapela's nonempty-param branch and Baidu's 9294715/bd_ettf model remain without
matching local samples; their existing implementation was not removed. No new
API 24, 16 KiB or physical-phone claim is made. Google/eSpeak/Sherpa require API
26+, and legacy SDK page-alignment constraints remain. Player UI, chapter/role
orchestration and all-voice coverage are outside this backend-only acceptance;
the earlier Piper/Matcha/Kokoro evidence above is retained, not reclassified as
new runs. No vendor kernel is marked source-complete by a successful WAV.

Debug and Release assembly, Release R8/Vital Lint, Debug Lint, app unit-test
compilation and source-speech test compilation passed. Debug Lint reported
454 warnings and 9 hints, with no errors; Release Vital Lint had no errors or
warnings. JVM tests were not executed locally. Temporary device harnesses and
all model/audio artifacts remain outside the committed tree.
