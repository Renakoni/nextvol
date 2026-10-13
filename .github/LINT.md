# Reviewed app Debug Lint findings

Scope: `:app:lintDebug` with the repository wrapper / JDK 22, including its
configured test analysis. This is not a claim about every module or variant.
The immediate pre-change report at #343 (`752943a4`) has 17 Error, 368 Warning,
and 8 Hint findings; translation/quantity/format errors have already been fixed
by #323/#327. The original #334 inventory predates those fixes.

## Error-level review (#334)

| Finding / location | Decision and evidence |
| --- | --- |
| NewApi: NativeBrowserFiles.initialize | Existing `check(SDK >= 28 && owner == profile)` already failed closed. Use an explicit API branch so Lint can prove the guard; unsupported providers and wrong owners still throw before touching the newer API. |
| NewApi: source_browser_theme, light | Move the navigation icon flag to v27. API24–26 only support light navigation icons, so use a black navigation bar there; API27+ retain the themed bar and dark icons. |
| NewApi: source_browser_theme, night | Keep the dark base theme and move the explicit navigation flag to night-v27. The versioned styles inherit all other base attributes. |
| WrongConstant: BookshelfHomeViewModel.layout | Root cause is `@BottomSheetBehavior.State` imported onto the BookshelfHomeUiState interface, not an invalid persisted layout enum. Remove the unrelated annotation; do not substitute a new Compose stability promise. |
| NetworkSecurityConfig: 10.0.2.2.nip.io | Debug fixture trust only. `includeSubdomains=false` makes the existing exact-host scope explicit. |
| NetworkSecurityConfig: vpn-only.hnovel.test | Same exact-host decision; no extension of the fixture CA or Release trust. |
| RestrictedApi: ExportBookToEPUBWork | ImageDownloader returns public `Result.success()` with empty output or `Result.failure()`. Compare against the public factory result, preserving failure/cancellation handling without referring to restricted `Result.Success`. |
| LocalContext: BookshelfHomeScreen share subject | Use configuration-aware stringResource with rememberUpdatedState; the existing remembered callback and pending worker read the current text. |
| LocalContext: BookshelfHomeScreen chooser title | Same state as the share subject, so both fields update together. |
| LocalContext: DataSettingsList chooser | Read stringResource during composition and pass the current title on click. Pending import success/failure texts also use rememberUpdatedState. |
| LocalContext: DetailScreen unread failure | Read configuration-aware text through rememberUpdatedState inside the pending failure callback, without restarting the write. |
| LocalContext: settings Navigation export failure | The dialog leaves composition before work finishes. Use the Toast resource-ID overload with applicationContext so text resolves at display time. |
| LocalContext: settings Navigation export success | Same display-time decision; do not freeze a string from the dismissed dialog. |
| LocalContext: settings Navigation chooser | Resolve the current stringResource for the click callback. |
| LocalContext: ThemeScreen download notice | Use rememberUpdatedState for the effect's message; locale changes do not restart an image download. |

## Exact deferred baseline (#325)

Only the two existing GestureBackNavigation locations in
`sourcebrowser/NativeSourceBrowserActivity.kt` and `SourceBrowserActivity.kt`
belong in `app/lint-baseline.xml`. Both use platform Activity/onBackPressed while
the manifest enables the new back callback. This is a real compatibility risk,
not proof that all back navigation fails or that a crash/data loss was observed.
#325 owns the gesture/key/history/verification completion and lifecycle changes.
Do not expand this baseline to other locations or suppress the detector globally.

Remove each entry with its #325 fix. `LintBaselineFixed` is an Error so obsolete
entries fail full Lint validation instead of silently remaining. It must not be
Fatal: lintVital only runs fatal detectors and would otherwise mistake the
unscanned back-navigation entries for fixed findings. Never regenerate this
file in CI. If an additional crash or data-loss path is confirmed, fix it rather
than recording it as a baseline exception.

## Warnings remain visible

- Higher-priority review: RequiresFeature guards are present (`relocated` checks
  STARTUP_FEATURE_SET_DIRECTORY_BASE_PATHS; the proxy call checks PROXY_OVERRIDE).
  No new feature/API suppression is added.
- InlinedApi's media playback service type is passed to ServiceCompat, which
  handles the platform version. The app keeps existing API24 device coverage.
- Two StaticFieldLeak findings retain application contexts, not Activities.
  SourceBrowserService.active is cleared onDestroy and the dedicated process
  terminates. These conclusions do not assert a heap-profiler leak test.
- MissingKeepAnnotation on Route is covered in the app's current R8 rules by
  `-keep,includedescriptorclasses class io.nightfish.lightnovelreader.api.**`.
  This is app-specific evidence, not a promise for every external API consumer.
- Cleartext base configuration is intentional for user-supplied HTTP sources;
  requests still pass source permission/address policy, and Release trusts only
  system CAs. It is not a reason to expand fixture trust.
- DefaultUncaughtExceptionDelegation is a real retained limitation: CrashLogHandler
  writes a panic report and exits directly instead of delegating to the previous
  handler. It is not suppressed or claimed fixed here. Scheduler fixed-rate
  behavior in the isolated execution service also remains a visible warning for
  that subsystem's lifecycle/performance work.
- PluralsCandidate and Chinese unused `one` branches need language-specific
  judgement; working Russian plurals are covered by #327 tests. UnusedResources,
  logging/style/KTX suggestions and dependency updates remain visible; this
  change does not mechanically delete resources or update dependencies to
  reduce the warning count.

Regression coverage includes browser profile API/owner protection, API24/26/27/35
day/night theme resolution, an unread write failing after a locale change,
persisted bookshelf layouts, image fallback/failure/cancellation and source EPUB
export. Actual run results and counts are recorded in the PR.
