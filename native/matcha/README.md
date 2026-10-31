# Offline Sherpa TTS backend

Sherpa 1.12.10 (`6b1fbdedd2d90dba2714b7aff825412d89e45eba`) runs
VITS (including supported Piper, Coqui and Melo exports), Matcha, Kokoro and
Kitten models through `SpeechSynthesizer`. It produces a complete mono PCM16 WAV
at the model's actual sample rate, then uses the existing read-aloud session and
player. Ordinary debug, release and snapshot APKs include the backend. Model
weights and pronunciation data come from user-installed packages.

## Source boundary

`sources.json` pins upstream archives and licenses. `sources.sha256` inventories
vendored source files and patches for reproducible native builds. These build
inputs are separate from imported voice packages: no model digest or sample
allowlist is used. The build uses local source files without downloading
dependencies. It builds the runtime from `../onnxruntime`, then the JNI adapter
and selected Sherpa TTS translation units. No upstream JNI binary, AAR or Maven
runtime is used.

Piper and espeak-ng provide the multilingual phonemizer. Vocos uses the inverse
STFT subset of kaldi-native-fbank and KissFFT; HiFi-GAN remains available. ASR,
device audio output, espeak command-line tools and tests are not compiled.
Dependency licenses are included in `assets/matcha-native`.

The recorded patch turns fatal resource/parser errors into exceptions handled
at JNI, checks tensor shapes and audio bounds, and releases espeak's global
resources when the current model closes. Switching a voice reopens its own
espeak directory instead of retaining the first model's directory forever.
The application JNI binding handles UTF-16 to UTF-8, cancellation and bounded
PCM16 output without requiring upstream Java package names.

The build currently targets CPU, NDK 28.2.13676358, Android API 26 and arm64-v8a,
with static libc++ and 16 KiB ELF segment alignment. Threads, speaker ID and
model parameters come from the selected voice. All APK variants retain minSdk
24; native availability depends on loading the library in an API 26+ arm64
process. Other devices retain system and HTTP speech. Small, never-loaded ABI
marker libraries keep the APK installable on other supported Android ABIs.

## Build

```powershell
./gradlew.bat :app:assembleDebug `
  -PmatchaNdk=E:/Android/Sdk/ndk/28.2.13676358 `
  '-PmatchaCmake=E:/Microsoft Visual Studio/2022/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe' `
  -PmatchaNinja=E:/Android/Sdk/cmake/3.22.1/bin/ninja.exe
```

The NDK defaults to `ndk/28.2.13676358` in the Android SDK. CI installs fixed
NDK/CMake tools and caches the Gradle native task outputs. `matchaNative=false`
disables the Sherpa backend; the separate vendor runtimes remain embedded.
CI also sets `matchaOrtCacheKey` (`--ort-cache-key` for the script) to reuse the
independently cached, verified ONNX Runtime install across Matcha/JNI changes.

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

Open a MultiTTS ZIP from the system document picker. Static preview reads YAML
as data and resolves each Sherpa voice's `param.modelDir`, falling back to its
voice code. Resource discovery resolves the acoustic/model file, optional
vocoder and voice embeddings, tokens, lexicons, espeak and Jieba directories,
and FST/FAR rules. Explicit resource paths and voice parameters are preserved.
Preview does not execute scripts or load a model.

After installation, model initialization uses the selected files and the ONNX
model's metadata to identify its actual Sherpa format. The model loader checks
its own resource and tensor contracts. An unrelated or incomplete model fails
initialization through the existing speech error flow; filenames and an
`inner` declaration do not establish that synthesis succeeded. There is no
fixed Baker filename, checksum, sample-rate or single-speaker gate.

Package speaker ID, thread count, noise/length scales and language are passed to
the relevant backend. Package speaking rate combines with the existing synthesis
rate setting. Package pitch uses PCM resampling with native speaking-rate
compensation, and volume scales the generated samples. Playback speed continues
to use the common player. System-engine pitch settings are not applied to the
offline backend.

Installation copies the selected model directory to managed private storage,
then atomically publishes its catalog entry. It validates paths and file
lengths without model hashes or content-derived revisions. Reimport replaces
the same selected voice with a fresh resource revision. Interrupted staging and
unreferenced revisions are reclaimed at the next process start; active resource
leases defer deletion until native destruction has completed. Imported weights
are not included in the APK.

The debug-only `MatchaProbeActivity` remains a development helper. It uses the
same resource discovery as installed voices; it is not the user import flow.

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
