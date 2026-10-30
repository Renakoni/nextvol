# Chinese Matcha backend

Sherpa 1.12.10 (`6b1fbdedd2d90dba2714b7aff825412d89e45eba`) runs the
installed Chinese Matcha / HiFi-GAN sample through `SpeechSynthesizer`. It produces
a complete mono PCM16 WAV, then uses the existing read-aloud session and player.
The ordinary debug, release and snapshot APKs include the backend. Users install
external voice packages through Settings → Read aloud → Voices; weights are not
bundled with the application.

## Source boundary

`sources.json` pins upstream archives and licenses. `sources.sha256` inventories
the selected source files and patches, including cppjieba/limonp, kaldifst and
OpenFST. The build uses these files without downloading dependencies. It first
builds the runtime from `../onnxruntime` (#793), then builds the JNI adapter and
selected Sherpa translation units. No upstream JNI binary, AAR or Maven runtime
is used. ASR, espeak, VITS, Kokoro and Kitten implementations are not compiled.
The retained 242 vendor files follow the compiler dependency inventory, including
licenses. Unused OpenFST script/extension headers, lattice helpers and limonp
utilities are omitted. Small upstream configuration structs remain to preserve
the internal C++ API.

The recorded patch restricts the factory/frontend to Chinese Matcha, rejects
incompatible graph signatures/metadata and output tensors, and changes fatal
resource-parser errors, including malformed tokens and missing files, into C++
exceptions caught at the JNI boundary. The
application-specific JNI binding is `jni.cc`; it handles UTF-16 to UTF-8,
cancellation and bounded PCM16 output without upstream Java package names.

The fixed profile is CPU, two threads per ONNX session, speaker 0, 22,050 Hz, NDK
28.2.13676358, Android API 26 and arm64-v8a, with static libc++ and 16 KiB ELF
segment alignment. Use CMake >= 3.26 and < 4.0, Ninja and the host build tools
listed in the #793 recipe. All variants retain minSdk 24. Matcha is offered only on API 26+ when the arm64
library actually loads. Small, never-loaded ABI marker libraries keep the same
APK installable on armeabi-v7a, x86 and x86_64; those processes retain system and
HTTP speech. An emulator with ARM translation still needs an arm64 process;
MuMu A selected arm64 automatically when installing the ordinary APK during
#794 acceptance. Merely advertising arm64 in the device ABI list is not treated
as a working backend.

## Build

```powershell
./gradlew.bat :app:assembleDebug `
  -PmatchaNdk=E:/Android/Sdk/ndk/28.2.13676358 `
  '-PmatchaCmake=E:/Microsoft Visual Studio/2022/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe' `
  -PmatchaNinja=E:/Android/Sdk/cmake/3.22.1/bin/ninja.exe
```

The NDK defaults to `ndk/28.2.13676358` in the Android SDK. CI installs fixed
NDK/CMake tools and caches the Gradle native task outputs. `matchaNative=false`
is an explicit build opt-out, with offline voices reported unavailable.

Optional `matchaBuildDir` selects an output/cache directory; `matchaPython`
selects the Python interpreter. The default output is `app/build/matcha`.
`build_android.py` can also be called directly with `--ndk`, `--cmake`,
`--ninja`, `--output` and `--jobs` (default 3). It always verifies source
inventories and builds the current source tree, reusing CMake's object cache.

`jniLibs/arm64-v8a` contains the two generated libraries. The packaged
`assets/matcha-native` directory contains their build identity and dependency
licenses; the manifest embeds #793's runtime identity, including toolchain
binary dependencies. It is published atomically after all exports succeed. Neither generated libraries nor model weights are tracked.

## Package import and selection

Open a MultiTTS ZIP from the system document picker. The first supported sample
is Baker in the Sherpa Matcha/Kokoro package. The static preview reads YAML as
data; it never executes scripts, constructs tagged Java objects or loads models.
Select **Install Baker**, return to Voices, and explicitly select or audition it.
Cached/local chapters can then play with networking disabled.

Mapping uses `engine.code == sherpa`, `engine.type == inner`, and
`voice.param.modelDir`, falling back to `voice.code`. Relative directories may be
nested. The required filenames are fixed by `ChineseMatchaProfile`, including
the acoustic graph, HiFi-GAN, tokens, lexicon, five dictionary files and three
FSTs. Extra ONNX candidates, duplicate identities/paths and escaping paths are
rejected. This first importer verifies the two graphs against the accepted
sample SHA-256 values; filename/`inner` declarations alone do not establish
model compatibility. Unsupported voices remain visible with a reason.

The current runtime fixes CPU, two threads and speaker 0. Explicit differing
parameters, dynamic `extendUI` references and local speed/pitch/volume effects
are rejected. `noiseScale` and `lengthScale` only accept the shipped Matcha
values (1); other model-specific parameters are unsupported. MultiTTS's existing
`p134t4/c` parser uses `modelDir`/speaker code and defaults to four threads;
`p134t4/b` applies its noise parameters to VITS, not to the Matcha builder.
This importer intentionally describes its supported profile rather than silently
applying all of those fields to Matcha. Rate uses the existing synthesis setting;
playback speed continues to use the common player. System pitch remains stored
but is not applied to Matcha.

Installation reopens the document, repeats static mapping, checks selected paths,
sizes and CRCs against the preview, and copies only the required files with
bounds, CRC and graph-hash verification. Files are staged in private no-backup
storage, then atomically published with the catalog. Interrupted staging and
unreferenced revisions are reclaimed at the next process start. Duplicate import
replaces the same voice identity; identical resources reuse the same revision.
The persisted `OfflineVoiceRef(id, revision)` is separate from existing system
and HTTP selections. The ID derives from engine/voice code, while the revision
hash covers the installed resource contents. A deleted voice cannot open again;
active leases defer resource deletion until native destruction completes.

The imported weights are user-provided, are not redistributed by NextVol, and
remain subject to their own model and dataset licenses. Native dependency
licenses and source-build provenance are packaged in `assets/matcha-native`.

The legacy debug-only `MatchaProbeActivity` remains a restricted development
helper. It is not part of the user import or acceptance workflow.

## Lifecycle

`MatchaResources`, `MatchaNative` and `MatchaSpeechSynthesizer` own validation,
JNI bindings and lifecycle respectively. A process-wide semaphore allows one
model instance. A per-instance lock serializes initialization, synthesis and
destruction. `cancel()` only sets an atomic flag; `close()` waits off the main
thread before deleting the pointer and returning the instance slot.

The callback observes coroutine cancellation between sentence batches. A running
ONNX graph or initializer finishes before release; cancellation is not an
interruptible per-operator operation. Its partial result is discarded. Output
is checked for finite, non-silent samples, bounded to the existing 8 MiB WAV
contract, written to `.part`, validated, and renamed only while the request is
active. The existing session waits for cleanup and guards generation changes,
so stopped/replaced sessions cannot submit late audio to the player.

JVM regression tests protect cancellation during initialization, close during
generation, and resource escapes. Existing session tests cover stale clips and
bounded prefetch. Device acceptance records actual native completion latency;
see `VALIDATION.md` for the measured run and its limits.
