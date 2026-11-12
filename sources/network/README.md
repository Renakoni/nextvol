# Source-bound host services (#85)

`SourceBroker.open` is a **trusted host operation**. It binds stable source namespace/id,
engine profile, account generation, exact-origin grants and quotas into a `SourceSession`.
IPC callers submit `BrokerRequest`/`StorageRequest`; neither DTO can choose another
source or obtain a client, path, repository or broker. `source-execution` binds each
process to the host-created session; these JVM objects alone do not authenticate IPC.

Imported rules reach this broker through `source-content` and the execution bridge.
The Android app supplies the optional browser port. Current integration evidence and
account semantics are covered by the module tests.

## Request diagnostics

Imported-source diagnostics reuse `ContentTraceEvent.requestDiagnostic` in the existing
diagnostic screen/export and debug-only `RuleSourceTrace` log. Normal release reading does
not install an observer. Wenku8 and the browser identity/routing/cookie policies are unchanged.

- Each `SourceSession.execute` assigns a fresh local `requestId`, not the caller's often-reused
  request ID. Browser-mediated HTTP requests carry a `parentRequestId` from their owning
  browser operation. Script bridge calls are also observed, but do not claim a parent when
  the execution bridge did not preserve that context. IDs are never website headers.
- `Selected` records intent and the chosen executor/reason, not proof of navigation.
  Browser reasons distinguish source browser-read, native website/interactive selection,
  rendering, synthetic/verification documents and unmet native transport requirements.
- `HeadersResolved.userAgentSources` lists present layers from lowest priority to winner:
  session default, origin grant, same-origin login headers, request headers. Source header
  rules and URL-option overrides are already merged at this boundary and are not falsely
  attributed separately. An absent override is explicitly transport/WebView default.
  Web-cookie-only operations bypass source headers as before and do not imply navigation.
- `HeadersResolved.cookies` describes HTTP selection, not browser storage or server receipt.
  `HttpJar` counts selected entries (including explicit pairs), parsed explicit pairs, expired
  entries removed in this selection, remaining unmatched entries, and same-name jar entries
  overridden by explicit pairs. Unmatched includes URL/expiry checks, not a fabricated
  SameSite/partition decision. The jar may contain browser handoffs; this is the selecting
  store, not original issuance provenance. `ExplicitHeaderOnly` means the existing image
  policy bypassed jar selection; absent counts are unknown, not zero. `automaticCapture`
  reports HTTP response capture policy; false does not disable sending login/explicit cookies.
- `TransportHeaders` observes OkHttp's locally prepared UA after its default header handling,
  with broker retry attempt and redirect hop. OkHttp connection retries can share these
  coordinates. It is not evidence that a server received the bytes.
  Cached and inline responses have no transport event; `Completed.path` identifies them.
- `WebViewSettings` is a trusted service callback, not a public JavaScript bridge operation.
  It includes a bounded UA summary and whether settings readback matches the requested UA.
  Metadata status is `ProviderDefault`, `Unsupported`, `UnhandledUserAgent`, `Applied` or
  `Rejected`. `Applied` means the metadata setter returned successfully, not a page/worker,
  UA-CH header, or server-side observation. No event means unknown, not unsupported.
  Optional `environment` reports the installed WebView package/version (not the UA's claimed
  version), and support for UA metadata and full Cookie information. Package/version strings
  have bounded formats. Environment lookup failure omits only that evidence.
- `CookieSnapshot` describes an admitted native-browser snapshot received by the host.
  `selected` counts exported entries, not Chromium's outbound Cookie header. With full
  metadata, `partitionedExcluded` counts the existing Partitioned filter; header-only
  fallbacks cannot know that count and leave it absent. Optional counts are also omitted
  when they would exceed the existing IPC payload limit; Cookie data and its limits are
  unchanged, and an absent count remains unknown rather than zero. `completeMetadata` describes the
  export interface, not HTTP preservation of browser semantics. A received snapshot can
  still be rejected by the existing version fence; the event is not proof of application,
  transmission, authentication or challenge clearance.

