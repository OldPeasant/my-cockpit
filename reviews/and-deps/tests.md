# Test Report

Date: 2026-10-20

Task: and-deps - Android dependencies and test setup

Scope: base ffc40318e7627602ff62f8833f6a70a717ac5f73 (`git merge-base HEAD origin/main`) .. HEAD 177102607c993ae08492e66819ee02b7a0d7ac73 on branch `main`, no uncommitted changes. This is the reviewed revision. Task-owned commits: 00eab55 "add Android libraries and Robolectric Compose UI test", 881d4c3 "test-library smoke tests and test report", 266d6cd "code review report", 1771026 "record JaCoCo coverage for Robolectric tests (CR-1)". The earlier report measured the pre-rebase revision 9c52fa9 on base b6054b9. After the rebase it is commit 00eab55, and the task file list is the same. Changed files: `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/app/build.gradle.kts`, `android/app/src/test/.../ui/PlaceholderScreenTest.kt`, `android/app/src/test/.../TestLibrariesSmokeTest.kt`, `docs/ARCHITECTURE.md`, `reviews/and-deps/*`. No production Kotlin source changed (nothing under `android/app/src/main`).

Requirements:
- `tasks/and-deps.md` acceptance criteria and notes (assumptions: Tink instead of security-crypto; Hilt/Room plugins applied without an Application or database)
- `docs/ARCHITECTURE.md` "Coverage"
- `docs/QUALITY.md` (report contract)
- `AGENTS.md` project notes (Android check command, lint conventions)
- `reviews/and-deps/fixes.md` (fix for TW-1 / CR-1)

## Test files changed

No test files changed in this run. The test files this task added or changed are already committed in 881d4c3:

- `android/app/src/test/java/ch/sonensei/cockpit/ui/PlaceholderScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/TestLibrariesSmokeTest.kt`

## Tests added or updated

Count: 0 in this run (4 in the earlier run, already committed)

The earlier run added these tests:
- `PlaceholderScreenTest.placeholderScreenDoesNotShowOtherText` (Robolectric Compose; a negative check, so the test cannot pass against an empty semantics tree)
- `TestLibrariesSmokeTest.mockkStubsSuspendFunctionsInsideRunTest` (MockK `coEvery`/`coVerify` in `runTest`)
- `TestLibrariesSmokeTest.runTestSkipsDelaysWithVirtualTime` (kotlinx-coroutines-test virtual time)
- `TestLibrariesSmokeTest.turbineObservesStateFlowEmissionsInOrder` (Turbine on a `StateFlow`)

No tests were added in this run. The fix only changed the JaCoCo build configuration, and the existing tests now show it works (see Coverage).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: catalog + app build add Hilt (KSP), Navigation Compose, Retrofit/OkHttp/serialization converter, Room (KSP), WorkManager, DataStore, Tink, Credential Manager, Glance, Coil, Robolectric + Compose UI test, MockK, coroutines-test, Turbine | Diff inspection of `libs.versions.toml` / `app/build.gradle.kts`; test-library usability: `TestLibrariesSmokeTest` (3 tests); Robolectric + Compose UI test: `PlaceholderScreenTest` (3 tests) | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` | Pass (everything resolves and compiles; the test libraries run). Production libraries are only checked for resolution and compilation, because no code uses them yet. |
| AC2: a Robolectric Compose UI test of the placeholder screen runs in `testDebugUnitTest` | `PlaceholderScreenTest.placeholderScreenShowsTheScaffoldText`, `placeholderScreenDoesNotShowOtherText`, `placeholderTextNamesTheApp` | same command | Pass: `PlaceholderScreenTest` tests=3, failures=0, errors=0, skipped=0. Coverage now confirms the Robolectric run executes the screen code. |
| AC3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android unit suite (6 tests) + ktlint + lint | same command | Pass: BUILD SUCCESSFUL, 54 tasks executed |
| AC3: ARCHITECTURE.md stack table updated | Not testable by automated tests (documentation); the diff shows the stack row was changed | n/a | Not testable |
| TW-1 / CR-1 fix: Robolectric tests are recorded by JaCoCo | existing `PlaceholderScreenTest` tests | same command | Pass: `PlaceholderScreen.kt` 95/104 instructions covered (it was 0/104) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks`

Result:

