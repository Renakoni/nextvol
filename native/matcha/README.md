# Chinese Matcha backend (#791)

Sherpa 1.12.10 (`6b1fbdedd2d90dba2714b7aff825412d89e45eba`) runs the
installed Chinese Matcha / HiFi-GAN sample through `SpeechSynthesizer`. It produces
a complete mono PCM16 WAV, then uses the existing read-aloud session and player.
Model import, persistent selection and settings UI belong to #794.

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
listed in the #793 recipe. This issue deliberately exposes an opt-in debug build;
ordinary debug, release and snapshot variants retain their existing platform
support and do not package the backend.

## Build

```powershell
./gradlew.bat :app:assembleDebug -PmatchaNative=true `
  -PmatchaNdk=E:/Android/Sdk/ndk/28.2.13676358 `
  '-PmatchaCmake=E:/Microsoft Visual Studio/2022/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe' `
  -PmatchaNinja=E:/Android/Sdk/cmake/3.22.1/bin/ninja.exe
```

Optional `matchaBuildDir` selects an output/cache directory; `matchaPython`
selects the Python interpreter. The default output is `app/build/matcha`.
`build_android.py` can also be called directly with `--ndk`, `--cmake`,
`--ninja`, `--output` and `--jobs` (default 3). It always verifies source
inventories and builds the current source tree, reusing CMake's object cache.

`jniLibs/arm64-v8a` contains the two generated libraries. The packaged
`assets/matcha-native` directory contains their build identity and dependency
licenses; the manifest embeds #793's runtime identity, including toolchain
binary dependencies. It is published atomically after all exports succeed. Neither generated libraries nor model weights are tracked.

## Installed sample and controlled entry

Install the debug APK on the designated MuMu instance. The opt-in build uses
`indi.renakoni.nextvol.matchaprobe` to coexist with ordinary debug installations. Copy the already obtained
sample into the app's private `files/matcha` directory with `adb push` and
`run-as indi.renakoni.nextvol.matchaprobe`. Required files:

```text
model-steps-3.onnx  hifigan_v2.onnx  tokens.txt  lexicon.txt
date.fst  number.fst  phone.fst
dict/jieba.dict.utf8  dict/hmm_model.utf8  dict/user.dict.utf8
dict/idf.utf8  dict/stop_words.utf8
```

This profile accepts the Baker Chinese sample's `x`, `x_length`, `noise_scale`,
`length_scale` acoustic inputs, `matcha-tts` metadata and pad ID 1. Resources must
be readable, bounded regular files whose canonical paths do not escape their
installation. Rate must be finite and in 0.5–2; pitch changes and alternate
voices are unsupported. Model weights are external acceptance fixtures; they
are not distributed with NextVol. Their own licenses and dataset restrictions
must be addressed by model import/distribution work.

```powershell
adb -s 127.0.0.1:16448 shell am start -W `
  -n indi.renakoni.nextvol.matchaprobe/indi.renakoni.nextvol.tts.matcha.MatchaProbeActivity
adb -s 127.0.0.1:16448 shell run-as indi.renakoni.nextvol.matchaprobe cat files/matcha-result.json
```

The debug-only activity requires the system `DUMP` permission held by adb shell.
It loads `files/matcha`, synthesizes fixed Chinese text into
`cache/matcha-probe.wav`, and releases the instance. Success, WAV size or a
failure is written to `files/matcha-result.json` and `NextVolMatcha` logcat.
Paths and text are fixed. The one-off cancellation/session-replacement and
memory/timing probe modes have been removed after acceptance; the focused
lifecycle tests remain.

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