Only enums, counts, booleans, bounded provider identity and request-local IDs are retained:
no raw UA, cookie names/values, stable fingerprint/hash, URLs, headers, credentials, script
or response text. Callback failures cannot fail a request; UA/environment callbacks are
bounded and accepted once per job. Cookie evidence reuses the existing bounded handoff.
No extra IPC, persistent store, background task or dependency is introduced. With observation
disabled, no added diagnostic object/provider lookup or extra Cookie scan occurs; selection
counts reuse existing collection sizes and partition counts reuse the existing filter.
The existing report event cap/truncation flag still applies, so a truncated report is not
a complete request history. Real page/client-hint observations require owned browser
fixtures; neither these diagnostics nor their unit tests claim improved site acceptance.

## URL compilation

`RequestCompiler` handles static relative/absolute HTTP(S) URLs, `{{key}}`, `{{page}}`,
`{{baseUrl}}`, `<first,second,last>` page alternatives, and JSON options for method,
body, headers/header, charset and retry. Keyword values are encoded before entering
query/form separators; JSON object bodies serialize substituted string values safely.
GB2312 and other JVM charsets are supported; the legacy `escape` option emits `%XX`/
`%uXXXX`. Existing encoded URL octets are retained. Forms encode their individual fields.
JSON request bodies are supplied as valid JSON strings/objects (a raw JavaScript expression
in a body is not static JSON). Unknown options and malformed requests are rejected.
`RequestOptionsJson` in `source-rules` owns the data grammar shared with the worker:
JSON plus single-quoted strings, including escaped quotes and nested header/body strings.
It does not evaluate object expressions or accept comments, unquoted keys or trailing commas.
Input and normalized JSON are bounded to 65,536 characters and 64 nesting levels;
embedded JSON strings are checked again before parsing. Network authorization stays here.
Source-level headers and explicit `java.connect` header strings use this grammar too;
the worker sends explicit connect headers as canonical JSON to the existing host port.
The static compiler reports ScriptRequired for expressions; the production worker evaluates
dynamic URL/header/body expressions first. Browser options compile to data and dispatch
through BrowserExecutor; an absent port reports BrowserRequired.

## Network and credentials

### Per-source VPN bypass (#214)

The default is the current system route, including any OS VPN/TUN. It does not promise
a remote proxy exit: VPN exclusions and Clash rules still decide that. The existing
`VpnDns.Default` resolves Fake-IP (`198.18.0.0/15`) answers through its public DoH fallback
before the usual address validation. Ordinary NXDOMAIN/private answers do not trigger it.

The Android host can enable **Bypass VPN** for an installed broker source. It observes
existing `INTERNET + NOT_VPN` networks, preferring validated Wi-Fi/Ethernet, then available
cellular. It does not start another network or bind the app process. The selected Android
`Network` provides **both DNS and sockets**, with `NO_PROXY`; its DNS never calls the global
DoH client. Clash must allow bypass. A missing/lost route reports `RouteUnavailable`;
DNS, timeouts and connection failures retain their observed codes. There is no fallback to
the other mode. Neither mode supports application HTTP/SOCKS proxies or changes TLS policy.

`SourceRouteProvider` is a trusted host port, absent from script/IPC request DTOs. Each
admitted request captures one `SourceNetworkRoute`, retained through redirects, retries
and broker-browser child requests. Source settings are keyed by stable host identity,
outside rule JSON and account state. Switching changes later requests without replacing
the source runtime or cancelling aggregate searches. Normal caches and cookies survive.

Sessions retain their own dispatcher and separate route/network connection pools.
Android default-network changes and lost/changed physical networks retire the relevant
route, cancel its active HTTP calls and evict idle connections. A switch of one source's
preference does not retire a network used by other sources. Cached response bodies can
still be read while offline; cache hits are not evidence of a network route.

Rules, JS bridges, external jsLib, provider images, background reads and Z-Library use
this port. Disposable diagnostics and candidate-rule validation inherit the real source's
mode; source-definition downloads and initial collection imports use their own default
download route. Current background work has no separate unmetered-only policy.

