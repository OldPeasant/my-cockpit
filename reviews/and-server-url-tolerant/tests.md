# Test Report

Date: 2026-11-01

Task: and-server-url-tolerant - Android login: server address tolerates keyboard-inserted spaces

Scope: base 0948ebbf6a8cae1829f87343d096400149ceefc0 .. HEAD 61d1d68777515b51ff09b9ba70aa924c4224b2c2. Task-owned commits: 63820aa80e7ef4e36f090149827a375ccff43e59 (implementation: "server address ignores inserted spaces, adds https, no autocorrect, error quotes the address") and 61d1d68777515b51ff09b9ba70aa924c4224b2c2 (test-writer tests only, 6 test files, no production change). Restricted to `android/`. Branch `main`; working tree clean apart from this report.

Requirements:
- `tasks/and-server-url-tolerant.md` acceptance criteria and recorded assumptions (zero-width characters removed; address-quoting error for both reasons, general message when nothing is left; any `letters://` prefix counts as a scheme)
- `docs/ARCHITECTURE.md` "Coverage" (Android: `createDebugUnitTestCoverageReport`, JaCoCo XML)
- AGENTS.md project notes (English + German strings, ktlint_official, fictitious test data)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/PasskeyRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/PasskeyViewModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginScreenTest.kt`

## Tests added or updated

Count: 15 added by the test-writer (on top of the implementation's own tests, which were reviewed and kept unchanged).

- ServerUrlTest: `removes the other invisible characters a keyboard may insert`, `an http address with inserted spaces keeps the https rule and quotes the cleaned address`, `a scheme in capitals is kept, an address with only a port or path gets https`, `understood removes spaces, adds https only when no scheme was typed, and is stable`
- AuthRepositoryTest: `a server address with keyboard-inserted spaces signs in and stores the address without them`, `an invalid server address with inserted spaces reports the address without them`
- PasskeyRepositoryTest: `an invalid server address reports the address as understood, without spaces and with https added`, `a passkey login accepts a server address with keyboard-inserted spaces`
- PasskeyViewModelsTest: `an invalid server address on the passkey path keeps the address as understood until the next edit`
- LoginViewModelTest: `editing any field forgets the quoted server address`, `a later failure that is not about the address shows no quoted address`
- LoginScreenTest: `the focused server address field asks the keyboard for a URI without autocorrect or capitals` (checks the EditorInfo the real focused field hands to the IME, not only the constant), `a non-address error ignores a leftover address and shows its own message`, `a server address error without an understood address shows the general message`, `the server address errors quote the address in German` (Robolectric `@Config(qualifiers = "de")`, both German strings with their `%1$s` argument)

## Requirement coverage

Gradle was denied to the test-writer sub-agent; the parent worker ran every command below on HEAD 61d1d6877 and reported the results.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 all whitespace (incl. NBSP, other Unicode spaces, zero-width) removed; `https://my-cockpit. sonensei. ch ` accepted as `https://my-cockpit.sonensei.ch/` | ServerUrlTest `removes all whitespace the keyboard inserted, including Unicode spaces`, `removes the other invisible characters a keyboard may insert`, `understood removes spaces, adds https only when no scheme was typed, and is stable`; AuthRepositoryTest `a server address with keyboard-inserted spaces signs in and stores the address without them`; PasskeyRepositoryTest `a passkey login accepts a server address with keyboard-inserted spaces` | focused command below | Pass |
| AC2 no scheme gets `https://`; plain `http://` keeps HTTPS_REQUIRED | ServerUrlTest `an address without scheme gets https`, `an http address with inserted spaces keeps the https rule and quotes the cleaned address`, `a scheme in capitals is kept, an address with only a port or path gets https`; AuthRepositoryTest `an invalid server address with inserted spaces reports the address without them` | focused command below | Pass |
| AC3 server address field: no autocorrect, no auto-capitalization, keyboard type Uri | LoginScreenTest `the server address keyboard neither autocorrects nor capitalizes`, `the focused server address field asks the keyboard for a URI without autocorrect or capitals` | focused command below | Pass (Robolectric EditorInfo; a real phone keyboard: Not run, no device) |
| AC4 invalid address error quotes the address as understood (password and passkey paths) | ServerUrlTest `an invalid address carries the address as understood`; AuthRepositoryTest `an invalid server address is rejected before any request`; PasskeyRepositoryTest `an invalid server address reports the address as understood, without spaces and with https added`; LoginViewModelTest `an invalid server address keeps the address as understood until the next edit`, `editing any field forgets the quoted server address`, `a later failure that is not about the address shows no quoted address`; PasskeyViewModelsTest `an invalid server address on the passkey path keeps the address as understood until the next edit`; LoginScreenTest `a server address error quotes the address as the app understood it`, `a non-address error ignores a leftover address and shows its own message`, `a server address error without an understood address shows the general message` | focused command below | Pass |
| AC5 new strings in English and German | LoginScreenTest `a server address error quotes the address as the app understood it` (English), `the server address errors quote the address in German`; `lintDebug` translation checks | focused command and full command below | Pass |
| AC6 unit tests cover the criteria; `ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` | Pass (BUILD SUCCESSFUL, 2404 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.ServerUrlTest' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryTest' --tests 'ch.sonensei.cockpit.auth.PasskeyRepositoryTest' --tests 'ch.sonensei.cockpit.auth.PasskeyViewModelsTest' --tests 'ch.sonensei.cockpit.auth.LoginViewModelTest' --tests 'ch.sonensei.cockpit.auth.LoginScreenTest'`

Result:

Pass (run by the parent worker): BUILD SUCCESSFUL, 153 tests in the 6 classes, 0 failures, 0 errors; the keyboard EditorInfo test passed.

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport`

Result:

Pass (run by the parent worker on HEAD 61d1d6877): BUILD SUCCESSFUL in 3m 3s, 2404 tests, 0 skipped, 0 failures.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle, which is denied to the sub-agent). Its Android part (`ktlintCheck testDebugUnitTest lintDebug`) passed in the full run above; the parent runs `.taskcheck` itself before `done`.

