# Embedded vendor runtimes

The app's Kotlin adapters and small Java JNI bridges are source code. The vendor
engines are separate native SDKs. Importing a voice package does not supply these
SDKs or grant an SDK license.

Sherpa and its ONNX Runtime continue to build from `native/matcha` and
`native/onnxruntime`. Microsoft client SDK 1.45.0 comes from its official Maven
AAR. Ordinary builds fetch the release asset pinned by URL and SHA-256 in
`runtime.json` through `fetch_runtime.py` and embed its libraries and runtime data
in the APK. The user does not need a second app or an engine download after installation. CI uses the
same verifier with a shared ZIP cache and supplies the extracted directory to Gradle.
The bundle is kept outside Git because it contains binary SDKs.
To build with a locally supplied runtime instead:

```text
./gradlew :app:assembleDebug -PofflineRuntimeDir=/absolute/path/to/runtime
```

The directory contains `jniLibs/<abi>/*.so` and `assets/offline-native/`. An
alternative versioned artifact location can be supplied with
`-PofflineRuntimeUrl=https://.../runtime.zip` with its expected
`-PofflineRuntimeSha256=<digest>`. A mirror of the pinned bytes uses the same digest.
The script retains a ZIP beside its output directory, or at `--archive`, and verifies
its SHA-256 before every extraction. Invalid cached bytes trigger a fresh download;
a mismatched download fails before replacing the current SDK output. Download or
extraction failures fail the build instead of silently producing an APK without its engines. No
model hashes or model allowlist checks are used.

Main publishes one SDK ZIP cache keyed by `runtime.json`; APK jobs restore it or
download the pinned asset on a miss. JVM/Lint skip SDK preparation. Updating the
runtime URL or digest triggers main cache warming, and retention keeps the latest
shared archive. The SDK cache is independent of the source-built ONNX Runtime cache.

The pinned bundle contains arm64 vendor libraries. APK download size and installed native-library
size are different measurements; legacy companion-library loading requires
Android to extract the native libraries during installation.

For local compatibility investigation, prepare the minimum arm64 library set
from a caller-supplied runtime APK:

```text
python native/offline/prepare_runtime.py --apk /path/to/runtime.apk --microsoft-aar /path/to/client-sdk-1.45.0.aar --ndk /path/to/android-ndk --output /new/external/runtime-directory
```

The helper copies only the SDK entry libraries and their ELF dependencies. Entry
libraries include explicit Java `System.loadLibrary` calls and native plugin
loads, which are not necessarily listed in ELF `DT_NEEDED`. It excludes libraries
already supplied by the Microsoft AAR, records native loading constraints,
and copies no Java code, service,
manifest, voice model or application assets. It does not perform model hashes or
model allowlist validation. The resulting `assets/offline-native/runtime-manifest.json`
lists the source APK hash, library hashes, filenames, byte sizes, dependencies
and page alignment for inspection.
Runtime SDK defaults are supplied separately as
`assets/offline-native/runtime-licenses.json`; the extractor does not infer or
copy them. Acapela and CereProc use these defaults when the voice package does
not supply its own license data. Local validation can override individual
engines through the application-private `files/offline-runtime-licenses.json`.

