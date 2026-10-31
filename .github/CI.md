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

Each workflow checks out the tested tree and runs the shared `ci-scope` action
before preparing Java, Gradle, the SDK, or an emulator. Devices share selection
and one APK build upstream of the two required API jobs. The action uses
[`ci_scope.py`](scripts/ci_scope.py) to select work. Jobs with no relevant work
finish successfully with a selection summary; workflow-level path filters are
not used, so all six required check names still report a result. A selector error
fails its job. An unavailable base/diff conservatively selects full checks.

| Changed scope | JVM tasks | Debug devices | Minified runtime | Lint / Release APK |
| --- | --- | --- | --- | --- |
| Independent UI: bookshelf, explore/search, statistics, Bangumi, about/licenses/logcat screens | Corresponding feature and reviewed consumers | Skip | Skip | Run |
| Discovery and categories UI | Discovery/categories, explore, navigation and discovery routing consumers | Skip | Source runtime smoke (uses categories/discovery) | Run |
| Reader UI | Reader, content, directory/detail, settings/font and speech-control consumers | API 24 reader panel/layout/spacing; API 24 + 35 wide reader position/spread | Skip | Run |
| Speech implementation/UI | App speech + reader tests and `:source-speech:test` | Reader coverage plus API 24 speech tests | Read-aloud smoke | Run |
| App updates: `data/update`, dedicated update settings and available-update dialog | App update tests and `SettingsHierarchyTest` | Skip | Skip | Run |
| EPUB export implementation, dedicated sharing/Worker/entry files and `epub/src/main` | `:epub:test` and App export consumers | API 24 EPUB sharing | Skip | Run |
| Backup implementation, dedicated import/export dialogs and backup Workers | App backup, persistence and shared-file consumers | Skip | Skip | Run |
| Storage queries/cleanup/ownership and dedicated book/storage management UI | App storage, downloads, local books and reading-record consumers | API 24 download persistence and local import | Skip | Run |
| Source module Kotlin implementation and existing source-catalog JSON | Complete App JVM plus module consumers below | Existing full Debug device coverage; speech module retains reader/speech devices | Source runtime; add read-aloud when speech is affected | Run |
| Shared/unknown code, resources, module inputs, dependencies, manifests, build or CI configuration | All existing JVM tasks | All existing selected classes | Both existing smoke tests | Run |
| Only root / `.github/*.md` / `docs/**/*.md` documentation, the three existing Issue templates, or `.github/FUNDING.yml` | Skip | Skip | Skip | Skip |

The exact allowlist is in the selector. In particular, shared `ui/components`, navigation,
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

Export selects `data.export.*`, the four dedicated export Worker regression
classes, `BookWorkIdentityTest`, `BookDownloadTest`, `BookRepositoryOperationsTest`,
`HostMultiSourceIntegrationTest`, `MixedSourceAcceptanceTest` and `DetailExportTest`.
These consumers cover actual publication, download reuse/cancellation, source
identity and WorkManager handoff outside the export directory. API 24 retains
`EpubShareInstrumentedTest`, including the legacy share Activity entry. Shared
image loading, Room, book repositories, detail navigation/ViewModel and Manifest
remain broader; EPUB local import is a separate responsibility. Module-only EPUB
test changes retain the test-input policy instead of launching App/device work.

Backup selects `data.backup.*` plus `SourceIdentityRoomTest`, `ReadingBookmarkTest`,
`BookDownloadTest`, `HostMultiSourceIntegrationTest`, `LocalBookRelinkTest`,
`ExternalFilesTest`, `ExternalFileViewModelTest` and `ReaderBookmarksUiTest`.
These retain restore transactions, pre-NextVol archives, cross-source associations,
download/local-file ownership, bookmark selection and Worker file handoff.
`ExportDataWork`, `ImportDataWork`, `SaveBookshelfWork` and the dedicated backup
dialogs/error message are allowed production entries. Shared external-file
dispatch, settings/navigation, database schemas/DAOs and UserData remain broad.
All `app/src/test/resources/backups/**` files, including README and legacy `.lnr`
archives, are test inputs: they select backup consumers and Lint without Release.
No separate backup device group is introduced.

