# HTTP speech sources

This module imports HTTP TTS definitions and returns bounded audio bytes. Android owns encrypted persistence, source selection, decoding and the existing read-aloud session/player. There is no second playback service or provider-specific queue.

## Definitions and requests

- Import previews accept an object or a list (4 MiB, 1024 entries, bounded JSON depth). IDs remain exact strings, including large numeric IDs; definitions without IDs receive a canonical SHA-256 identity. Unknown fields and original scripts survive saving and editing.
- `speakText` and `speakSpeed` are bound only for speech execution. The reference configuration's `speed=5` maps to script `speakSpeed=10`; an application rate of 1.0 maps to 10. Each provider's script still determines what that value means to its service.
- Original static expressions or a terminal `@js:`/`<js>` rule execute in the existing isolated worker. Generated request strings, nested requests and substituted book text are data, not another round of templates. Speech requests do not expand book-source page alternatives.
- Requests use `SourceBroker` origin grants, cancellation, cookies, pacing and redirect checks. A redirect or dynamically returned audio origin can require a separate user grant. Denial diagnostics contain origins only, never paths, queries or credentials.
- Each synthesis has a 60-second deadline and a 2 MiB audio limit. Data-URI audio has its own bounded decoder, without increasing ordinary source URL limits. Container signatures reject obvious text/error payloads; Android inspects the audio track before committing a file. These checks do not replace complete decoding/playback acceptance.
- `loginCheckJs` and interactive login/configuration UIs are not implemented here. Preserving a configuration does not claim that every dependency can execute. No arbitrary `Packages` or Java reflection is exposed.

## Dedicated MultiTTS web backends

The `ggtrans`, `huoshan`, `gemini`, `xfpeiyin` and `peiyinya` adapters accept `inner` engines, including packages without an explicit engine type. Import retains the original speaker identity, locale, parameters and extension controls without making requests. Each protocol has its own implementation; `HttpSpeechClient` owns the existing broker session, grants, deadline and cancellation authority. No package script or native library is needed for these protocols.

| Engine | Original protocol | Audio delivery |
| --- | --- | --- |
| `ggtrans` | Configurable `Host`, Translate `jQ1olc` web RPC | Extract Base64 audio from the complete RPC response |
| `gemini` | Configurable `Host`, Bard `XqA3Ic` web RPC, speaker code and sample-rate selector | Extract Base64 audio from the complete RPC response |
| `huoshan` | `crx` or `web` selected by speaker `param`, JSON speaker/language/text | Decode `audio.data` after the complete response |
| `xfpeiyin` | MD5 of UTF-8 text, AES signing request/response, signed synthesis URL | Complete returned audio |
| `peiyinya` | AES payload and RSA key envelope, emotion/degree parameters, decrypted audio URL | Complete downloaded audio |

The mapping follows MultiTTS 1.8.3 (84), APK SHA-256 `d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`: REA `F6.i/h/m/x/q`, with the Google synthesis branches additionally checked against DEX disassembly where Java decompilation lost loop control. Protocol constants embedded in the original client are distinct from personal credentials; generated tokens and signatures are not persisted or logged. Follow-up and redirected audio URLs remain subject to the same exact-origin grants.

Text uses the original sentence aggregation and the existing sentence-length setting, preserving supplementary characters where the original split cuts a UTF-16 surrogate pair. MP3 and chained Ogg responses retain their encoded segments; PCM WAV responses are joined under one corrected WAV header. Current Huoshan and Xunfei responses can be WAV, and Gemini can return Ogg/Opus, despite the original decoder's `audio/mpeg` hint. RPC audio is extracted through both JSON layers, fixing the original direct Base64 decoding of escaped padding such as `\u003d`. Selecting incremental decoding for these five adapters uses the existing file path.

Speaker speed/volume/pitch remain in the common audio processor. Google, Gemini and Huoshan ignore the separate global synthesis rate/volume arguments, as the original does. Xunfei and Peiyinya retain their original request-unit conversions. There is no additional local compensation or duplicate application of those controls.

`HttpSpeechClient.refreshVoices()` explicitly fetches the original Peiyinya catalog and returns definitions for the existing catalog save/merge path. The other four protocols have no original catalog request to reproduce. Opening an import preview never invokes this operation. Catalog counts and successful package import do not establish current service availability; live synthesis results are recorded separately in the implementation PR.

## Microsoft Translator recipes

Two audited script shapes (fixed voice and header-selected voice) use a host adapter. Recognition hashes the exact normalized script after replacing only voice/language, signing-key and client-ID literals. Modified code is not silently reinterpreted as this protocol. The test resources use a synthetic signing key and synthetic client IDs.

