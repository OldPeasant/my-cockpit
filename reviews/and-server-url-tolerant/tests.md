# Test Report

Date: 2026-11-01

Task: and-server-url-tolerant - Android login: server address tolerates keyboard-inserted spaces

Scope: base ca888fe28b3da21ae90f30723231a23efca13471 (`git merge-base HEAD origin/main`) .. HEAD 8d67a120943fe607e57fcc81d84cd140ba3bd672. Task-owned commits (given by the parent): 163a6de12 (implementation), 1c3aafcbe (test-writer tests, no production change), 8d67a1209 (fixer: CR-1 mistyped scheme is MALFORMED in `ServerUrl.kt`, CR-2 escaped test literals). These are the rebased versions of the commits covered by the earlier report (63820aa80, 61d1d6877), which is superseded by this one. Restricted to `android/`. Branch `main`. Uncommitted at the time of writing: the two test files listed below (this run) and `reviews/and-server-url-tolerant/review.md` (not touched by the test-writer; presumably the code-reviewer's rerun).

Requirements:
- `tasks/and-server-url-tolerant.md` acceptance criteria and recorded assumptions (zero-width characters removed; address-quoting error for both reasons, general message when nothing is left; any `letters://` prefix counts as a scheme)
- `reviews/and-server-url-tolerant/fixes.md` (CR-1: a scheme the app added that leaves host `http`/`https` is MALFORMED)
- `docs/ARCHITECTURE.md` "Coverage" (Android: `createDebugUnitTestCoverageReport`, JaCoCo XML)
- AGENTS.md project notes (English + German strings, fictitious test data)

## Test files changed

In this run (uncommitted, must be run by the parent):
- `android/app/src/test/java/ch/sonensei/cockpit/core/network/ServerUrlTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/auth/AuthRepositoryTest.kt`

Already committed in the task (reviewed, unchanged in this run): `ServerUrlTest.kt`, `AuthRepositoryTest.kt`, `PasskeyRepositoryTest.kt`, `PasskeyViewModelsTest.kt`, `LoginViewModelTest.kt`, `LoginScreenTest.kt` under `android/app/src/test/java/ch/sonensei/cockpit/`.

## Tests added or updated

Count: 2 added in this run (on top of the 15 from the earlier test-writer run and the fixer's CR-1 test, all kept unchanged).

- ServerUrlTest `a scheme typed only partly is malformed and quotes the address as understood`: `https`, `http`, `https:`, `https:/` and ` Https : /` give MALFORMED with the quoted address (`https://https`, ..., `https://Https:/`). Before CR-1 these were a "valid" server named `https`/`http`; the fixer's test covered `https//`, `http//`, `http:/x` but not an unfinished scheme with nothing after it.
- AuthRepositoryTest `a mistyped scheme is reported as an invalid address before any request`: the user-visible path of CR-1. `login("https// cockpit. example. org", ...)` returns `InvalidServerUrl(MALFORMED, "https://https//cockpit.example.org")`, sends no request and stores no server URL (before the fix the login went out and failed with the general network error).

Reviewed for the final code without changes: the fixer's `a mistyped scheme is malformed, not a server named http or https` (typo with and without inserted spaces, upper case, and the two must-stay-valid cases `https://https` and `httpserver.example.org`), the `\uXXXX`-escaped invisible-character tests (same code points as before, CR-2), and all criteria tests from the earlier run.

## Requirement coverage

Gradle is denied to the test-writer. Results below are from the parent's run on HEAD 8d67a1209, which does not yet include the 2 tests added in this run; those are marked "Not run" until the parent runs them.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 all whitespace (incl. NBSP, other Unicode spaces, zero-width) removed; `https://my-cockpit. sonensei. ch ` accepted as `https://my-cockpit.sonensei.ch/` | ServerUrlTest `removes all whitespace the keyboard inserted, including Unicode spaces`, `removes the other invisible characters a keyboard may insert`, `understood removes spaces, adds https only when no scheme was typed, and is stable`; AuthRepositoryTest `a server address with keyboard-inserted spaces signs in and stores the address without them`; PasskeyRepositoryTest `a passkey login accepts a server address with keyboard-inserted spaces` | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q --no-daemon` | Pass |
| AC2 no scheme gets `https://`; plain `http://` keeps HTTPS_REQUIRED | ServerUrlTest `an address without scheme gets https`, `an http address with inserted spaces keeps the https rule and quotes the cleaned address`, `a scheme in capitals is kept, an address with only a port or path gets https`; AuthRepositoryTest `an invalid server address with inserted spaces reports the address without them` | same | Pass |
| AC2 + CR-1: a mistyped or unfinished scheme does not become a server named `http`/`https` | ServerUrlTest `a mistyped scheme is malformed, not a server named http or https` | same | Pass |
| AC2 + CR-1 (added in this run) | ServerUrlTest `a scheme typed only partly is malformed and quotes the address as understood`; AuthRepositoryTest `a mistyped scheme is reported as an invalid address before any request` | not yet run | Not run: added after the parent's run; Gradle is denied to the test-writer |
| AC3 server address field: no autocorrect, no auto-capitalization, keyboard type Uri | LoginScreenTest `the server address keyboard neither autocorrects nor capitalizes`, `the focused server address field asks the keyboard for a URI without autocorrect or capitals` | same | Pass (Robolectric EditorInfo; a real phone keyboard: Not run, no device) |
| AC4 invalid address error quotes the address as understood (password and passkey paths) | ServerUrlTest `an invalid address carries the address as understood`; AuthRepositoryTest `an invalid server address is rejected before any request`; PasskeyRepositoryTest `an invalid server address reports the address as understood, without spaces and with https added`; LoginViewModelTest `an invalid server address keeps the address as understood until the next edit`, `editing any field forgets the quoted server address`, `a later failure that is not about the address shows no quoted address`; PasskeyViewModelsTest `an invalid server address on the passkey path keeps the address as understood until the next edit`; LoginScreenTest `a server address error quotes the address as the app understood it`, `a non-address error ignores a leftover address and shows its own message`, `a server address error without an understood address shows the general message` | same | Pass |
| AC5 new strings in English and German | LoginScreenTest `a server address error quotes the address as the app understood it` (English), `the server address errors quote the address in German`; `lintDebug` translation checks | same | Pass |
| AC6 unit tests cover the criteria; `ktlintCheck testDebugUnitTest lintDebug` passes | all of the above | same | Pass on HEAD 8d67a1209 (exit 0, 2405 tests, 0 failures, 0 skipped); rerun needed for the 2 tests added in this run |

## Focused test execution

Command (for the parent to run on the final tree):

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.network.ServerUrlTest' --tests 'ch.sonensei.cockpit.auth.AuthRepositoryTest'`

Result:

Not run by the test-writer (Gradle denied). The parent must run it; the 2 new tests are expected to pass against HEAD 8d67a1209 (expected values derived from `ServerUrlValidator.understood` and the CR-1 host check, not from a run).

## Full test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q --no-daemon`

Result:

Pass, run by the parent on HEAD 8d67a1209 without the 2 tests of this run: exit 0, 2405 tests, 0 failures, 0 skipped.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle, denied to the sub-agent). The parent runs it before `done`.

## Bugs found

None. CR-1 is fixed in HEAD 8d67a1209 as described in `fixes.md`; reading `ServerUrlValidator.validate` shows the added tests should pass.

## Coverage

Status: verified for HEAD 8d67a1209 with the committed tests; the 2 tests added in this run exercise lines that are already covered, so they do not change which lines are covered, but they are not part of the measured run.

Metric: whole-file coverage of task-changed production files (JaCoCo; "Statements" = INSTRUCTION counter, "Functions" = METHOD counter).
Changed-line coverage: 41/41 executable added/modified lines covered (100 %); branches on those lines 41/41. Method: added lines from `git diff -U0 ca888fe28..8d67a1209 -- {file}` intersected with the JaCoCo `line` elements of the report (`ci` above 0 = covered; `cb`/`mb` for branches); removed and non-executable lines not counted. Per file: ServerUrl.kt 14/14 lines, 22/22 branches; LoginViewModel.kt 10/10, 0/0; PasskeyRepository.kt 1/1, 2/2; LoginScreen.kt 14/14, 15/15; AuthRepository.kt 2/2, 2/2.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-server-url-tolerant; base ca888fe28b3da21ae90f30723231a23efca13471 (`git merge-base HEAD origin/main`, given by the parent); measured HEAD 8d67a120943fe607e57fcc81d84cd140ba3bd672; task-owned commits 163a6de12, 1c3aafcbe, 8d67a1209 (parent's list, matching `git log ca888fe28..HEAD`); file scope from `git diff --stat ca888fe28..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 8d67a1209 with no uncommitted source or test changes at measurement time (parent's run). Added afterwards by this run, not measured: `ServerUrlTest.kt` sha256 1162e4eb2c459fbb66b32ed3d1800b46db4e6e5b12baf25da6162c2cde1ae6e8, `AuthRepositoryTest.kt` sha256 7cb6b76fbee0c2c3eeb892111033ca5f801609da5d5974817f7b60cfd2208395.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/core/network/ServerUrl.kt`, `.../auth/AuthRepository.kt`, `.../auth/PasskeyRepository.kt`, `.../auth/LoginViewModel.kt`, `.../auth/LoginScreen.kt`. Excluded `android/app/src/main/res/values/strings_auth.xml` and `res/values-de/strings_auth.xml` (resources, not instrumentable; covered by the English/German Compose tests and lint). No renames or deletions. No scoped file missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP-managed version); `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q --no-daemon`, run by the parent from the repository root; full debug unit-test suite.
- Run start/completion time and exit status: JaCoCo session dell26-4aa5678a, start 1793545206165 ms, dump 1793545392774 ms (2026-11-01 about 16:00:06 to 16:03:13 +0100), after the HEAD commit (15:56:55 +0100); exit 0 (reported by the parent).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (written 16:03:16 +0100), sha256 7ea0b49a9baf8bc15169766fd75ea82417621f179458fc5b5c0d694c1f6998b5. The earlier report (fbb754ce...) is superseded.
- Evidence outputs are fresh and inputs stayed unchanged: yes for HEAD 8d67a1209. The session started after the last commit, and the ServerUrl.kt counters differ from the earlier report (instructions 571/573 instead of 513/515, methods 27 instead of 25, the new `compact`/`isSchemeAdded` helpers), so the report was regenerated from the fixed code.
- Final run after latest test additions and production fixes: yes for the production fix (CR-1); no for the 2 tests added in this run (Gradle denied to the test-writer; the parent must rerun them, see Summary).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/network/ServerUrl.kt` | 571/573 (99.7 %) | 63/64 (98.4 %) | 27/27 (100 %) | 60/60 (100 %) |
| `auth/AuthRepository.kt` | 705/740 (95.3 %) | 44/44 (100 %) | 25/25 (100 %) | 100/103 (97.1 %) |
| `auth/PasskeyRepository.kt` | 756/768 (98.4 %) | 67/67 (100 %) | 21/23 (91.3 %) | 106/108 (98.1 %) |
| `auth/LoginViewModel.kt` | 1059/1059 (100 %) | 69/70 (98.6 %) | 31/31 (100 %) | 102/102 (100 %) |
| `auth/LoginScreen.kt` | 1769/1934 (91.5 %) | 54/59 (91.5 %) | 25/34 (73.5 %) | 166/180 (92.2 %) |
| Aggregate (summed counters, 5 files; the 2 string resource files excluded, none unmeasured) | 4860/5074 (95.8 %) | 297/304 (97.7 %) | 129/140 (92.1 %) | 534/553 (96.6 %) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `core/network/ServerUrl.kt` CR-1 lines (`SCHEME_NAMES`, `takeUnless` host check, `compact`, `isSchemeAdded`) | mistyped scheme must be MALFORMED | Fully covered (22/22 branches on changed lines) by the fixer's test; this run adds the unfinished-scheme and login-path cases |
| `core/network/ServerUrl.kt:133` (1 of 2 branches) | `readablePrefs` rethrow of non-IO errors; pre-existing, not changed by this task | Justified gap |
| `auth/LoginScreen.kt:75` (4 of 8 branches), 296-312 | `LoginRoute` Hilt default parameter and `@Preview` composable; not changed by this task | Justified gap: pre-existing, outside the task |
| `auth/LoginScreen.kt:105` (1 of 2 branches) | pre-existing form layout code, not changed by this task | Justified gap |
| `auth/AuthRepository.kt:157, 228-229`; `auth/PasskeyRepository.kt:33-34` | pre-existing code (device name fallback, logout/ceremony helpers), not changed by this task | Justified gap |
| `auth/LoginViewModel.kt:154` (1 of 4 branches) | pre-existing countdown code, not changed by this task | Justified gap |

## Unable to test

- AC3 on a real phone keyboard (that it no longer inserts spaces or capitals) needs a device; the Robolectric test verifies the EditorInfo flags the field requests, which is what keyboards act on. No follow-up needed unless the owner wants a device check.
- Unicode NEXT LINE (U+0085) is not removed (`Char.isWhitespace` does not treat it as whitespace, and the fixer reverted an unrequested change adding it). Keyboards do not insert it; low risk. Whether to strip it is a parent decision (see `fixes.md`), not tested here.
- The test-writer could not run Gradle; all results above were run and reported by the parent worker, and the 2 tests of this run still need a run.

## Summary

Tests added and all validation passed for HEAD 8d67a1209 as run by the parent; no production defects found. Task-scoped coverage is sufficient (41/41 changed executable lines and 41/41 branches on them covered). Two tests were added in this run for CR-1 (`ServerUrlTest` `a scheme typed only partly is malformed and quotes the address as understood`, `AuthRepositoryTest` `a mistyped scheme is reported as an invalid address before any request`); the parent must run them before relying on this report.