Storage selects download and local-book JVM suites, book/download/local-book UI,
`SourceIdentityRoomTest`, `UserReadingDataTransactionTest`, `BookWorkIdentityTest`,
`BackupRestoreTransactionTest`, `ReaderReadingRecordsTest`,
`ReaderDirectoryProgressTest` and `SettingsHierarchyTest`. In particular,
`BookDownloadTest` exercises orphan attribution, both Worker cancellations,
failure/cancellation boundaries and retained reading records; `LocalBookStoreTest`
exercises metadata recovery before storage queries and clearing imported content.
API 24 retains download persistence and local import without browser/TTS suites.
Shared Room/DAO, BookIdentity, book repositories and reading-record core paths
still widen coverage. Changes to shared JVM consumers such as `BookDownloadTest`
or `LocalBookStoreTest` conservatively run the full App JVM suite; combined with
storage production edits they retain storage-only devices, not all devices.

### Source module consumers

Only `sources/<module>/src/main/kotlin/**/*.kt` in the table below is classified
as reviewed source implementation. Module names map to `:source-<module>` in
`settings.gradle.kts`. Each row runs the complete App JVM suite and
`:source-compatibility:test`, in addition to the listed `:source-<module>:test`
tasks. This explicit reverse dependency closure includes test dependencies;
compiling a dependent module alone would not run its tests.

| Changed module | Own and downstream source JVM tasks |
| --- | --- |
| rules | rules, network, import, rhino, execution, content, speech |
| network | network, import, execution, content, speech |
| import | import, content |
| rhino | rhino, execution, content, speech |
| execution | execution, content, speech |
| content | content |
| speech | speech |

The `source` scope keeps the existing full Debug device set because App reading,
download and browser consumers cross module boundaries. It retains the minified
source smoke and adds read-aloud smoke for rules/network/rhino/execution, whose
downstream consumers include speech. A speech-only module edit uses the existing
reader/speech devices and read-aloud smoke. App tests run without `ciTestScope` for
all these module edits, including mixed UI changes, so source adapters in download,
export, image and UI packages remain covered. EPUB module tests are selected only
when another changed path requires them. Production Lint and Release policy stays
unchanged.

The existing `catalog`, `Adult`, `Anime`, `Female`, `General`, `Literature` and
`Platforms` JSON files under `app/src/main/assets/source-catalog` are production
inputs. They select App/content/compatibility JVM, source devices and source smoke;
content reads catalog files directly and App tests consume the packaged assets.
TestFixtures, Rhino resources and compatibility browser/native-routing inputs keep
the shared-input rules below and union with production changes. Unknown catalog
files, module resources, new modules, API, Manifest, AIDL and shared build/dependency
changes still select full checks. No import or execution implementation is changed.

PR and merge-group comparisons use the event's base SHA and the checked-out merge
tree. A missing base is fetched shallowly by SHA. Git lists all changed paths
without rename collapsing, preserving deletions and both sides of cross-scope
moves; this avoids API file-list limits and downloading historical binary assets.
Manual `workflow_dispatch` always runs the full coverage of the chosen workflow.
Run all four workflows manually when a complete check of a ref is needed.

For local task selection (default remains full), use the existing Gradle task:

