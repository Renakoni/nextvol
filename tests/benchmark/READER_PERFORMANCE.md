# Reader performance baselines

## Run on a dedicated device

`ReaderJourneyBenchmark` uses the test-only `readerBenchmark` application
(`indi.renakoni.nextvol.readerbenchmark`). No real accounts, source definitions,
network hosts or user books are needed. The receiver resets this fixture's
book/chapter rows and reading settings in that separate application. Release, snapshot, debug and the
existing benchmark application do not contain the fixture activity or receiver.

Use API 29 or later. Select **one** dedicated device, keep a stable orientation,
font scale and refresh rate, and run at most one heavyweight build at a time:

```sh
export ANDROID_SERIAL=<dedicated-device>
./gradlew :benchmark:connectedReaderBenchmarkAndroidTest \
  -PreaderBenchmarkSha=$(git rev-parse HEAD) \
  -Pandroid.testInstrumentationRunnerArguments.class=indi.renakoni.nextvol.benchmark.performance.ReaderJourneyBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.readerIterations=5 \
  --max-workers=2 --no-parallel
```

PowerShell equivalents use `$env:ANDROID_SERIAL`, `./gradlew.bat`, and
`-PreaderBenchmarkSha="$(git rev-parse HEAD)"`; place arguments on one line.
If building uncommitted code, append `-dirty` and archive the diff **outside the
repository**. An unspecified SHA fails the report assertion. The class filter is
required: the existing main-source-set UI tests target the other benchmark APK.
The iteration argument permits a short smoke run, not a claim of a stable baseline.

Capture the test output containing `READER_SAMPLE` JSON, the Macrobenchmark JSON
and Perfetto traces from the connected-test/additional-test-output directories.
Keep these artifacts outside source control. Each sample identifies source SHA,
variant, minification, compilation mode, model, OS fingerprint/API, content size,
cache trust, sample count, request counters and original-content checks. Also
record device type (emulator/physical), thermal/power state, resolution, font
scale and refresh rate with the result; these are environment conditions, not
portable performance budgets. Compilation is explicitly `Full`, not a claim
about a normal user's installed baseline profile.

## One event vocabulary

| Metric | Begin / end and interpretation |
| --- | --- |
| Initial display | Macrobenchmark `StartupTimingMetric` TTID; activity launch to first frame. This is **not** readable text. |
| Interactive | `reader.to_interactive`: activity `onCreate` to handling the test's first Ping click, followed by an accessibility acknowledgement. Includes test input-delivery latency; does not block on text readiness. |
| First readable text | Startup TTFD (`reportFullyDrawn`) and `reader.to_readable` (`onCreate`/user action to a drawn, fully visible original glyph). The activity and the normal loading UI are displayed before this event. |
| Text preparation | `reader.prepare`: synchronous JSON/component preparation, including the existing speech-index access. It excludes cache/source waiting and layout. |
| Layout | `reader.layout`: synchronous real text layout. Sum includes repeated preparations and preloaded geometry, not just current-page work. |
| Source execution | `reader.source.execute`: entry/exit of the controlled native source, including simulated blocking latency and cancellation. |
| Background/refresh request | `reader.background.request` / `reader.refresh.request`: repository request entry to completion/cancellation. Compare to native source execution to identify pre-execution/cache/dispatcher costs. |
| Waiting/cancellation | Sample `backgroundWaitNs`: submission to native source entry (includes repository/dispatcher waiting, not exclusively queue time). `backgroundExecutionNs`: native work. `cancelResponseNs`: Cancel click handler to native finally/release. |
| Frames | `FrameTimingMetric`: frame CPU/overrun distributions over the measured journey, including test controls. A successful click is not evidence of smooth frames. |
| Memory | `MemoryUsageMetric(Mode.Max)`: maximum trace-sampled memory for the measured process, **not** a guaranteed instantaneous heap/RSS peak. |

The shared text-fragment observer is absent by default. The test host opts in,
checks original UTF-16 ranges and the glyph's actual clipped window bounds after
drawing, and checks the complete prepared chapter text and component count. It
tracks each fragment's original component index and UTF-16 offset, including text
after an image; moving beyond component zero must not suppress readiness reports. It
does not mistake a composed but off-screen/overscanned fragment, a placeholder,
or an activity window for readable text. Font changes wait for matching new text
geometry. The observer and correctness checks have their own overhead: compare
like-for-like instrumented runs, not these numbers to an uninstrumented app.
The 120-second content-readiness watchdog prevents indefinitely stuck tests; it
is not a performance budget. The actual elapsed readiness time remains measured.

## Repeatable minimum matrix