The broker-forwarded browser disables native loads and propagates the root route to every
child HTTP request. The separate persistent **native Chromium** process binds its network
before Chromium initialization and uses a direct proxy override when bypassing VPN (#219).
Unsupported Android/WebView combinations report `RouteUnsupported`; no route fallback is
implied. Native page subrequests use Chromium's networking, not the broker's per-peer checks.
Cookie handoff still validates the current source/account and authorized origins.

Broker HTTP address and origin enforcement applies to both default and bypass routes.
Do not grant private-address access
as a workaround. `Dns` means a lookup failed; `AddressDenied` means a non-public answer;
`OriginDenied` means an exact-origin permission is missing. Only the last belongs in the
permission editor. Broker-browser main-frame refusals retain broker codes across Binder. A page
may handle a failed subresource/XHR itself; handled failures do not replace successful
rule results.

### Origin approval feedback (#139)

An `OriginDenied` result may include `OriginDenial`: a canonical scheme/host/explicit
port plus ResourceKind. Its constructor rejects noncanonical values, paths, queries
and user information, including data received over browser IPC. Other failure kinds
do not generate website-approval requests.

Each source/account session remembers at most 32 distinct origin/kind refusals for the
source-management screen. These are transient diagnostics, never grants. Closing the
session clears them; account/revision replacements do not inherit them. Request
refusals are published under the same host commit guard as request results. Background
workers can record a refusal but cannot navigate, show a prompt or grant access.

The import preview separately scans validated definition fields for at most 32 literal
HTTP(S) origin/purpose candidates. It does not execute JavaScript or infer unresolved
hosts. Adding a candidate changes only the visible draft; saving goes through the
existing revision/account validation and replacement path. Exact-origin and address
checks continue to apply to approved CDN/API/redirect targets.

### Enforcement

- Grants authorize exact scheme/host/port combinations; multiple origins are explicit.
  All document/image/script/API/import requests use the same check. Only HTTP(S) is accepted.
- DNS policy is installed in the **actual connection resolver**. Every returned address
  must be public unless the host explicitly granted private addresses for that origin.
  Literal IPs are checked before connection because OkHttp may skip Dns for them. The
  network interceptor checks the connected peer again. Loopback/LAN/link-local, IPv4-mapped
  addresses, multicast/reserved ranges and IPv6 transition ranges are covered. A changed
  DNS answer cannot bypass an earlier preflight check. Automatic redirects, transport
  retries, global cookies, HTTP cache and system proxies are disabled.
- Every redirect goes through the same origin/address checks. Same-origin requests keep
  their headers. Cross-origin redirects drop **all caller headers**, then attach only the
  target grant's headers and domain/path/secure/expiry-matching session cookies. Explicit
  cookies override same-name jar cookies for that target; source/profile/account jars never
  share state. Host/transport/proxy routing headers cannot be supplied by a rule.
- 301/302 POST and 303 non-HEAD redirects become GET with no body; 307/308 retain method/body
  within an origin. Cross-origin forwarding of a retained body fails explicitly. The host
  can authorize a separate explicit request to that endpoint without implicitly sending
  the previous endpoint's login body.
- `retry` bounds additional attempts (0..3 by default), for recoverable IO failures and
  429/502/503/504 on safe GET/HEAD reads. Safe connection recovery can use one attempt even
  at `retry=0`, within the host limit, and shares this budget. Streamed bodies, browser actions
  and other methods are not replayed; an owning download task disables in-session retries. Permissions,
  quotas and malformed requests are not retried. HTTP error responses remain structured
  responses so later source rules can inspect status/body.
- A total coroutine deadline covers queueing, rate delays, redirects, retries and body
  reads. Ordinary HTTP calls have no separate OkHttp total deadline; socket connect/read/write
  limits still apply. Session permits and minimum start intervals apply to all attempts/hops.
  Cancelling the caller cancels its OkHttp call through body consumption and releases permits;
  closing/replacing the session cancels in-flight/queued requests and rejects old handles.
- Response bytes are bounded, with a header bound as well. Response charset comes from an
  explicit request override, then Content-Type, then a valid HTML head declaration, then
  UTF-8. HTML detection inspects at most the first 8 KiB using the existing Jsoup parser,
  for HTML/XHTML or a missing media type; comments, script strings and nested templates
  are not declarations. There is no statistical encoding guess. Request encoding and
  response decoding are distinct. The protocol retains the original bytes and nullable
  HTTP/override charset separately from the effective text encoding. No source rule,
  external resource or full response DOM is evaluated during charset selection.

## Website verification (#173)

Document requests recognize Cloudflare and supported site challenges before rule extraction,
including same-origin `/@wafjs` scripts, `_wa_` cookie refreshes and `_waform` captcha forms.
A 401 cookie bootstrap retries the same GET once; POST is preserved for the verification
flow and is not silently retried. Raw API responses and browser subrequests remain available
to source scripts. Cached challenge pages cannot become successful empty document results.

Both browser transports inspect the live document before returning extraction results.
Interactive verification waits through script and captcha pages; the broker browser retains
the actual HTTP status, while native DOM results remain `BrowserDocument` with status 0,
never a fabricated HTTP 200. Completing the page preserves its source session. The existing
foreground coordinator retries the original operation once, so a repeated challenge or an
unparseable document still fails. Background work exposes a notice without opening a window.
In the broker browser, form submissions retain the page's User-Agent just like fetch requests.
The close action cancels verification without extracting or retrying the current document;
the separate confirmation action still supports ordinary login pages and image-code input.

Challenge recognition does not solve captchas, authorize new domains, disable certificate
checks or guarantee a site's availability. Actual Aitu reading and different network routes
require separate live evidence; owned browser fixtures prove only the recovery mechanism.

## State and storage ownership

Persistent config keys use source+profile; account KV/cookies and script caches also include account generation.
Opening a new account generation retires the old session, preserves config and starts separate
account state. A source/profile can only have one active session in a broker. Host grant changes
require closing the existing session first. No current-source global or UI state is read.

Persistent cookies retain absolute expiry; session cookies remain in memory. Cookie persistence
has its own private namespace, inaccessible through account KV. Cookie persistence failures are
reported instead of silently switching to an unauthenticated jar. SourceLoginService and
AndroidSourceBrowser coordinate login/logout and browser cookies through these sessions.

KV accepts opaque keys, never paths. Namespaces and filenames are hashes of unambiguous stable
components; writes enforce bytes/entry quotas before atomic replacement. Path checks reject
symlinks/aliases outside the controlled directory. Null writes delete a key. Stored values are
not encrypted by this module; the caller supplies app-private storage, and IPC/process access
restrictions are #86's responsibility.

`StorageArea.Cache` provides bounded persistent script get/put/delete and per-entry TTL.
This includes both `cache.put` and `cache.putFile`; all values belong to the source,
profile and account generation. Restart and revision replacement retain them for
the same account. Logout/relogin clears the retired account's values, and a new
generation never reads them even if cleanup is interrupted. Revoked sessions
cannot write late values.

Legacy script caches used only source/profile ownership and cannot be reliably
classified as public or private by key names. On opening a source, its old script
cache partition is cleared (best effort) without copying values into the new partition. Public
script cache entries are also invalidated at upgrade and account rotation; sources
must rebuild them. Durable source preferences belong in `source.put`/Config and
retain their existing lifetime. BookState, downloaded resources, local books,
images, content and reading progress are not script cache entries and are retained.

`RequestVariables` is a separate bounded frame, copied per invocation, with snapshot reads.
Neither cache is durable source configuration. HTTP GET caching is opt-in, keyed by URL/response
charset/effective headers (including scoped credentials); it is a host cache with a fixed TTL,
not a claim to implement all HTTP cache directives. Cookie/account changes preserve cached
bodies; changed effective headers choose their own entries, and hits never replay Set-Cookie.
Source-account/runtime replacement inherits the HTTP response cache. Cache-only misses fail
without DNS/network, response arrays are copied, and ordinary TTL/eviction still apply.

Serializable DTOs define the later IPC payloads. Their diagnostic strings omit headers,
bodies, keys and URLs; failure results contain stage/code/attempt, not exception messages
with paths or secrets. Payloads necessarily carry data for the caller but are never logged
by this module. JS execution, login UI, Chromium and the Android process boundary live in
their owning modules; BrowserExecutor is only their source-bound request port here.

## Verification

Run `gradlew.bat :source-network:test :source-compatibility:test`.
MockWebServer fixtures explicitly authorize only their local test origins. Tests never access
external sites: encoding, actual wire headers/body, response decode, origin redirects,
cookie priority/persistence, same-domain source/profile/account isolation, cache-only behavior,
TTL/quotas, path containment, DNS rebinding/IPv6/literals, concurrency, rate, cancellation,
timeout, retry and serializable/redacted result contracts are covered.

The existing compatibility sources also run their static search URL through the production
compiler. The current URL/HTTP/storage entries link broker, execution, content and platform
tests; this module alone is not a full upstream JavaScript or Android browser oracle.