```sh
./gradlew :app:testDebugUnitTest -PciTestScope=bookshelf
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

UI filters select `bookshelf`, `explore`, `catalog`, `stats`, `bangumi`, `about`
(including licenses), or `logs`. Bookshelf also includes its home lifecycle test;
catalog includes explore, MainNavigation and DiscoveryRouting tests; about/logs
retain SettingsHierarchy, with LoggerRepository for logs. The manual `ui` filter
is the union of these feature sets. Shared components, navigation and theme inputs
remain full, and new test packages without a matching group keep the App suite.

Reader includes detail/directory consumers, MarkAllChaptersAsReadDialog,
SettingsHierarchy (the font-license entry) and SpeechControls (ReaderTopBar), as
well as reader/content/mixed-source tests. Speech unions the reader set with
`tts.*` and `ui.tts.*`; its API 24 services and minified read-aloud smoke remain.
All feature and business sets use positive unions; none exclude another group's
consumers. App tasks explicitly fail on no discovered tests or unmatched filters.

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
| Gradle (`setup-gradle`) | All build/test checks | Wrapper, dependencies, transforms, local build cache; only the serialized main warmer writes |
| Variant task outputs | Release and Minified checks | Separate Release/benchmark outputs; latest snapshot per ref and variant, saved after a successful APK build |
| ONNX Runtime install | All native build consumers | Source-built library, headers, licenses and manifest; exact input key, latest entry per main/PR ref |
| SDK | All checks | Platform, Build Tools, adb; main publishes one shared version |
| Emulator + one API image | Device checks only | Main publishes separate API 24 and API 35 entries |
| Robolectric SDKs | JVM check only | Main publishes Android runtime jars under `~/.m2/repository/org/robolectric` |

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
are removed by `cleanup-pr-caches.yml`. After Release workflow completion, it also
retains only the latest snapshot for each ref/variant and removes redundant PR
tool copies once a main entry exists for that tool. It checks out only main code,
never the completed workflow's head or its artifacts, with `actions: write`.
Concurrent cleanups tolerate caches already removed by another run or eviction.
Caches are disposable acceleration: misses fall back to normal dependency or
fixed tool downloads. AboutLibraries uses local license texts instead of making
per-library license/funding requests during APK builds.

`gradle-cache.yml` refreshes main's compilation caches after build/environment
configuration or `native/onnxruntime/**` changes, a manual main run, or daily maintenance at 09:17 UTC.
Ordinary source, test, documentation
and template changes do not trigger it. A lightweight selector compares the
push's `before` SHA with the checked-out commit and reuses the ordinary scope
plan; manual and scheduled runs select all compilation. App JVM checks map to
`:app:compileDebugUnitTestKotlin`; selected source/EPUB/compatibility JVM checks
map to `:testClasses`, including their Gradle compilation/resource dependencies.
App's existing dependency supplies source-content test fixtures. Selected Debug
devices add the App/test APK assembly tasks. Minified coverage only adds
`:benchmark:assembleBenchmark`, the instrumentation APK; it does not assemble
the shrunk App. No prewarming task runs tests, R8, or an emulator.

Separate API image jobs run only for fixed SDK action/script/package metadata
changes, a manual main run, or daily maintenance. An unavailable push diff conservatively selects
all compilation and SDK/image preparation. Main remains the only shared Gradle
cache writer, and PRs remain read-only consumers. Existing Release/benchmark
variant cache ownership is unchanged; no duplicate Gradle home cache is introduced.
Misses, failed prewarming, or cancellation leave
ordinary PR checks able to build normally using Gradle's input validation.

The independent ONNX Runtime cache uses the vendored sources, source inventories,
build scripts/profile, pinned toolchain action and Ubuntu runner image revision.
Kotlin, Matcha/JNI, PR commit IDs and native README/validation notes are not inputs.
Only `app/build/matcha/onnxruntime/install` is archived, never CMake object trees.
The builder verifies the source inventory and every exported file's hash before
skipping protoc and ORT compilation; missing, stale or damaged exports rebuild.
Successful PR jobs can publish this small cache within their own merge ref, while
main publishes entries available to other PRs. The four workflows can still build
independently on their first simultaneous miss. Cache hits remove the repeated
ORT compilation, not the existing checks or Matcha build. Main/PR retention keeps
each ref's newest ORT entry; main's different input key never replaces a PR's entry.

The main cache concurrency group preserves its running writer and retains at
most the newest pending update. Completion allows setup-gradle to save its cache;
manual cancellation does not guarantee a save. Replaced pending updates do not
accumulate. Daily maintenance repairs eviction without requiring a build-file
change or adding a full warmup to every source push. It restores reusable state
before compilation; it does not execute tests. This affects reuse, not which code
PR checks compile or validate.

Fixed SDK/image actions only save on main. PR cache misses still prepare the
required tools locally, without uploading private copies. The Robolectric action
likewise restores only on PRs; tests retain their normal download fallback.
`prepare-robolectric.sh` downloads the instrumented SDKs for the APIs selected by
App tests (24, 27, 28, 30, 33, 34, 35), plus API 36 used by Robolectric 4.16.1 for
compile-time resources. It verifies Maven SHA-512 checksums before installation,
without launching Gradle or tests. Its script hash versions the shared cache;
update the coordinates when upgrading Robolectric or adding tested APIs.

The warmer uses a run/attempt suffix for setup-gradle's root snapshot. A rerun on
the same commit can therefore publish repaired payloads instead of hitting an
immutable, partially evicted commit entry and skipping its save. After successful
warming, retention restores that exact root archive to read setup-gradle v4's
payload manifest. It preserves the current root and every referenced payload,
then deletes older roots and unreferenced payloads on main. An incomplete manifest
or missing payload stops Gradle pruning. This reader depends on the pinned action's
cache paths and metadata format; review them when upgrading setup-gradle.
Only the main warmer can write shared Gradle state, so its concurrency group also
serializes publication and pruning. Failed warming keeps the previous Gradle graph.
Old PR tool copies are removed only after a main replacement is available.

### Source prewarming evaluation (#721)

Source-triggered warming was withdrawn after a controlled remote replay, before
deployment to main. The [seed job](https://github.com/Renakoni/nextvol/actions/runs/38410742275/job/115342092865)
warmed main `7124552a` using its actual update scope: App unit-test compilation
and compatibility `testClasses`. This took 223.965 seconds plus 38 seconds for
the fixed environment. The [consumer run](https://github.com/Renakoni/nextvol/actions/runs/38412019549)
replayed real PR heads #709 (`dad00d23`) and #710 (`711f8d6c`) on fresh runners,
using isolated snapshots of the same original main task cache, before and after
that warming. Shared main caches remained read-only throughout the experiment.

| Compilation workload | Original cache (seconds) | Source-warmed cache (seconds) |
| --- | ---: | ---: |
| #709 JVM | 211.717 | 160.698 |
| #710 JVM | 234.068 | 216.808 |
| #710 shared Debug APK build | 161.644 | 238.921 |
| Total for two PRs | 607.428 | 616.427 |

Both JVM pairs gained only a compatibility test compilation hit; changed App
production/test inputs still compiled. The Debug pair had identical task states,
so its slower warmed run is not evidence of a cache regression. Even ignoring
that slowdown entirely and crediting both JVM improvements, 68.279 seconds saved
does not repay 261.965 seconds of warming: an extra 193.686 seconds remains.
Consumer jobs including setup/transfers totalled 776 versus 752 runner seconds;
adding the warming lower bound gives 1,013.965 versus 776 seconds. Queue time is
not runner time. These are compilation-path comparisons, not full CI runtimes:
tests and emulators were not repeated, and Release/benchmark caches were separate.
Historical Lint already hit the relevant production outputs and does not consume
these newly warmed test classes.

This single paired sample does not establish a universal performance ratio.
The estimate deliberately excludes selection, cache cleanup/publication and any
superseded running writer. Artifact transfer is an experimental proxy; snapshots
also retain old outputs that setup-gradle's production cleanup could discard.
These limits do not establish a net benefit, so ordinary source triggers remain
disabled. The temporary experiment jobs and script are removed.

The retained full prewarm capability was checked separately: all nine JVM
`compileTestKotlin` tasks restored `FROM-CACHE` on another clean runner. Adding a
source-content test input forced recompilation and produced its new class. The
136.476-second supplemental full warm and contract checks are excluded from the
source-policy comparison. See #721 for the complete experiment cost and evidence.

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

Debug device checks build the App and instrumentation APKs once per workflow,
only when API 24 or 35 has selected tests. The required API jobs always inspect
the upstream result: selection, build, or upload failure fails both checks;
an unselected API succeeds explicitly without downloading APKs or starting an
emulator. Selected jobs download the producer's immutable artifact ID and prepare
only the fixed SDK/image, without Java or Gradle setup.

The artifact contains the two APKs, their output metadata, and a build manifest.
Upload and download preserve `app/build/outputs/apk/debug` and
`app/build/outputs/apk/androidTest/debug` for `connected-tests.sh`. Validation checks
the tested merge SHA, workflow run, producer attempt, Debug variant/package, and
file hashes before preparing an emulator. Missing/expired artifacts, incomplete
APKs, and failed downloads fail the selected required checks. A failed-device-only
rerun reuses its successful producer's artifact and original build attempt;
rerunning all jobs creates a new uniquely named artifact and ID. No artifact is
selected from a different run, commit, or variant. Existing reports and narrow/
wide viewport failure propagation remain unchanged.

Sharing removes a duplicate build from runner consumption. Wall-clock changes
also depend on the shared build, artifact transfers, queueing, and device startup;
the saved parallel build time is not an equivalent reduction in waiting time.
The completed baseline device run `38393554272` spent 171/228 seconds compiling
APKs and 439/711 seconds in the API 24/35 jobs (1,150 runner seconds total); API 35
retried emulator preparation once. Compare future completed runs with these
conditions recorded, including the new producer job and artifact transfer cost.

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
