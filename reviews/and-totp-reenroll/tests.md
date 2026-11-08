# Test Report

Date: 2026-11-08

Task: and-totp-reenroll - Android handles forced TOTP re-enrollment

Scope: task commits bdd0a970f and e3be6baaa on top of base 27218eaafecd0a876c80862f7075c6c5da1382de
(`git diff 27218eaaf..HEAD -- android`), HEAD e3be6baaa25c3d11279985869a7fef8f54940b7f, branch main. Uncommitted at
measurement time: only the test additions listed below (no production changes).

Requirements:
- `tasks/and-totp-reenroll.md` acceptance criteria and the recorded assumption (open the web enrollment page
  `{server}/enroll-totp` instead of enrolling natively)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", Android test stack)
- Parent instructions (scope, base, task commits)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginScreenTest.kt`

(`PasskeyViewModelsTest.kt` was changed by the task commits and reviewed; not changed by the test-writer.)

## Tests added or updated

Count: 5 added by the test-writer (the task commits already added 5 and adjusted 1 existing test)

Reviewed tests from the task commits:
- AuthRepositoryTest: `403 means TOTP must be set up again in the web app and stores nothing`; `other error statuses
  are server errors` now uses 404/503 (403 moved to its own result; this reflects the new requirement and does not
  weaken the test)
- LoginViewModelTest: `a login that needs TOTP set up again offers the web enrollment page until the next attempt`
- PasskeyViewModelsTest: `a passkey login that needs TOTP set up again offers the web enrollment page`
- LoginScreenTest: `the TOTP re-enrollment error offers a button that opens the web enrollment page`;
  `the web enrollment button is shown only with the TOTP re-enrollment error`

Added by the test-writer:
- AuthRepositoryTest: `the web enrollment page stays below a server address with a sub-path` (server entered as
  `{mockserver}/cockpit` without trailing slash, expected `{mockserver}/cockpit/enroll-totp`)
- AuthRepositoryTest: `without a configured server there is no web enrollment page`
- LoginViewModelTest: `a TOTP re-enrollment without a known enrollment page still shows the error`
- LoginScreenTest: `the web enrollment button is labelled in German` (Robolectric qualifier `de`)
- LoginScreenTest: `the web enrollment button does not crash without a browser` (UriHandler throws)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: login reporting re-enrollment (403) is recognized | AuthRepositoryTest `403 means TOTP must be set up again in the web app and stores nothing`, `other error statuses are server errors` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'` | Pass |
| AC1: the button opens the configured server's web enrollment page, also for a server with a sub-path; no URL without a server | AuthRepositoryTest `the web enrollment page stays below a server address with a sub-path`, `without a configured server there is no web enrollment page` | same | Pass |
| AC1: password and passkey paths store the URL with the error and clear it on the next submit | LoginViewModelTest `a login that needs TOTP set up again offers the web enrollment page until the next attempt`, `a TOTP re-enrollment without a known enrollment page still shows the error`; PasskeyViewModelsTest `a passkey login that needs TOTP set up again offers the web enrollment page` | same | Pass |
| AC1: clear message plus a button that opens the web page; button only with that error; no crash without a browser | LoginScreenTest `the TOTP re-enrollment error offers a button that opens the web enrollment page`, `the web enrollment button is shown only with the TOTP re-enrollment error`, `the web enrollment button does not crash without a browser` | same | Pass |
| AC1: choice recorded with `tasks.py assume` | not a test; the assumption is present in `tasks/and-totp-reenroll.md` Notes | inspection | Not testable (documentation) |
| AC2: new string in English and German | LoginScreenTest `the TOTP re-enrollment error offers a button that opens the web enrollment page` (English text), `the web enrollment button is labelled in German` (German text) | same | Pass |
| AC3: unit/Compose tests and `ktlintCheck testDebugUnitTest lintDebug`, `sh scripts/check.sh` | all of the above; ktlintCheck ran | same | Pass for ktlintCheck + auth tests; Not run: full `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh` (left to the parent, see below) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.*'`

Result:

