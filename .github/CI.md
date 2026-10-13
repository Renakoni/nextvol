# Android checks

Pull requests, merge groups, and manual runs use four independent workflows:

| Workflow | Checks | Reports |
| --- | --- | --- |
| `unit-tests.yml` (1/4) | `JVM unit tests` | `unit-test-reports` |
| `device-tests.yml` (2/4) | `Isolated execution (API 24)` and `Isolated execution (API 35)` | `execution-platform-api-24`, `execution-platform-api-35` |
| `release-checks.yml` (3/4) | `Release APK` and `Minified source runtime (API 35)` | `release-build-reports`, `minified-source-runtime` |
| `lint.yml` (4/4) | `Android Lint` | `android-lint-reports` |

The JVM check does not wait for the release APK build. Selected device tests retain
the API matrix and diagnostic reports. The minified check builds the benchmark
variant when its source runtime or read-aloud coverage is affected.
The workflows have separate concurrency groups so they cannot cancel each other.

Require all six check names above in the `main` branch's status checks. In
particular, `Release APK` must be required separately now that it is outside the
JVM job. Keep strict up-to-date checking and the other branch protections enabled.
YAML alone does not update repository protection settings.

## Checks selected by change scope

Each required job checks out the tested tree and runs the shared `ci-scope` action
before preparing Java, Gradle, the SDK, or an emulator. The action uses
[`ci_scope.py`](scripts/ci_scope.py) to select work. Jobs with no relevant work
finish successfully with a selection summary; workflow-level path filters are
not used, so all six required check names still report a result. A selector error
fails its job. An unavailable base/diff conservatively selects full checks.

| Changed scope | JVM tasks | Debug devices | Minified runtime | Lint / Release APK |
| --- | --- | --- | --- | --- |
| Independent UI: bookshelf, explore/search, statistics, Bangumi, about/licenses/logcat screens | App UI tests, excluding reader and speech UI | Skip | Skip | Run |
| Discovery and categories UI | Same UI tests | Skip | Source runtime smoke (uses categories/discovery) | Run |
| Reader UI and reader tests | App UI tests plus content and mixed-source reader consumers, excluding speech UI | API 24 reader panel/layout/spacing; API 24 + 35 wide reader position/spread | Skip | Run |
| Speech implementation/UI/tests | App speech + reader tests and `:source-speech:test` | Reader coverage plus API 24 speech tests | Read-aloud smoke | Run |
| App updates: `data/update`, dedicated update settings and available-update dialog | App update tests and `SettingsHierarchyTest` | Skip | Skip | Run |
| Shared/unknown code, resources, source/EPUB modules, dependencies, manifests, build or CI configuration | All existing JVM tasks | All existing selected classes | Both existing smoke tests | Run |
| Only root / `.github/*.md` / `docs/**/*.md` documentation, the three existing Issue templates, or `.github/FUNDING.yml` | Skip | Skip | Skip | Skip |

The exact allowlist is in the selector. In particular, `ui/components`, navigation,
shared theme/settings and unreviewed data paths retain full checks. These are reviewed path
boundaries, not automatic dependency analysis; update the selection when sharing
code across them. Resource files and Markdown fixtures under source directories
are not assumed to be documentation. Mixed changes take the union; any unknown
path selects full checks. Full selection runs the maintained suite described below.
The Issue template allowlist names `bug_report.yml`, `feature_request.yml`, and
`maintenance_task.yml`; a new template or other repository configuration needs
review before it can skip Android work. PR templates use the `.github/*.md` rule.
Deleted documentation follows the same allowlist. A move into a source or fixture
directory also includes its destination, which retains that input's checks.

The update scope includes source parsing, query/download failures and Android
interaction through `data.update.*`, plus the update settings consumer in
`SettingsHierarchyTest`. Source revision updates and `CheckUpdateWork` (book
chapter updates) are different responsibilities and retain their broader checks.
MainActivity, global settings, shared navigation, resources and Manifest changes
still select full checks. App filters use positive unions, preserving existing
UI/reader/speech coverage when combined with the new update scope. The property
reduces test execution; App and test compilation still cover the whole App.