| Engine | Entry libraries; companion libraries follow ELF dependencies |
| --- | --- |
| Acapela | `acattsandroid`, `ttsacattsandroidextended` |
| Baidu | `bd_etts`, `bd_ettm`, `bd_ettf` |
| Baidu online | `BDSpeechDecoder_V1` decodes the 6.1.4.1add0da WebSocket protocol's `aue=2` payloads |
| ByteDance offline | `speechengine`, with `sscronet`, `ttboringssl`, `ttcrypto`; TTNet Java SDK `4.1.127.6-tob` comes from its official Maven repository |
| CereProc | `cerevoice_eng` |
| Google | `google_speech_sbg_tts_jni` |
| IssTTS | `issxtts30`, `issxtts40` |
| LarkTTS | `synthesizer`, `Aisound` |
| Microsoft | Official client AAR supplies `Speech.java.bindings` and `Speech.core`; additional `Speech.extension.telemetry`, `Speech.extension.embedded.tts`, its runtime and `Speech.extension.onnxruntime` (all under `Microsoft.CognitiveServices`); companion `speexdsp` where package license aliases require it |
| MscTTS | `msc` |
| Samsung | `smtloader`; per-voice engine libraries belong to the voice package |
| Sogou | `omp`, `evalite_uapi`, `sgsplit`, `sgtts`, `sgproxy_lib`, `sgtts_lib` |
| VCS | `bl_vcs` |
| Vivo online | `vopus_v5` and its `opus` dependency decode SDK 5.1.6.11 (231201_ae4b12ea) `aue=1` payloads |
| Vocalizer | `NuanceVocalizer` |
| WeRead | `weread-tts`, `hwTTS` |
| Xiaomi | `tts-offline` |
| Audio effects | Android's media decoder; no vendor library |

Standalone eSpeak is source-built alongside Sherpa, outside this vendor bundle.
ByteDance uses SpeechEngine 1.1.5.3 from the reference APK. Its native SDK remains
binary; the package adapter, JNI declarations and environment setup are maintained
in this repository. TTNet must initialize before SpeechEngine, including for
offline models: the SDK's settings and licensing paths otherwise dereference an
uninitialized Cronet context during shutdown or initialization failure. This does
not change the offline work mode or substitute an online synthesizer.

ByteDance selects flat `bytedance/` resources or `bytedance/midu/`, as MultiTTS
1.8.3 does, independently of `param`. It retains `zh-cn`, the selected
`<code-before-hyphen>_streaming` directory and the supplied license. When a voice
only exists under `fanqie/`, its model is combined with the same package's midu
base data and `speech_license.licbag` through the SDK's separate base/voice paths.
This repairs a confirmed MultiTTS resource lookup bug: the supplied package's
BV001/BV002 exist only under fanqie, while the original app looks for them under
midu. Both now synthesize with unchanged package bytes. No license is generated,
rewritten or fetched by the importer. Preview remains non-executing.

The obsolete Baidu `37111357` adapter was removed after comparing the supplied
MultiTTS 1.8.1, 1.8.2 and 1.8.3 APKs: all retain a Java reference to `bd_ettl`,
but none ships that library. The current BDeTTS packages use `90013` and
`6082724`; their existing engine routes remain intact.

Microsoft SDK 1.45.0 loads the telemetry extension from
`TelemetryManager.getSingleton()` when constructing `EmbeddedSpeechConfig`,
including for offline synthesis. Its standard client AAR does not contain this
extension. The preparation helper therefore includes it explicitly and excludes
all library filenames already supplied by the AAR to avoid duplicate packaging.

Vivo and Baidu online signing, framing and service requests are implemented in
`:source-speech` and use the existing network broker. Their vendor libraries only
decode audio. MSC online sessions share the existing `msc` runtime and its
process-wide serialization with offline MSC sessions. These native decoders and
MSC's synthesis implementation remain vendor binaries, not source-built engines.

Voice resources, dictionaries and package-provided licenses stay in the imported
package tree. Lark and Samsung may require ABI-specific libraries inside that
tree. Acapela and CereProc still require their actual SDK license data. Vocalizer
uses the same SDK-scoped Context compatibility mechanism as the reference app:
its package query sees the SDK package name and supplied public certificate,
while application storage and resources delegate to NextVol. This does not
change NextVol's package, signing key or Android manifest. The certificate is
parsed before loading the library; the native SDK's verification is unchanged.

The supplied arm64 runtime is a compatibility reference, not proof of support on
every Android device. In particular, Google links Android API 26 libraries, and
older SDK libraries may require 4 KiB memory pages. The helper reports those facts
without rewriting the SDKs or disabling their authentication. On-disk companion
loads use extracted native libraries (`useLegacyPackaging = true`).