Pass (BUILD SUCCESSFUL; JUnit XML: 219 auth tests, 0 failures, 0 errors, 0 skipped; all 10 TOTP re-enrollment tests
listed above passed)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer (the full Android suite and lint are long and known to have unrelated load flakes; the parent
runs them as AC3 evidence).

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh`; the parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified (whole-file, focused auth test selection)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: inspected, not counted. Method: the JaCoCo line entries (`mi`/`mb`) for the lines added by
`git diff -U0 27218eaaf..HEAD -- android/app/src/main/java` were checked by hand. The only added executable line with
missed counters is LoginScreen.kt:276 (1 missed instruction, 1 missed branch); every other added executable line in the
three files has no missed instructions or branches. No changed-line totals are given.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-totp-reenroll; base
  27218eaafecd0a876c80862f7075c6c5da1382de; HEAD e3be6baaa25c3d11279985869a7fef8f54940b7f; task commits bdd0a970f and
  e3be6baaa as given by the parent, checked against `git diff --stat 27218eaaf..HEAD` (only task-touched files)
- Source/test snapshot identifier (including uncommitted inputs): HEAD e3be6baaa plus uncommitted test files, sha256:
  AuthRepositoryTest.kt 65587ccc1ed644371a2fb166485187d305631dec3bb1bbc68d1775d52051e31d,
  LoginViewModelTest.kt 29ae9a74bea25e877ad798cb88312d3415e19b5ce7ae91bdd1e31c78cc4fd885,
  LoginScreenTest.kt 0473a06ac98a1a95ed1e0c2e79f106ced8432bc7355197a60dab12e6d2e136eb,
  PasskeyViewModelsTest.kt (committed) 311629c7526e69ebb9f7289ce48a009f8ddc4803cc2ddffc53ac3d10ee514544
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt`, `LoginScreen.kt`,
  `LoginViewModel.kt`. Excluded: `res/values/strings_auth.xml`, `res/values-de/strings_auth.xml` (resources, not
  instrumentable; covered by the English and German Compose tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP 9.4.1);
  `./gradlew testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.auth.*' createDebugUnitTestCoverageReport` in
  `android/`; selection: auth package tests only
- Run start/completion time and exit status: completed 2026-11-08 23:10:53 +0100 (report mtime); BUILD SUCCESSFUL
  in 32s, exit 0
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 1223dcf6f44e54690ebb9c520b9de2a12c0f6fd3e7081c35f395a77a3d33090c
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced `testDebugUnitTest` to execute (it showed as
  executed, not UP-TO-DATE) and the report was regenerated; no source or test file was edited after the snapshot
- Final run after latest test additions and production fixes: yes (no production fixes were made)

Values below use covered/total (percentage). Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/auth/AuthRepository.kt` | 735/770 (95.5%) | 50/50 (100%) | 26/26 (100%) | 106/109 (97.2%) |
| `android/app/src/main/java/ch/sonensei/cockpit/auth/LoginViewModel.kt` | 1166/1166 (100%) | 73/74 (98.6%) | 32/32 (100%) | 106/106 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/auth/LoginScreen.kt` | 1962/2129 (92.2%) | 63/69 (91.3%) | 28/37 (75.7%) | 178/192 (92.7%) |
| Aggregate (all 3 files, summed counters; resources excluded as above) | 3863/4065 (95.0%) | 186/193 (96.4%) | 86/95 (90.5%) | 390/407 (95.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LoginScreen.kt:276` (1 instruction, 1 branch) | the button's onClick lambda | Both the open path and the throwing (no browser) path are tested; the missed branch is most likely the Compose compiler's lambda memoization path (recomposition with an unchanged url). Not material |
| `LoginScreen.kt:78`, `:108`, `:317-334` | `LoginRoute` Hilt wiring and `LoginScreenPreview` (pre-existing, not changed by the task) | Outside task scope; not material |
| `AuthRepository.kt:158`, `:242-255` | app-scope logout collector and logout revoke error/finally lines (pre-existing) | Outside task scope; not changed by the task |
| `LoginViewModel.kt:159` (1 branch) | 429 countdown end with an error other than TOO_MANY_ATTEMPTS (pre-existing) | Outside task scope |

## Unable to test

- Opening a real browser on a device (the Robolectric tests replace `LocalUriHandler`): needs an emulator or device; low
  risk, because the button only calls `UriHandler.openUri`, which is tested.
- End-to-end: a real server answering 403 after a restore without a passphrase, then enrolling on the web page: needs the
  e2e stack and a browser; the acceptance criteria do not ask for it.

## Summary

Tests added and all validation passed. The task commits plus 5 new tests (server sub-path, no server, missing enrollment
URL, German label, no browser) pass in the focused auth run (219 tests, 0 failures) together with ktlintCheck. No
production defects found. The full Android `testDebugUnitTest`, `lintDebug` and `sh scripts/check.sh` are left to the
parent.
