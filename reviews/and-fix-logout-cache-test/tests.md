# Test Report

Date: 2026-11-09

Task: and-fix-logout-cache-test - Android: AppStartRoutingTest 'logging out from settings clears the cached files' fails on main

Scope: base 5acb7590bb60248c6fb19287d4b2557ce1575dca .. HEAD db42593595577330b0b1b0a65b9d6601cc9bf506 on `main`; task commit db4259359 (test-only: `AppStartRoutingTest` cache-clear waits 5 s -> 30 s). The other commits in the range only touch `tasks/`. No production files changed (`git diff --name-only <base>..HEAD -- android/app/src/main` is empty). Uncommitted: this report and the added test below.

Requirements:
- `tasks/and-fix-logout-cache-test.md` acceptance criteria and notes
- `docs/ARCHITECTURE.md` "Coverage"
- Production code read: `AuthModule.cacheDirectory` (`android/app/src/main/java/ch/sonensei/cockpit/auth/AuthModule.kt`), `PreviewFiles` / `OpenedFiles` (`android/app/src/main/java/ch/sonensei/cockpit/files/DeviceFiles.kt`)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/auth/SessionCacheTest.kt`

## Tests added or updated

Count: 1

- `SessionCacheTest` - `the files feature's previewed and opened files go with the logout`: creates real cache files through `PreviewFiles.target` (`cacheDir/files-preview/`) and `OpenedFiles.target` (`cacheDir/files-open/<id>/`), runs the `AuthModule.cacheDirectory` SessionCache, and asserts both are gone and `cacheDir` is empty. Before this, `AppStartRoutingTest` only used a file at the top level of `cacheDir`, so nothing tied the logout cleanup to the paths the files feature actually uses. If the files feature moves its cache out of `cacheDir`, this test now fails.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Logout clears the files cache the files feature actually uses | `SessionCacheTest.the files feature's previewed and opened files go with the logout`, `SessionCacheTest.the cache directory is emptied, including subfolders, but kept itself`, `AppStartRoutingTest.logging out from settings clears the cached files`, `AppStartRoutingTest.a logout by the network layer also clears the cached files` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' -q` | Pass |
| AC2 (part): `AppStartRoutingTest` passes | `AppStartRoutingTest` (9 tests) | same command | Pass |
| AC2 (part): full `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | - | - | Not run: limited session time; the parent worker runs these as part of `done` |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*' -q`

Result:

Pass. ktlintCheck clean; JUnit XML in `android/app/build/test-results/testDebugUnitTest/`: 17 auth suites, 220 tests, 0 failures, 0 errors, 0 skipped (`SessionCacheTest` 3, `AppStartRoutingTest` 9).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (limited session time). The parent worker must run it (and `.taskcheck`) before `done`.

## .taskcheck

Result:

Not run (see above).

## Bugs found

None. The original failure (ComposeTimeoutException at `AppStartRoutingTest.kt:167`) did not reproduce here. The new test confirms that the cleanup itself covers the files feature's cache folders, which is consistent with the task's assumption that the failure came from timing (the sequential SessionCache loop on a loaded host) and not from a wrong cache location.

## Coverage

Status: not applicable - the task changes no production files (test-only change), so the task coverage scope is empty. No coverage run was done and no percentage is claimed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed production lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-fix-logout-cache-test; base 5acb7590bb60248c6fb19287d4b2557ce1575dca; HEAD db42593595577330b0b1b0a65b9d6601cc9bf506; task commit db4259359, as given by the parent and confirmed with `git log`/`git diff --stat`
- Source/test snapshot identifier (including uncommitted inputs): HEAD db42593595577330b0b1b0a65b9d6601cc9bf506 + `SessionCacheTest.kt` sha256 e9a71a9b6aa23f777106b7a58e9d5e4daaf380ebc45ce3eaf7e48d5f0e7996c8
- Exact included files; renamed/deleted/excluded files and reasons: none included. Excluded: `android/app/src/test/.../AppStartRoutingTest.kt` (test file), `tasks/*.md` (task metadata)
- Provider/version, command, working directory, and test selection: N/A (`cd android && ./gradlew createDebugUnitTestCoverageReport` not run because the scope is empty)
- Run start/completion time and exit status: N/A
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: N/A
- Final run after latest test additions and production fixes: N/A (no coverage run); focused tests were run after the test addition

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `AuthModule.kt` `cacheDirectory` (not task-changed) | Logout must delete the files feature's cache | Covered behaviorally by the new `SessionCacheTest` test; no coverage numbers because the file is outside the task's changed scope |

## Unable to test

- Reproducing the original timeout under host load: it depends on timing and could not be triggered deterministically; no follow-up from testing.
- Full Android check and `.taskcheck`: not run here because of the session-time limit; the parent worker must run them.

## Summary

Tests added and the focused validation passed (auth package, 220 tests, plus ktlintCheck). No production defects found. Coverage is not applicable because no production code changed. The full Android check and `.taskcheck` are still for the parent to run.