PR and merge-group comparisons use the event's base SHA and the checked-out merge
tree. A missing base is fetched shallowly by SHA. Git lists all changed paths
without rename collapsing, preserving deletions and both sides of cross-scope
moves; this avoids API file-list limits and downloading historical binary assets.
Manual `workflow_dispatch` always runs the full coverage of the chosen workflow.
Run all four workflows manually when a complete check of a ref is needed.

For local task selection (default remains full), use the existing Gradle task:

```sh
./gradlew :app:testDebugUnitTest -PciTestScope=ui
./gradlew :app:testDebugUnitTest -PciTestScope=reader
./gradlew :app:testDebugUnitTest :source-speech:test -PciTestScope=reader,speech
python3 .github/scripts/ci_scope.py --paths app/src/main/kotlin/indi/renakoni/nextvol/ui/home/reading/stats/StatsOverviewScreen.kt
```

The Gradle property filters test execution, not compilation: App and test sources
still compile together. Release assembly and Lint remain necessary for production
UI changes. Selection reduces unrelated work; it does not promise a fixed runtime.
The JVM job always runs the lightweight selector regression tests, including
documentation-only changes. Emulator/environment script checks run for full scope.

## JVM test coverage

### Test-only inputs

Production paths retain the policy above. Known test-only paths independently
select their execution environments and keep Lint, without a production Release
APK. App JVM tests/resources select the App owner (using a reviewed feature scope
where available); JVM modules select their complete owning test task. Test code
changes also select `:source-compatibility:test`, whose product-test file inputs
invalidate its cache when a referenced class is deleted or moved.

App Android tests select their device scope plus compatibility integrity. The two
known benchmark smoke classes select their minified check, which still assembles
both the benchmark App and instrumentation APK. Other benchmark paths remain
full until their execution contract is reviewed.

Shared inputs have explicit consumers: content testFixtures select content/App
JVM and the existing Debug devices (including the reading panel); Rhino test
resources select Rhino/compatibility JVM and Debug devices; compatibility browser
fixtures select content/compatibility JVM and Debug devices; native-routing site
fixtures select Debug devices. Live browser tests remain opt-in. Compatibility
reference files select its JVM task and are declared Gradle inputs.

`device-scopes` is separate from the summary `scopes`: adding a JVM-only test to a
reader production change must not widen devices to all source tests. Mixed paths
union tasks, App filters, device scopes, and minified classes independently. Unknown
production/shared configuration still selects full checks. No discovered-test or
filter failure is suppressed; a deliberately empty plan skips environment setup.

UI tests retain representative navigation, source ownership, selection, paging,
retry, download, and account interactions. Narrow copy, formatting, layout, and
empty-state variants are not all tested individually. Reader and speech suites,
download recovery, backup transactions, and source isolation remain covered.

Browser back tests use API 24 and 35 for the legacy and modern callback paths.
Source browsing tests retain cache eviction and cancellation boundaries without
traversing 1000 sources. Catalogue tests retain 69-page pagination, 7501 JSON
chapter objects crossing the worker boundary, and partial-directory recovery;
the separate 12001-chapter reopen stress test is omitted. These are deliberate
coverage tradeoffs, not equivalent replacements for every removed regression.

## Fixed tools and reusable caches

The repository contains scripts, SDK package metadata, and small AboutLibraries
license texts. Toolchains, Maven dependencies, and system images are not Git
assets. `.github/actions/android-environment` selects Temurin 21.0.8+9 for the
compiler and 22.0.2+9 for Gradle/tests, plus the repository's Gradle wrapper.
`.github/actions/android-sdk` restores only the SDK and optional image needed by
that job. Actions are pinned to commits and runners use `ubuntu-24.04`; GitHub
still updates the hosted OS, so this is not an immutable machine image.