Package preview, installed voices, selection and synthesis share the app's
per-voice availability check. It reads the actual process ABIs, system page size,
packaged ELF headers and runtime dependency inventory without loading an SDK.
Baidu and IssTTS variants are checked independently; Samsung and Lark also check
the selected package's native library. Audio effects remain independent of the
native speech runtimes. Unsupported devices can still preview and install data.

Passing static checks means unverified, not successful playback. Initialization
and synthesis failures remain retryable and are recorded for the exact voice
revision and effective engine configuration in the current app process. A
completed synthesis replaces that failure with a successful-synthesis status;
edited parameters or a fresh resource revision do not inherit that observation.

Classic Microsoft models rely on case-insensitive filenames in the reference
app's external storage. Managed installation adds hard links for extension casing,
`Tokens.xml` references, extra-language suffixes and NUSData domain suffixes in
private storage. For example, a packaged `.apm` remains available under the SDK's
`.APM` lookup, and `voiceAssistant.unt` under `voiceassistant.UNT`, without copying
model data or changing existing files.
Missing these paths can surface as `WRONG_DECRYPTION_KEY` from the native SDK.

## Voice editing

The voice library uses scrollable engine tabs and toolbar search within the
current tab. Each voice supports editing, enable/disable and copying. Refresh
and imports live in the toolbar menu. HTTP tabs use engine codes so equally named
engines remain separate. Search follows MultiTTS 1.8.3's speaker matcher: plain
terms match name, description or code, ignoring case; whitespace-separated terms
must all match. Field queries support `desc`, `note`, `locale`, `code`, `gender`,
`type`, `sampleRate`, `param`, `extendUI` and `ischecked`. In this app `ischecked`
refers to the selected reading voice. Empty or incomplete numeric queries do not
crash live search. A voice has a stable ID distinct
from its engine and resource revision. Copies share model files; deletion waits
for the last referencing voice and active lease. Package refreshes retain user
speaker overrides, including values changed back to 1, while adopting untouched
new defaults. Refresh also discovers Google speakers from installed pipeline
declarations without initializing the SDK. On first use, Sherpa fills missing
speaker choices and the model sample rate, as MultiTTS does; package-defined
choices remain intact.

Editors reuse the loaded voice catalog and prepare form data off the UI thread.
Opening or typing does not recheck package files or validate the complete voice;
configuration validation runs on save. Extension controls are parsed only when
their definition changes, and offline parameter normalization belongs to the
repository save operation.

`param` and `extendUI` preserve unknown control fields. Their raw editors are
hidden by default and enabled separately in Playback settings. The compact voice
form shows package-defined choices directly; it has no nested advanced sections.
Candidate keys are parameter values, candidate values are labels, and an empty
selection defaults to the first candidate. Only complete `@code` references are
substituted. HTTP parameters are JSON encoded before entering the existing
isolated request worker, retaining the saved source's network grants.

Package defaults are overridden by saved speaker settings. Speaker synthesis
rate multiplies the global synthesis rate; player speed remains separate under
Voice settings. The editor follows the MultiTTS field order and candidate lists.
Import keeps declared sample rates and fills missing/zero rates with 16000.
Sample rate describes either the native
request or declared PCM format, not a universal resampler. Shared DSP remains
the responsibility of #814; role/segment overrides are not implemented here.

Playback settings owns the shared audition text used by the voice list, system
voice choices. The editor has Cancel and Save actions; audition is available
from the voice list after saving. Finishing audition restores the book's audible
boundary, play/pause intent and frozen timer. Saving publishes a new synthesis
revision when required, invalidating prepared speech plans.

Avatars resolve from `<engine>/avatar/<filename>` in imported packages. Copies
share their image resource, user-selected images use persistent document access,
and absent or unreadable images fall back to the gender-specific portrait.
