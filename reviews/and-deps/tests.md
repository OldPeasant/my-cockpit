# Test Report

Date: 2026-10-20

Task: and-deps - Android dependencies and test setup

Scope: base b6054b9217ac518865f0def3e161c25783097e41 .. HEAD 9c52fa9b829c7283eb92220fe925f46f90d0f006 (one task commit, 9c52fa9 "and-deps: add Android libraries and Robolectric Compose UI test"), branch `main`, plus the uncommitted test changes listed below. Changed files in the task commit: `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/app/build.gradle.kts`, `android/app/src/test/java/ch/sonensei/cockpit/ui/PlaceholderScreenTest.kt`, `docs/ARCHITECTURE.md`. No production Kotlin source changed (`git diff --name-only b6054b9..HEAD -- android/app/src/main` is empty).

Requirements:
- `tasks/and-deps.md` acceptance criteria and notes (assumptions: Tink over security-crypto; Hilt/Room plugins applied without an Application/database)
- `docs/ARCHITECTURE.md` "Coverage"
- `docs/QUALITY.md` (report contract)
- `AGENTS.md` project notes (android check command, lint conventions)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/ui/PlaceholderScreenTest.kt` (modified, uncommitted)
- `android/app/src/test/java/ch/sonensei/cockpit/TestLibrariesSmokeTest.kt` (new, uncommitted)

## Tests added or updated

Count: 4

- `PlaceholderScreenTest.placeholderScreenDoesNotShowOtherText` (Robolectric Compose): asserts an absent text is not found, so the existing `placeholderScreenShowsTheScaffoldText` cannot pass against an empty or fake semantics tree.
- `TestLibrariesSmokeTest.mockkStubsSuspendFunctionsInsideRunTest`: MockK `coEvery`/`coVerify` on a suspend function inside `runTest`.
- `TestLibrariesSmokeTest.runTestSkipsDelaysWithVirtualTime`: kotlinx-coroutines-test virtual time (a 60 s `delay` advances `currentTime` to 60000 without waiting). Uses `@OptIn(ExperimentalCoroutinesApi::class)` because `currentTime` is still experimental; without it the Kotlin compiler warns.
- `TestLibrariesSmokeTest.turbineObservesStateFlowEmissionsInOrder`: Turbine `test {}` on a `StateFlow`.

These smoke tests prove that the test libraries added by criterion 1 resolve, compile and run together on this JDK 21 / AGP 9.4.1 toolchain. They do not test product behavior.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: catalog + app build add Hilt (KSP), Navigation Compose, Retrofit/OkHttp/serialization converter, Room (KSP), WorkManager, DataStore, Tink, Credential Manager, Glance, Coil, Robolectric + Compose UI test, MockK, coroutines-test, Turbine | Diff inspection of `libs.versions.toml` / `app/build.gradle.kts` (all listed libraries present, Hilt/Room compilers on `ksp`); test-library usability: `TestLibrariesSmokeTest` (3 tests), Robolectric + Compose UI test: `PlaceholderScreenTest` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` | Pass (build resolves and compiles everything; test libraries run). Production libraries are only checked for resolution/compilation; no runtime use exists yet. |
| AC2: a Robolectric Compose UI test of the placeholder screen runs in `testDebugUnitTest` | `PlaceholderScreenTest.placeholderScreenShowsTheScaffoldText`, `PlaceholderScreenTest.placeholderScreenDoesNotShowOtherText`, `PlaceholderScreenTest.placeholderTextNamesTheApp` | same command | Pass: `TEST-ch.sonensei.cockpit.ui.PlaceholderScreenTest.xml` tests=3, failures=0, errors=0, skipped=0 |
| AC3: `./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole android unit suite (6 tests) + ktlint + lint | same command | Pass: BUILD SUCCESSFUL, no Kotlin compiler warnings in test sources |
| AC3: ARCHITECTURE.md stack table updated | Not testable by automated tests (documentation); diff shows the stack row changed | n/a | Not testable |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest`

Result:

