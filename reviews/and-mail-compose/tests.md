# Test Report

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: `git diff e6a9288b5f2fb4c5cafea94705d0b44da12906a7..58171348df62d8b9d3fdd06648069d373a2637eb -- android` (task commits 5a09f26f0, 9bcb7b2fa, 6822849a4, 905b442ed, b89550fef, 58171348d, as given by the parent worker). Focus on new gaps in the latest fix commit 58171348d (second review CR-1..CR-3 in `ComposeSession.kt`, the `leaving` flag in `MailComposeViewModel.kt`, the discard menu item in `MailComposeScreen.kt`). Branch `main`, HEAD 58171348df62d8b9d3fdd06648069d373a2637eb, no uncommitted production changes.

Requirements:
- tasks/and-mail-compose.md acceptance criteria 1 and 2 and the recorded assumptions
- reviews/and-mail-compose/review.md (second review CR-1..CR-3) and fixes.md, as the intent of the latest fix
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeOverwriteLeaveEdgeCasesTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeStatesScreenTest.kt` (one test added)

## Tests added or updated

Count: 8

- `an overwrite keeps as many equal files as were kept, not every equal file of the newest version` (CR-1, "one is kept per kept one")
- `an overwrite does not keep an inline part or a file of another size that shares the kept name` (CR-1, match by name + size + inline; fails against the pre-fix part-id matching)
- `a second back while leaving saves does not save again` (CR-2, `if (s.leaving) return`)
- `a file picked while leaving saves is not uploaded` (CR-2, attach is locked by `editable`)
- `leaving an untouched forward locks the screen until its draft is discarded` (CR-2 on the discard-on-leave path)
- `a retry while a new mail's accounts load does not load them twice` (CR-3 for `ComposeMode.New`)
- `a retry after the second start failed too starts it again` (CR-3: the lock is released after a finished load)
- `while back saves the draft, the form, send, save and discard are locked` (Robolectric/Compose screen test for the `leaving` state, incl. the changed discard menu item)

Existing tests already covered the main CR-1..CR-3 paths (`MailComposeSecondReviewFixesTest`); the new ones cover the edge cases of those fixes only.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 compose/reply/forward, formatting, attachments, drafts | existing `MailComposeViewModelTest`, `ComposeModelsTest`, `ComposeModelsEdgeCasesTest`, `MailComposeRepositoryTest`, `DeviceFilesTest`, `MailComposeInAppTest`, `MailComposeWiringTest`; new overwrite attachment tests | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` | Pass (JVM/Robolectric only; no device run) |
| AC2 draft save and send states | existing `MailComposeSaveSendStatesTest`, `MailComposeViewModelConcurrencyTest`, `MailComposeReviewFixesTest`, `MailComposeSecondReviewFixesTest`; new leave/retry tests | same | Pass |
| AC2 Robolectric/Compose UI tests for screens | existing `MailComposeScreenTest`, `MailComposeStatesScreenTest`, `MailComposeRouteTest`, `MailComposeInAppTest`; new `while back saves the draft, ...` | same | Pass |
| Second review CR-1 (kept attachments by file on overwrite) | 2 tests in `MailComposeSecondReviewFixesTest` + 2 new | same | Pass |
| Second review CR-2 (leaving locks edits/send/discard) | 2 in `MailComposeSecondReviewFixesTest` + 3 new VM tests + 1 new screen test | same | Pass |
| Second review CR-3 (retry no-op during load) | 1 in `MailComposeSecondReviewFixesTest` + 2 new | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'`

Result:

Pass. BUILD SUCCESSFUL; 15 test classes, 145 tests, 0 failures, 0 errors, 0 skipped (JUnit XML in `android/app/build/test-results/testDebugUnitTest/`, written 10:07:32). The first run failed on two ktlint violations in the new test file; fixed in the test, then rerun.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck createDebugUnitTestCoverageReport` (runs the whole Android unit suite)

Result:

Pass. BUILD SUCCESSFUL in 1m 27s; 150 test classes, 1502 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` -> "== all checks passed" (server, web, android). The web build prints its existing initial-bundle budget warning (not related to this task).

## Bugs found

None.

## Coverage