The adapter obtains its signing material from the selected definition. It generates the complete `MSTranslatorAndroidApp::signature::date::id` envelope, holds the returned token in the current client for at most eight minutes, and refreshes once after HTTP 401. Rate limits and other service failures are not retried. SSML escapes book text and maps application 1.0x to 0%, 0.5x to -50%, and 2.0x to +100%.

Approving the Translator origin also approves this adapter's regional Microsoft speech service, as shown during import and website editing. The authentication response supplies a validated region label, never an arbitrary URL. Only a host-owned request receives the derived exact HTTPS origin; general source scripts do not receive that grant. Authentication and synthesis redirects are disabled.

This compatibility path is distinct from Edge WebSocket and Azure subscription-key authentication. Its fixed protocol signing constant is not an Azure subscription key or a user account.

## MultiTTS mstrans packages

The dedicated `code: mstrans`, `type: inner` adapter imports the Chinese/English and all-language packages directly. It retains every locale, voice code, parameter and extension control. Engine and speaker edits, reimport identity, encrypted storage (1024 imported entries), previews and chapter playback use the existing online catalog and session. Importing and opening the preview perform no authentication, network, script or native execution.

The mapping follows MultiTTS 1.8.3 (84), APK SHA-256 `d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`: REA `F6.o` constructor (`ev_50c75d67760c889816fc9ccb2045d37169aac3a9c50b74dfb9b375fc83591370`) and synthesis (`ev_68f71924ec7ff85a02e02612a9b62debd4969cd0e6bad05bde4a0a53909385ac`), `F6.b.g` authentication (`ev_69a126911aa5e274ebfeb0667eb9332fa3cadb232a33916a86422cbef3bf7438`), and `R6.i.f` input units (`ev_4915b8b1c919d040d5be6052c705f29c402f97795c3a45954d32e7bab268218c`). These are static observations, not APK runtime captures.

| Field | Execution |
| --- | --- |
| Engine `param.authHost` | HTTPS authentication host; defaults to dev.microsofttranslator.com. Its origin needs approval before connecting; only its successful response derives the bounded Microsoft regional grant. |
| Engine `param.audioFormat` | Sent as X-Microsoft-OutputFormat; defaults to audio-24khz-48kbitrate-mono-mp3. Raw PCM formats cannot be played by the current container-based player. |
| Speaker `code`, `locale` | Code identifies the saved speaker; locale supplies SSML xml:lang, without a zh/en allowlist. |
| Speaker `param` | Ordered voice name, role, style, style degree, optional language skill. Defaults: Default, Default, 1.0, no lang wrapper. Optional slots resolve @extendUI values; an empty slot ends optional parsing as in the original. |
| Application synthesis rate/volume | Native SSML: int(rate × 40)%, int(volume × 50), with pitch 0%. Thus default 1.0 maps to 40% / 50, independently of the audited script recipes. |
| Speaker speed/volume/pitch/sampleRate | Preserved for common audio processing #814; not applied by this native backend or claimed as implemented effects. |

The native adapter shares the existing session-local token cache, one refresh on 401, origin/address policy, timeout, cancellation, response limit and player. Fixed client-protocol signing material comes from the original adapter; no account credential or token is persisted in logs. Original random X-Forwarded-For spoofing is not needed for the observed service requests and is omitted. Editable SSML attributes and book text are escaped, avoiding the original attribute interpolation defect.

Malformed optional extension controls are ignored as in `ExtendUI.parseExtendParams` (REA `ev_893fdc2d75b707d6412a10a47faa85ad51c9aa733b8defee32f3eff2bcfd7a71`); they do not disable a speaker whose parameters do not depend on them.

On 2026-11-01 the actual Kotlin adapter statically imported 163 and 579 voices from the local packages and synthesized Chinese special-character text, Japanese and German through SourceBroker. The three results were MP3, 24 kHz, mono. This records public-service behavior at that time, separately from deterministic tests; it does not guarantee every catalog voice remains available from Microsoft.

## MultiTTS edgetts packages

`code: edgetts`, `type: inner` uses the dedicated Edge WebSocket protocol, independently of Translator and generic HTTP templates. Both local packages (61 Chinese/English voices and 318 all-language voices) use the existing catalog, editing, encrypted store and player. The real voice name is the first comma-separated `param` value, including @extendUI resolution. Locale remains catalog metadata; the original SSML uses xml:lang=en-US with the selected voice name. Default application rate/volume map to 40% / 50%, pitch stays +0Hz, and speaker speed/volume/pitch/sampleRate remain common processing work under #814.

