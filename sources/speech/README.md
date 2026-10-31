# HTTP speech sources

This module imports HTTP TTS definitions and returns bounded audio bytes. Android owns encrypted persistence, source selection, decoding and the existing read-aloud session/player. There is no second playback service or provider-specific queue.

## Definitions and requests

- Import previews accept an object or a list (4 MiB, 512 entries, bounded JSON depth). IDs remain exact strings, including large numeric IDs; definitions without IDs receive a canonical SHA-256 identity. Unknown fields and original scripts survive saving and editing.
- `speakText` and `speakSpeed` are bound only for speech execution. The reference configuration's `speed=5` maps to script `speakSpeed=10`; an application rate of 1.0 maps to 10. Each provider's script still determines what that value means to its service.
- Original static expressions or a terminal `@js:`/`<js>` rule execute in the existing isolated worker. Generated request strings, nested requests and substituted book text are data, not another round of templates. Speech requests do not expand book-source page alternatives.
- Requests use `SourceBroker` origin grants, cancellation, cookies, pacing and redirect checks. A redirect or dynamically returned audio origin can require a separate user grant. Denial diagnostics contain origins only, never paths, queries or credentials.
- Each synthesis has a 60-second deadline and a 2 MiB audio limit. Data-URI audio has its own bounded decoder, without increasing ordinary source URL limits. Container signatures reject obvious text/error payloads; Android inspects the audio track before committing a file. These checks do not replace complete decoding/playback acceptance.
- `loginCheckJs` and interactive login/configuration UIs are not implemented here. Preserving a configuration does not claim that every dependency can execute. No arbitrary `Packages` or Java reflection is exposed.

## Microsoft Translator recipes

Two audited script shapes (fixed voice and header-selected voice) use a host adapter. Recognition hashes the exact normalized script after replacing only voice/language, signing-key and client-ID literals. Modified code is not silently reinterpreted as this protocol. The test resources use a synthetic signing key and synthetic client IDs.

The adapter obtains its signing material from the selected definition. It generates the complete `MSTranslatorAndroidApp::signature::date::id` envelope, holds the returned token in the current client for at most eight minutes, and refreshes once after HTTP 401. Rate limits and other service failures are not retried. SSML escapes book text and maps application 1.0x to 0%, 0.5x to -50%, and 2.0x to +100%.

Approving the Translator origin also approves this adapter's regional Microsoft speech service, as shown during import and website editing. The authentication response supplies a validated region label, never an arbitrary URL. Only a host-owned request receives the derived exact HTTPS origin; general source scripts do not receive that grant. Authentication and synthesis redirects are disabled.

This compatibility path is distinct from Edge WebSocket and Azure subscription-key authentication. Its fixed protocol signing constant is not an Azure subscription key or a user account.

## Verification

JVM tests cover import boundaries, template/data separation, request cancellation, denied origins, signature vectors, SSML, token expiry and bounded refresh. Android tests cover actual isolated execution, media decoding/playback and Keystore persistence, including revocation and runtime cleanup when sources change. The minified UI smoke selects, previews and removes a deterministic imported voice; it makes no claim about public service availability.

## Bundled Android catalog

Android loads `app/src/main/assets/speech/builtin-voices.json` on every repository instance. The 99 fixed entries remain available without an import file, after reopening, and after app-data reset. Namespaced IDs keep existing imported definitions and selections separate. Speaker metadata and enabled state can be overridden, and voices can be copied; built-in entries cannot be deleted. Copies keep the original service credential reference and approved origins.

Imported MultiTTS speakers retain their `param` and `extendUI`. Candidate controls feed JSON-encoded values into the request template's `params` array without executing during import or editing. Reimport retains stable speaker IDs and local overrides. Auditions remain in the voice list and use saved settings through the existing authorization and worker path. Shared audio effects remain the responsibility of #814.

The catalog covers the previously exercised Baidu, AISpeech, Volcengine, Microsoft Translator, Shipook and iFlytek-compatible recipes. The two Qidian proxy voices require a user Token, shared at service level; no sample proxy token is bundled. Aliyun requires the user's AppKey, AccessKeyId and AccessKeySecret and uses its HTTPS CreateToken flow. Account fields remain in the existing Keystore-encrypted store; that store and its runtime are excluded from Android backup because the Keystore key does not transfer. Clearing or changing them revokes execution tickets and removes cached runtime tokens. Private credentials are serialized as JSON before binding in the existing isolated executor; input values cannot add script or query parameters.

Only exact audited service origins are granted. Microsoft regional grants remain confined to its existing adapter. Bundled client-protocol constants are distinct from user account keys. The original account-dependent login UI, broken dynamic endpoints and unsupported selectors are not bundled as working voices. Public-service availability remains independent of catalog persistence; bundled online voices still require a working network and service.

Tests cover fresh/reset catalog loading, legacy imports, immutable built-in IDs, credential validation and revocation, Aliyun token requests, and the unified selection/configuration UI. A user's live Aliyun/Qidian account is not exercised by deterministic tests.
