# Edge TTS protocol algorithm

`src/main/kotlin/hnovel/speech/EdgeGec.kt` adapts the Sec-MS-GEC v2 algorithm from
[`rany2/edge-tts`, `drm.py`, revision bd44c1d1aaebdc1db7f47456ee12431b42ec7cac](https://github.com/rany2/edge-tts/blob/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac/src/edge_tts/drm.py).
The upstream authors license that file under GNU LGPL version 3. This Kotlin
adaptation retains that license; the repository's Apache-2.0 license does not
replace it. The upstream license, including the LGPL and GPL texts, is retained
in `app/license-config/licenses/edge-tts-lgpl.json` and displayed in the app's
existing open-source licenses page.

Changes: port the integer arithmetic and byte layout to Kotlin/JDK, keep only
the current v2 token generator and remove the upstream Python transport and
clock-correction integration. The original public client protocol constant is
not a user account credential.

The modified source, its `EdgeSpeech.kt` caller and the application build files
are distributed in this repository. To replace or modify the algorithm, check
out the source revision matching the installed release, edit `EdgeGec.kt`, then
follow the repository's Android build setup and run `./gradlew :app:assembleDebug`
(`gradlew.bat` on Windows). The resulting debug APK can be installed with
`adb install -r app/build/outputs/apk/debug/<apk-name>.apk`; it uses a separate
application ID and does not require the project's release signing key.
Modification and reverse engineering for debugging changes to this LGPL
component are permitted under its license.