Pass (PlaceholderScreenTest 3/3, TestLibrariesSmokeTest 3/3). A first run showed a Kotlin opt-in warning for `currentTime`; fixed in the test with `@OptIn`, re-run clean.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` (2026-10-20T08:51:06Z - 08:51:18Z)

Result:

Pass (BUILD SUCCESSFUL, 6 tests, 0 failures). Server and web suites were not run: the task touches no server or web files.

## .taskcheck

Result:

Not run: executing `sh scripts/check.sh` was not permitted in this helper's sandbox (command required approval). The parent worker must run it.

## Bugs found

### Robolectric-run unit tests record no JaCoCo coverage

Finding ID:

`TW-1`

Test:

`createDebugUnitTestCoverageReport` after `PlaceholderScreenTest` (all 3 tests pass, yet `PlaceholderScreen.kt` shows 0 covered instructions, including `placeholderText`, which `placeholderTextNamesTheApp` calls directly)

Production source:

`android/app/build.gradle.kts` (test task / JaCoCo configuration; no line, the setting is missing)

Requirement:

`docs/ARCHITECTURE.md` "Coverage": Android coverage via `./gradlew createDebugUnitTestCoverageReport` informs the test-writer; the task sets up the Android test stack, and Robolectric is the stack that every Compose UI unit test will use.

Expected behavior:

Code executed by Robolectric tests (`@RunWith(AndroidJUnit4::class)`) is counted in `app/build/reports/coverage/test/debug/report.xml`.

Actual behavior:

Every counter in the report is 0 covered (137 instructions, 16 lines, 10 methods missed). Diagnostic: a temporary plain-JUnit test calling `placeholderText("X")` (no Robolectric runner) made line 14 of `PlaceholderScreen.kt` covered (`ci="3"`); the probe was removed afterwards. So the agent works, but classes loaded by Robolectric's sandbox class loader are not recorded. Before this task the test class ran without Robolectric, so this is a regression introduced by adding `@RunWith(AndroidJUnit4::class)`. The usual fix is to configure the JaCoCo extension of the unit-test tasks with `isIncludeNoLocationClasses = true` and `excludes = listOf("jdk.internal.*")`.

Severity:

Medium (the check passes; but Android task-scoped coverage is silently 0 for all Robolectric tests, for every later Android task)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (build configuration in `android/app/build.gradle.kts`, which is in this task's `touches`)

## Coverage

Status: not applicable - the task changed no production source files (only build configuration, a test and docs). Informational whole-file numbers are given below; they are also affected by TW-1.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed production lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-deps; base b6054b9217ac518865f0def3e161c25783097e41 (given by the parent); HEAD 9c52fa9b829c7283eb92220fe925f46f90d0f006; one task commit, 9c52fa9; scope from `git diff --name-only b6054b9..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9c52fa9 + uncommitted `TestLibrariesSmokeTest.kt` sha256 666672ab58835207c96e4c9de314ee3c779ce6764df4162f3ad5fce90fad0b7f and `PlaceholderScreenTest.kt` sha256 05e1aa7647b46beb0aa25a57701486817f83c5483a70d76e39663ca2deea7efa (hashes the same before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: task coverage scope is empty (`git diff --name-only b6054b9..HEAD -- android/app/src/main` returns nothing). Excluded: `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/app/build.gradle.kts` (build config, not instrumentable), `PlaceholderScreenTest.kt` (test), `docs/ARCHITECTURE.md` (docs).
- Provider/version, command, working directory, and test selection: JaCoCo via AGP 9.4.1 `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --rerun-tasks` in `android/`; all debug unit tests (6).
- Run start/completion time and exit status: 2026-10-20T08:51:06Z - 08:51:18Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 99f23ba40d69da4ff6e10fa64ce6289996ae5eb9948e77c1a4c92d1d3a11969d, JaCoCo session `dell26-6a5dc7fe`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (`--rerun-tasks`, new session id, test sources hashed before and after).
- Final run after latest test additions and production fixes: yes for the test additions; no production fix has been made yet (TW-1 open), so after the fix coverage must be re-measured.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no production file changed) | N/A | N/A | N/A | N/A |

Informational, not task-scoped (unchanged files, instructions as the statement metric):

| File | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/ui/PlaceholderScreen.kt` | 0/104 (0%) | N/A (0 total) | 0/6 (0%) | 0/11 (0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/MainActivity.kt` | 0/33 (0%) | N/A (0 total) | 0/4 (0%) | 0/5 (0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PlaceholderScreen.kt`:14, 17-25, 29 | Exercised by the 3 passing Robolectric tests, but not recorded | TW-1 (build config); no further test can fix this |
| `MainActivity.kt`:9-14 | Activity launch; outside this task's acceptance criteria | Not tested; activity wiring belongs to the shell task |

## Unable to test

- AC1 production libraries (Hilt, Room, Retrofit, WorkManager, DataStore, Tink, Credentials, Glance, Coil, Navigation) are only checked for dependency resolution and compilation; they have no code that uses them yet, so their runtime behavior cannot be tested in this task. Follow-up: the feature tasks that wire them.
- Hilt testing (`hilt-android-testing` + `kspTest(hilt.compiler)`) is not exercised: there is no `@HiltAndroidApp` Application yet (task assumption). Follow-up: and-shell.
- `.taskcheck` not run here (permission); the parent must run it.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. (TW-1: the build configuration records no JaCoCo coverage for Robolectric tests. All tests and `ktlintCheck testDebugUnitTest lintDebug` pass.)