Status: verified (whole-file, JaCoCo, fresh run on HEAD 58171348d plus the two test files above).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-compose; base e6a9288b5f2fb4c5cafea94705d0b44da12906a7; HEAD 58171348df62d8b9d3fdd06648069d373a2637eb; commits and scope from the parent worker, file list from `git diff --name-only e6a9288b5..HEAD -- android/app/src/main`, consistent with the task's touches and its recorded MailScreen/MailReadScreen assumption.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 58171348d with no production changes in the working tree; uncommitted tests `MailComposeOverwriteLeaveEdgeCasesTest.kt` sha256 7c8b36f700c27214d77767b79b580b4b93f0986ee24910381b867ef315916315 and `MailComposeStatesScreenTest.kt` sha256 76b7b2e646d9622eba3a8c6981cf150083ad2f45330f2a8ff1fc6d8827ea65cc (last modified 10:07:10, before the run). `reviews/and-mail-compose/review.md` was modified by another helper during this work; it is not a test input.
- Exact included files: `mail/compose/{ComposeModels,ComposeSession,DeviceFiles,MailComposeApi,MailComposeRepository,MailComposeScreen,MailComposeViewModel}.kt`, `mail/list/MailScreen.kt`, `mail/read/MailReadScreen.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `android/app/src/main/res/values/strings_mail_compose.xml` (resources, not instrumented). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1, Kotlin 2.4.10, JDK 21.0.12.1); `./gradlew ktlintCheck createDebugUnitTestCoverageReport` in `android/`; the full debug unit test suite.
- Run start/completion time and exit status: started about 10:07:50, report written 2026-10-22 10:09:20 +0200; exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 41d78f4d54fe0b546adfd7db440c42fdefcdafd67ee4422bab0b6233ec2b61c8.
- Evidence outputs are fresh and inputs stayed unchanged: the test task re-ran (no up-to-date skip), the report timestamp is from this run, and the source/test hashes and `git status` were the same after the run.
- Final run after latest test additions and production fixes: yes (covers 58171348d, the CR-1..CR-3 fixes, and all tests added here).

Values below use covered/total (percentage) from JaCoCo XML sourcefile counters (statements = INSTRUCTION, functions = METHOD).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/compose/ComposeModels.kt` | 1166/1166 (100.0%) | 113/115 (98.3%) | 61/61 (100.0%) | 151/151 (100.0%) |
| `mail/compose/ComposeSession.kt` | 2730/2754 (99.1%) | 230/249 (92.4%) | 63/63 (100.0%) | 267/267 (100.0%) |
| `mail/compose/DeviceFiles.kt` | 180/185 (97.3%) | 18/18 (100.0%) | 6/6 (100.0%) | 23/23 (100.0%) |
| `mail/compose/MailComposeApi.kt` | 220/449 (49.0%) | N/A (0 branches) | 20/46 (43.5%) | 37/50 (74.0%) |
| `mail/compose/MailComposeRepository.kt` | 922/948 (97.3%) | 67/72 (93.1%) | 27/28 (96.4%) | 99/99 (100.0%) |
| `mail/compose/MailComposeScreen.kt` | 4484/4586 (97.8%) | 159/179 (88.8%) | 84/102 (82.4%) | 362/363 (99.7%) |
| `mail/compose/MailComposeViewModel.kt` | 1068/1084 (98.5%) | 38/52 (73.1%) | 63/63 (100.0%) | 93/93 (100.0%) |
| `mail/list/MailScreen.kt` | 3461/3557 (97.3%) | 116/135 (85.9%) | 68/80 (85.0%) | 294/299 (98.3%) |
| `mail/read/MailReadScreen.kt` | 2446/2537 (96.4%) | 111/128 (86.7%) | 40/48 (83.3%) | 184/188 (97.9%) |

Aggregate, `mail.compose` package (7 files above, summed counters): statements 10770/11172 (96.4%), branches 625/685 (91.2%), functions 324/369 (87.8%), lines 1032/1046 (98.7%).
Aggregate, all 9 task-changed source files: statements 16677/17266 (96.6%), branches 852/948 (89.9%), functions 432/497 (86.9%), lines 1510/1533 (98.5%). Excluded: `strings_mail_compose.xml` (resource). No scoped file is missing from the report.

`MailScreen.kt` and `MailReadScreen.kt` are whole-file numbers of files that existed before the task; most of their code is not this task's (the task only added the compose button/route and reply/forward buttons).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailComposeApi.kt:88,95,124-150` | Hilt `@Provides` glue and kotlinx-serialization generated code of the DTOs (default-argument constructors, `copy`, `component` functions) | Not material: the DTO mapping is covered through `MailComposeRepositoryTest` against MockWebServer; the Hilt module is wiring only |
| `ComposeSession.kt:54,124,130,208,347,382-383` (partial branches) | Short-circuit combinations: `loading` never null when `retry` runs after `load`; `wanted.remove` cannot return false after a match; leave of an untouched non-New mode always has a `stored` draft in EDITING | Not reachable in practice; the reachable outcomes of CR-1..CR-3 are tested (new tests above) |
| `ComposeSession.kt:65,84,106,247,259,295,320,341,400,415` (partial branches) | Defensive checks (ended after a load, empty file list, `stored` missing at send, `content()` invalid during a send conflict, 404 vs other failure on a refetch while discarding) | Main outcomes are tested; remaining combinations are defensive and left uncovered |
| `MailComposeViewModel.kt:129-198` (partial branches) | `current?.method()` null-session branches of the delegating functions (called before `start`) | Not material: the route always calls `start` first |
| `MailComposeScreen.kt:400` and partial branches at 74,92,386-402,462-555 | Account picker with more than one account while expanded, and null-safe UI branches | Account choice is covered in VM tests; the picker dropdown rendering is a minor UI gap, no test added |
| `MailComposeRepository.kt:136-138,155,170` (partial branches) | Fallback mappings for unexpected status codes | Main mappings are tested in `MailComposeRepositoryTest`; left uncovered |

## Unable to test

- Running on a real device or emulator (real file picker, real back gesture): not available in this environment; Robolectric covers the screens. Criterion 2 names only the JVM unit test command, which was run.
- A real multi-client conflict against the server: simulated with mocked repository results (412, renumbered part ids); no follow-up needed for this task's criteria.

## Summary

Tests added and all validation passed. No production defects found; task-scoped coverage is measured above, and the remaining gaps are defensive or generated code.