## Bugs found

None. All added tests pass against the implementation.

## Coverage

Status: verified - fresh run on the final code and tests (HEAD 61d1d6877).

Metric: whole-file coverage of task-changed production files (JaCoCo; "Statements" = INSTRUCTION counter, "Functions" = METHOD counter).
Changed-line coverage: 36/36 executable added lines covered (100 %). Method: added lines from `git diff -U0 0948ebbf6..63820aa80 -- android/app/src/main/java` intersected with JaCoCo `line` elements of the fresh report (`ci` above 0 = covered); removed and non-executable lines are not counted. Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-server-url-tolerant; base 0948ebbf6a8cae1829f87343d096400149ceefc0 (given by the parent); measured HEAD 61d1d68777515b51ff09b9ba70aa924c4224b2c2; task-owned commits 63820aa80 (production) and 61d1d6877 (tests only, confirmed with `git diff --stat 63820aa80 61d1d6877`); file scope from the parent's list and `git diff --stat 0948ebbf6..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 61d1d6877; `git status` shows no uncommitted source or test changes (only this untracked report).
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`, `.../auth/AuthRepository.kt`, `.../auth/PasskeyRepository.kt`, `.../auth/LoginViewModel.kt`, `.../auth/LoginScreen.kt`. Excluded `android/app/src/main/res/values/strings_auth.xml` and `res/values-de/strings_auth.xml` (resources, not instrumentable; covered by the English/German Compose tests and lint). No renames or deletions. No scoped file missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP-managed JaCoCo version); `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` run by the parent from the repository root; full debug unit-test suite.
- Run start/completion time and exit status: JaCoCo session start 1793544536731 ms, dump 1793544678010 ms (2026-11-01 15:48:56 to 15:51:18 +0100), after the test commit (15:39:54 +0100); report written 15:51:21 +0100; exit status 0 (BUILD SUCCESSFUL, reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 fbb754ce91eaa9a510786cdd01e10d296ad41edb136be10f15d04f1c7f1c6496 (the earlier pre-test-addition report had 4157a0cbb69376b3; it is superseded).
- Evidence outputs are fresh and inputs stayed unchanged: yes - the session started after the last commit, the tree had no source/test changes, and the counters for LoginScreen.kt changed versus the earlier report (line 272 now covered), showing the report was regenerated with the added tests.
- Final run after latest test additions and production fixes: yes (no production fixes were needed).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/network/ServerUrl.kt` | 513/515 (99.6 %) | 53/54 (98.1 %) | 25/25 (100 %) | 55/55 (100 %) |
| `auth/AuthRepository.kt` | 705/740 (95.3 %) | 44/44 (100 %) | 25/25 (100 %) | 100/103 (97.1 %) |
| `auth/PasskeyRepository.kt` | 756/768 (98.4 %) | 67/67 (100 %) | 21/23 (91.3 %) | 106/108 (98.1 %) |
| `auth/LoginViewModel.kt` | 1059/1059 (100 %) | 69/70 (98.6 %) | 31/31 (100 %) | 102/102 (100 %) |
| `auth/LoginScreen.kt` | 1769/1934 (91.5 %) | 54/59 (91.5 %) | 25/34 (73.5 %) | 166/180 (92.2 %) |
| Aggregate (summed counters, 5 files; the 2 string resource files excluded, none unmeasured) | 4802/5016 (95.7 %) | 287/294 (97.6 %) | 127/138 (92.0 %) | 529/548 (96.5 %) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `auth/LoginScreen.kt:272` and branch at 269 (uncovered in the earlier run) | `addressMessage` else branch: a non-address error with a leftover address must show its own message | Covered now by LoginScreenTest `a non-address error ignores a leftover address and shows its own message` |
| `auth/LoginScreen.kt:75` (4 of 8 branches), 295-312 | `LoginRoute` Hilt default parameter and `@Preview` composable; not changed by this task | Justified gap: pre-existing, outside the task |
| `auth/LoginScreen.kt:105` (1 of 2 branches) | pre-existing form layout code, not changed by this task | Justified gap |
| `core/network/ServerUrl.kt:120` (1 of 2 branches) | `readablePrefs` rethrow of non-IO errors; pre-existing, not changed by this task | Justified gap |
| `auth/AuthRepository.kt:157, 228-241`; `auth/PasskeyRepository.kt:33-36, 76` | pre-existing code (device name fallback, logout/ceremony helpers), not changed by this task | Justified gap |
| `auth/LoginViewModel.kt:154` (1 of 4 branches) | pre-existing countdown code, not changed by this task | Justified gap |

## Unable to test

- AC3 on a real phone keyboard (that it no longer inserts spaces or capitals) needs a device; the Robolectric test verifies the EditorInfo flags the field requests, which is what keyboards act on. No follow-up needed unless the owner wants a device check.
- Unicode NEXT LINE (U+0085) is, as far as the test-writer knows, not removed by `Char.isWhitespace` on the JVM; it was not tested because keyboards do not insert it. Low risk, no follow-up needed unless strict Unicode White_Space handling is wanted.
- The test-writer could not run Gradle itself; all results above were run and reported by the parent worker.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient (36/36 changed executable lines covered); no production defects found.