Pass (PlaceholderScreenTest 3/3, TestLibrariesSmokeTest 3/3)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` (2026-10-20T08:57:04Z - 08:57:18Z)

Result:

Pass (BUILD SUCCESSFUL in 13s, 54 actionable tasks executed, 6 tests, 0 failures). I did not run the server and web suites because the task touches no server or web files.

## .taskcheck

Result:

Not run: this helper's sandbox does not allow `sh scripts/check.sh` (the command needs approval). The parent worker must run it.

## Bugs found

None open.

TW-1 (Robolectric unit tests recorded no JaCoCo coverage) is resolved at 1771026. `android/app/build.gradle.kts` now applies the `jacoco` plugin and sets `JacocoTaskExtension.isIncludeNoLocationClasses = true` with `excludes = listOf("jdk.internal.*")` for every `Test` task. The fresh report shows the Robolectric-run code covered, for example `PlaceholderScreen.kt` line 19 `ci="26"` and line 21 `ci="27"`. In the earlier run every counter was 0.

## Coverage

Status: not applicable for the task scope, because the task changed no production source files (only build configuration, tests and docs). The informational whole-file numbers below are fresh and valid now that TW-1 is fixed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-deps; base ffc40318e7627602ff62f8833f6a70a717ac5f73 (`git merge-base HEAD origin/main`, as the parent specified); HEAD 177102607c993ae08492e66819ee02b7a0d7ac73; commits 00eab55, 881d4c3, 266d6cd, 1771026 from `git log base..HEAD`; scope from `git diff --stat base..HEAD`, which matches the task's `touches` plus tests, docs and reviews.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1771026, clean working tree (`git status --short` empty). `TestLibrariesSmokeTest.kt` sha256 666672ab58835207c96e4c9de314ee3c779ce6764df4162f3ad5fce90fad0b7f, `PlaceholderScreenTest.kt` sha256 05e1aa7647b46beb0aa25a57701486817f83c5483a70d76e39663ca2deea7efa, `app/build.gradle.kts` sha256 7c00a1034d90c070f4ec9de2b381577ea9e064080370037ff6ff74ef4bb4f75d. The hashes were identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: the task coverage scope is empty (no changes under `android/app/src/main`). Excluded: `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/app/build.gradle.kts` (build configuration, cannot be instrumented), both test files (tests), `docs/ARCHITECTURE.md` and `reviews/and-deps/*` (docs). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage` plus the Gradle `jacoco` plugin (Gradle 9.8.1); `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` run in `android/`; all debug unit tests (6).
- Run start/completion time and exit status: 2026-10-20T08:57:04Z - 08:57:18Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 b94868edb79eaf5b9c37107e7081776b2cd65ade0d661f7e99de8054c4fbb5ba, JaCoCo session `dell26-4cfebf09`. This is a new session; the earlier run's session was `dell26-6a5dc7fe`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun-tasks`, new session id and report hash, test result timestamps 08:57:10Z/08:57:12Z, input hashes the same before and after).
- Final run after latest test additions and production fixes: yes. It ran on 1771026, which includes the TW-1 fix and all test additions.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no production file changed) | N/A | N/A | N/A | N/A |

Informational only, not task-scoped. These files did not change; JaCoCo instructions stand in for statements:

| File | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/ui/PlaceholderScreen.kt` | 95/104 (91.3%) | N/A (0 total) | 5/6 (83.3%) | 10/11 (90.9%) |
| `android/app/src/main/java/ch/sonensei/cockpit/MainActivity.kt` | 0/33 (0%) | N/A (0 total) | 0/4 (0%) | 0/5 (0%) |
| Aggregate of both (summed counters; no other files in the report) | 95/137 (69.3%) | N/A | 5/10 (50%) | 10/16 (62.5%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PlaceholderScreen.kt`:29 | `@Preview` function `PlaceholderScreenPreview`, used only by the IDE | Gap is fine: preview-only code, not product behavior |
| `MainActivity.kt`:9-14 | Activity launch; not covered by this task's acceptance criteria | Not tested here; activity wiring belongs to the shell task (and-shell) |

## Unable to test

- AC1 production libraries (Hilt, Room, Retrofit, WorkManager, DataStore, Tink, Credentials, Glance, Coil, Navigation) are only checked for dependency resolution and compilation. No code uses them yet, so their runtime behavior cannot be tested in this task. Follow-up: the feature tasks that wire them in.
- Hilt testing (`hilt-android-testing` + `kspTest(hilt.compiler)`) is not exercised because there is no `@HiltAndroidApp` Application yet (task assumption). Follow-up: and-shell.
- `.taskcheck` was not run here (permission); the parent must run it.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-1 is resolved at 1771026: JaCoCo now records the Robolectric tests. `ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` passes with 6/6 tests.
