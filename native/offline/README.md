# Embedded vendor runtimes

The app's Kotlin adapters and small Java JNI bridges are source code. The vendor
engines are separate native SDKs. Importing a voice package does not supply these
SDKs or grant an SDK license.

Sherpa and its ONNX Runtime continue to build from `native/matcha` and
`native/onnxruntime`. Microsoft client SDK 1.45.0 comes from its official Maven
AAR. Ordinary builds fetch the pinned `offline-runtime-1` release asset through
`fetch_runtime.py` and embed its libraries and runtime data in the APK. The user
does not need a second app or an engine download after installation. CI uses the
same Gradle task. The bundle is kept outside Git because it contains binary SDKs.
To build with a locally supplied runtime instead:

```text
./gradlew :app:assembleDebug -PofflineRuntimeDir=/absolute/path/to/runtime
```

The directory contains `jniLibs/<abi>/*.so` and `assets/offline-native/`. An
alternative versioned artifact location can be supplied with
`-PofflineRuntimeUrl=https://.../runtime.zip`. Download or extraction failures
fail the build instead of silently producing an APK without its engines. No
model hashes or model allowlist checks are used.

The reference bundle has 42 arm64 libraries: 201.55 MiB before compression and
77.61 MiB for the complete ZIP. APK download size and installed native-library
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
already supplied by the Microsoft AAR, records missing SDK
variants and native loading constraints, and copies no Java code, service,
manifest, voice model or application assets. It does not perform model hashes or
model allowlist validation. The resulting `assets/offline-native/runtime-manifest.json`
lists filenames, byte sizes, dependencies and page alignment for inspection.
Runtime SDK defaults are supplied separately as
`assets/offline-native/runtime-licenses.json`; the extractor does not infer or
copy them. Acapela and CereProc use these defaults when the voice package does
not supply its own license data. Local validation can override individual
engines through the application-private `files/offline-runtime-licenses.json`.

| Engine | Entry libraries; companion libraries follow ELF dependencies |
| --- | --- |
| Acapela | `acattsandroid`, `ttsacattsandroidextended` |
| Baidu | `bd_etts`, `bd_ettm`, `bd_ettf`; `bd_ettl` where supplied |
| CereProc | `cerevoice_eng` |
| Google | `google_speech_sbg_tts_jni` |
| IssTTS | `issxtts30`, `issxtts40` |
| LarkTTS | `synthesizer`, `Aisound` |
| Microsoft | Official client AAR supplies `Speech.java.bindings` and `Speech.core`; additional `Speech.extension.telemetry`, `Speech.extension.embedded.tts`, its runtime and `Speech.extension.onnxruntime` (all under `Microsoft.CognitiveServices`); companion `speexdsp` where package license aliases require it |
| MscTTS | `msc` |
| Samsung | `smtloader`; per-voice engine libraries belong to the voice package |
| Sogou | `omp`, `evalite_uapi`, `sgsplit`, `sgtts`, `sgproxy_lib`, `sgtts_lib` |
| VCS | `bl_vcs` |
| Vocalizer | `NuanceVocalizer` |
| WeRead | `weread-tts`, `hwTTS` |
| Xiaomi | `tts-offline` |
| Audio effects | Android's media decoder; no vendor library |

Microsoft SDK 1.45.0 loads the telemetry extension from
`TelemetryManager.getSingleton()` when constructing `EmbeddedSpeechConfig`,
including for offline synthesis. Its standard client AAR does not contain this
extension. The preparation helper therefore includes it explicitly and excludes
all library filenames already supplied by the AAR to avoid duplicate packaging.

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