App JVM unit tests use `-XX:TieredStopAtLevel=1` to avoid a JDK 22 C2
`Node::uncast` crash while inlining Robolectric-instrumented SQLite methods.
They retain Java 22 for PotatoEPUB bytecode compatibility and run all assertions
with C1 compilation. This test-only setting does not change the Gradle daemon,
other modules' JVM tests, Android compilation, or device/runtime behavior.
`-XX:ReservedCodeCacheSize=240m` retains the normal tiered JVM's code cache
budget: C1 otherwise reduces it to 48 MiB, which the full Robolectric suite
exhausts with `Out of space in CodeCache for adapters`. The Java heap is unchanged.

`prepare-android-sdk.sh` names exact upstream ZIP revisions: SDK platform 37.0 r2,
Build Tools 36.0.0, Platform Tools 37.0.1, Emulator 37.1.11 (build 15917651), and
Google APIs x86_64 images API 24 r27 / API 35 r9. A cache miss downloads only the
missing packages with bounded connection times and retries. Installed-package
metadata in `ci-environment/sdk-packages` lets AGP recognize those exact packages
without sdkmanager/avdmanager repository scans. Change URLs, metadata, and cache
keys together when upgrading. Automatic Gradle JDK/SDK provisioning is disabled.

| Cache | Consumers | Contents / writer |
| --- | --- | --- |
| Gradle (`setup-gradle`) | All build/test checks | Wrapper, dependencies, transforms, local build cache; main writes, PR/merge-group runs read only |
| Variant task outputs | Release and Minified checks | Separate Release/benchmark output directories; restored from the latest same-variant entry and saved after a successful APK build |
| SDK | All checks | Platform, Build Tools, adb; saved immediately after successful preparation |
| Emulator + one API image | Device checks only | API 24 and API 35 have separate exact keys; saved before boot/tests |
| Robolectric SDKs | JVM check only | Runtime jars under `~/.m2/repository/org/robolectric` |

`org.gradle.caching=true` remains enabled. Gradle validates task inputs before
reusing outputs; a restored cache does not skip changed code or guarantee every
task is cacheable. Only setup-gradle owns the Gradle dependency/transform cache:
do not add a second whole-home/wrapper cache or archive every module's `build/`
directory. PR checks read main's reusable entries rather than each writing another
large snapshot. Release and benchmark additionally retain task outputs, including
R8, in separate local caches selected by `ci.init.gradle`. These start with shared
main outputs and preserve each variant's own entries. The minified cache is saved
before device tests so a test failure does not discard successful compilation.
Cache keys include the variant and commit; restore prefixes allow reuse after
source edits, with Gradle checking the actual task inputs. Closed PR cache entries
are removed by `cleanup-pr-caches.yml`, which runs only trusted base workflow code
and deletes only that PR's merge-ref caches without checking out PR code.
Caches are disposable acceleration: misses fall back to normal dependency or
fixed tool downloads. AboutLibraries uses local license texts instead of making
per-library license/funding requests during APK builds.

`gradle-cache.yml` populates main's caches after build/environment configuration
changes or a manual main run. It compiles Debug/test APKs and unit-test sources,
and assembles the benchmark instrumentation APK. It does not run R8, boot a
device, or execute tests. Separate API image jobs only prepare SDK files. This
main population matters because caches written by one PR are not shared with
other PRs. Source edits can still miss older compiled outputs; manually warming
main refreshes them without introducing extra checks on every PR.

## Emulator scope and failure handling