All content has unique `R<chapter>_P<paragraph>` markers, Chinese and surrogate-pair
characters. Ordinary/mixed fixtures have 30 paragraphs; long and single-paragraph
fixtures contain 2,000 units. The `components` fixture has 1,097 independent
`simple_text` components; `components-short` has 29. These exercise chapter-wide
layout work that a single component containing many paragraphs cannot detect.
`thousandComponentsFirstTextAndFrames` checks all original components at entry
and performs eight swipes. Its frame distribution includes entry and scrolling;
use separate trace windows when comparing initial mounting with steady scrolling.
The mixed fixture adds a generated local PNG and
an after-image marker; the test scrolls to that marker. Native chapter requests
take approximately 350 ms, background work 1.2 seconds, with cancellation checks
between bounded chunks. These are input conditions, **not** regression thresholds.
Background work carries the production `BackgroundSourceRequest` context, as
download work does: cancellation tests caller-owned work, not an intentionally
detached/coalesced plain preload. Durable WorkManager execution is not simulated.

| Test | Conditions and assertions |
| --- | --- |
| Cold/warm continue | New activity/VM with existing reading data, correct book/chapter and exact initial original marker; trusted text requests = 0. Startup cold means no Activity saved state, not an invented character-level disk anchor. |
| Trusted neighbor + background | Real native priority dispatcher, one permit; current text remains available, neighbor displays original content with no extra remote-text request, background makes progress and releases active work. |
| Cancellation | Cancel active native work, observe a cancellation and active count returning to zero, then read the trusted neighbor. Reports response time without imposing an arbitrary latency budget. |
| Legacy fallback + explicit refresh | Empty source revision; observe actual native requests and directory refresh, then an additional explicit fresh request while current text remains visible. No benchmark early return. |
| Long / oversized paragraph / mixed | Real VM, chapter repository, preparation, scroll/flip renderers; original content checks, first-readable and frame/memory metrics. Mixed content must retain text after the image. |
| Font / mode / recreate | Scrolls beyond the first component of the 1,097-component fixture, then reports component/UTF-16 anchor and `anchorPreserved`. Font reflow must report a nonzero component. Activity recreate and no-state startup are separate actions. |

Inspect per-iteration data and failures before comparing aggregate timings.
The framework can also emit `READER_SAMPLE` diagnostics during warm-up. Use the
Macrobenchmark JSON's measured iterations and distributions, not diagnostic
log-line counts, as the sample count.
Default five iterations are descriptive only: report count, individual samples,
median/range and the tool's frame distribution. Do not label a five-sample p95/p99
as a reliable tail estimate, and do not install simulator frame gates in CI.

## Build parity, ownership and limits

The existing `benchmark` variant has `BuildConfig.BENCHMARK=true`:
`ChapterRepository.getBookVolumesFlow` can return after local directory data,
and `getChapterContentFlow` can return after **untrusted** local fallback.
The earlier trusted-content return applies to both ordinary and benchmark
builds and remains valid. Local-book reads are another distinct early-return path.
Do not conflate them or declare every benchmark invalid.

`readerBenchmark` inherits release minification/non-debuggability and explicitly
sets `BENCHMARK=false`. The fixture's trusted revision matches registered source
metadata; legacy content has an empty revision. This measures the same repository
decisions as an ordinary build without deleting isolation switches used by other
tests. Explicit refresh calls the existing fresh repository operation, not a
replacement downloader or text-processing implementation.

Boundaries to retain in reports:

- This is an isolated reader-entry host around the real VM/repository/renderers,
  not the MainActivity bookshelf/navigation/onboarding journey. Full-app startup
  remains the existing `StartupBenchmark`; do not sum unrelated samples into a
  fabricated end-to-end number.
- Native-source execution/priority contention is covered; HTTP/TLS, script-worker
  isolation, work-manager scheduling and multi-account fairness are not simulated
  by a sleeping native source. Their owners must reuse these event definitions
  and add per-layer traces before making claims about those paths.
- Nonzero cross-mode and serialized SavedStateHandle guarantees belong to the
  original-position owner; this baseline reports anchor preservation without
  claiming certification of those stronger position contracts. No-state
  cold start still uses chapter/proportion recovery, not a persistent character
  checkpoint. Progress-source semantics remain a separate owner.
- The image fixture checks mixed-content retention, not remote image decode
  latency. Font-family, width/rotation and new-VM serialized-Bundle precision
  remain the native layout/position instrumentation matrix, not claims inferred
  from these tests. Existing `ReaderLayoutInstrumentedTest`, reader mode/cache
  contracts and `ReaderReadingRecordsTest` remain complementary regressions.
- No production startup, caching, pagination or scheduling algorithm is rewritten.
  A missing readiness event, wrong content, leaked/cancelled work or wrong request
  count is actionable correctness evidence; a slow sample alone is not a diagnosis.
- A physical-device result must actually be run and recorded. Emulator smoke
  validation proves executability only; no physical-device performance threshold
  or full product certification is implied by adding this suite.