Original evidence is MultiTTS 1.8.3 (84), the same APK digest above: REA F6.f constructor `ev_8f466aebb07a6890c7d69fbc81f3ff96884b1e8d721d70ff4377baf3bb62fed1`, handshake `ev_245246068613f1cb94110d0020f50ab9acf345d9cd994994e266f2ecabe6a3f2`, synthesis smali `ev_ebf81e8b0b2ae61de2a11e13a8757095fb495f9d025415af8c974085dd436e22`, binary/text messages `ev_6182d2bb46d45f8391e368c6ef2cb345343c00d765a971d76ad2a78f78b4d3b8` / `ev_298e94a5bc13b14950a14e21aa711d9388ef43a6148dbbbe6c85505dc999ba93`. Java recovery failed for synthesis; the cited representation is smali, not original source.

The historical v1/130 handshake was rejected with 403 in the bounded service comparison. The current implementation uses Sec-MS-GEC v2/143 from pinned edge-tts revision `bd44c1d1aaebdc1db7f47456ee12431b42ec7cac`; [source and license notice](EDGE-TTS-NOTICE.md) documents the Kotlin adaptation. It sends speech.config followed by escaped SSML, consumes the unsigned two-byte Edge header length, accepts MP3 audio frames, rejects textual errors, and publishes only after turn.end. This corrects the original substring-based audio parser. The complete MP3 is handed to the existing player; low-latency streaming remains #815.

Source-network owns the WebSocket upgrade and framing. Its ordinary HTTP Call upgrade preserves the existing actual-peer interceptor (OkHttp newWebSocket skips that interceptor). The exchange uses the source's HTTPS-origin grant, DNS/address policy, captured route, concurrency, pacing, cancellation, timeout and byte limits. It does not expose a socket to scripts, follow redirects or retry rejected handshakes. Closing or retiring a request prevents partial audio publication. Authentication tokens, cookies and returned error text are not emitted in diagnostics.

The actual Kotlin adapter and broker synthesized Chinese special-character text, Japanese and German on 2026-11-01, separately from the research probe. Each returned 24 kHz mono MP3. A fixed GEC vector and protocol/network failure cases are deterministic CI coverage, not a substitute for those service observations. Service availability and the validity of each remote voice can change.

## Verification

JVM tests cover import boundaries, template/data separation, request cancellation, denied origins, signature vectors, SSML, token expiry and bounded refresh. Android tests cover actual isolated execution, media decoding/playback and Keystore persistence, including revocation and runtime cleanup when sources change. The minified UI smoke selects, previews and removes a deterministic imported voice; it makes no claim about public service availability.

## Bundled Android catalog

Android loads `app/src/main/assets/speech/builtin-voices.json` on every repository instance. The 96 fixed entries remain available without an import file, after reopening, and after app-data reset. Namespaced IDs keep existing imported definitions and selections separate. Speaker metadata and enabled state can be overridden, and voices can be copied; built-in entries cannot be deleted. Copies keep the approved origins.

Imported MultiTTS speakers retain their `param` and `extendUI`. Candidate controls feed JSON-encoded values into the request template's `params` array without executing during import or editing. Reimport retains stable speaker IDs and local overrides. Auditions remain in the voice list and use saved settings through the existing authorization and worker path. Shared audio effects remain the responsibility of #814.

The catalog covers the previously exercised Baidu, AISpeech, Volcengine, Microsoft Translator, Shipook and iFlytek-compatible recipes. Qidian proxy voices and Aliyun are not bundled.

Only exact audited service origins are granted. Microsoft regional grants remain confined to its existing adapter. Bundled client-protocol constants are distinct from user account keys. The original account-dependent login UI, broken dynamic endpoints and unsupported selectors are not bundled as working voices. Public-service availability remains independent of catalog persistence; bundled online voices still require a working network and service.

The voice library has one import action for JSON definitions and ZIP/YAML packages; it identifies the file format before opening the existing confirmation or installation flow. Import and preview never execute source scripts. Tests cover fresh/reset catalog loading, legacy imports, reserved built-in IDs, and saved speaker edits.

The listening settings keep the lightweight online and offline catalogs in their shared view models. Engine tabs appear together once both catalogs are ready. Offline file checks and package avatars load only for the opened engine, and completed results are reused until the catalog, engine configuration or runtime observations change. Returning from another app does not reload the catalogs; users can explicitly reload data from the library menu.