JVM tests, Lint, and Release assembly do not start an emulator. Device tests cover
real Binder/isolated processes, WebView, Android UI, and service behavior that a
host JVM cannot fully validate. API 24 retains minimum-version coverage. Full
selection runs 17 normal-viewport classes on API 24 and 9 on API 35; both also run
the two wide-viewport reader classes. `ci_scope.py` owns these class lists, and
`connected-tests.sh` installs the prebuilt APKs only for selected nonempty suites.
The Minified API 35 check runs only `MinifiedSourceRuntimeTest` and
`ReadAloudSmokeTest` against a shrunk App; it does not repeat the Debug suite.
Both `:app:assembleBenchmark` and `:benchmark:assembleBenchmark` are required:
the former builds the shrunk App, the latter its instrumentation APK. The target
APK configuration is non-transitive because App libraries are already inside
that APK; resolving them again can select unrelated Desktop/JVM variants.

APKs build before boot, avoiding competition between compilation and the device.
`run-static-emulator.sh` creates a fresh 320x640, 160dpi KVM AVD from the fixed
image, with no snapshots or online SDK tools. Boot/ADB readiness has two
180-second polling windows with separately bounded ADB commands. Only a failed
startup gets a second attempt with fresh data; assertions, App crashes, and tests
are never rerun automatically. Missing tools/KVM fail before launch.

`emulator-tests.sh` retains the NexusLauncher ANR readiness check and disables
only API 24's unused preinstalled Google Messaging package, whose boot crash can
leave a system error dialog over the tests. Remaining dialogs from these packages
block test startup. It streams crash/system/event logcat to `runtime-events.txt`
throughout testing and collects final diagnostics without replacing the test exit
status. A missing test report alone is not proof of an App crash: correlate these
logs with instrumentation output and the job timeout/cancellation reason.

## Lint and translation coverage

`Android Lint` runs the standard-library resource contract tests/checker and
`:app:lintDebug` plus `:app:lintVitalRelease` with the repository wrapper and JDK 22.
APK jobs exclude Vital Lint to avoid duplicating this work alongside R8; local
assembly defaults are unchanged. No emulator is required.
Lint still runs after a resource-check failure to collect its diagnostics. Bash
pipefail preserves failures while tee records output; neither step allows errors
to become a successful check. HTML/XML/text reports and logs upload with
`if: always()`, including failing runs. Setup failures before analysis may leave
no Lint report; the Actions step log remains the evidence in that case.

| Scope | Coverage |
| --- | --- |
| App Debug | Full Android Lint, including main/Debug resources and the configured test source analysis |
| App translations | Default/en, Simplified Chinese, Traditional Chinese, generic Russian and existing ru-RU overrides; see [resource contracts](RESOURCE_CONTRACTS.md) |
| `:api` | Dependency model/class information used by app analysis; no independent `:api:lintDebug` scan |
| `:benchmark` | Android test module, exercised by the existing minified device job; not independently linted |
| App Release/snapshot/benchmark | No full variant Lint here; Release assembly/lintVital and minified runtime remain separate checks |
| JVM source/EPUB modules | Existing JVM/build checks; not independent Android Lint targets |

The gate fails on Error/Fatal diagnostics outside the reviewed baseline. Warnings
remain visible without requiring a mechanical cleanup of all existing warnings.
See [Lint triage](LINT.md) for exact deferred locations and removal conditions.
Do not regenerate a baseline automatically in CI, globally disable i18n/API/back
checks, or add a known crash/data-loss defect to it. `LintBaselineFixed` is an
Error in full Lint: when a deferred finding is fixed, remove its obsolete entry.
It is not Fatal because lintVital does not run every detector in the baseline.

The Lint concurrency key includes the event and PR/ref, keeping PR, merge-group,
and manual runs independent from one another and from the other three workflows.
Configure `Android Lint` as required only after a real PR has passed; read back
the protection settings and retain the existing five checks and strict mode.

## Maintenance

After workflow changes, run `actionlint`, the affected script regression tests, and
the affected Gradle input/task resolution. Selection changes must cover mixed
scopes, shared/unknown paths, docs, renames/deletions, fallback to full checks, and
preserved device failures. For cache or environment changes, exercise both cache
hits and misses, failed downloads, and interrupted boot. Generated
test reports belong in Actions artifacts or local build directories, not in Git.
