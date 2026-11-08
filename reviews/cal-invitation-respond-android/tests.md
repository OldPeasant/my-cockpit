# Test Report

Date: 2026-11-08

Task: cal-invitation-respond-android (answer invitations in the Android event editor). This is a re-run on the final code after the CR-1/CR-2 fixes.

Scope: immutable base 0cec3b4bab909270b22a0e32e7965555e46f97b5. The parent supplied the task commits fe383eefd (implementation), 3acbe299e (review), cadbdb450 (tests) and 562f6bc93 (CR-1/CR-2 fixes). During this run the parent rebased them to 37e5b73a2, 04e94f281, 8c8937007 and 03bc80ec9, plus 923123943 (re-review report). `git diff --stat f33de8d22..923123943` lists only `reviews/` and `tasks/` files, so the code is identical. Measured HEAD: 923123943b36afd62383de37f59aea6f1915018f, plus the uncommitted test changes listed below.
Changed production files (`git diff --name-only 0cec3b4b..HEAD -- android/app/src/main`): `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{CalendarWithEditor,EventEditRepository,EventEditorScreen,EventEditorSections,EventEditorViewModel,EventExtrasRepository}.kt`, `android/app/src/main/res/values{,-de}/strings_calendar_edit.xml`.

Requirements:
- `tasks/cal-invitation-respond-android.md` (3 acceptance criteria plus the assumption on same rules as the web subtask)
- `reviews/cal-invitation-respond-android/review.md` and `fixes.md` (CR-1, CR-2 fixed behavior)
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelRespondTest.kt` (uncommitted, sha256 1fdd82ddb81eff3d0310f417e82f7bc13bbe316a34fcd789a4db87a6fb7d9650)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorExtrasScreenTest.kt` (uncommitted, sha256 e58b4a48b94d0be785c266b755ba73538e0e756ef79767a0a78100d461446bba)

## Tests added or updated

Count: 2 added in this run. Together with the 22 tests from the earlier run and the 2 from the fixer (`a retry after the sources failed to load ...` and the updated `a refused answer is explained`), the task now has 26 tests.

- `a reload of a new event neither fetches the sources again nor drops the form` (ViewModel). It guards the new `if (occurrence == null) return` in `reload()` (CR-2 fix): on a new event, reload must not fetch the sources again, load an event, or drop the typed form.
- `a delegated or unknown own answer counts as not answered and selects no button` (screen). It covers the "You have not answered yet." branch for DELEGATED and for no matching own address (ownStatus null). The buttons stay enabled.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Accept / Maybe / Decline shown only for existing events with guests in Google/Microsoft calendars | `an existing event with guests in a Google or Microsoft calendar can be answered...`, `a local event, one without guests or a new one can't be answered`, `while the invitation calendars are unknown or failed to load nothing can be answered`, `no answers are offered for a local calendar, a new event or one without guests` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` | Pass |
| AC1: current status shown | `the current answer is named and selected`, `an unanswered invitation offers accept, maybe and decline...`, `a delegated or unknown own answer counts as not answered...`, `without a matching own address there is no current answer...` | same | Pass |
| AC1: sending updates the status (version, guests, unsaved edits kept) | `an answer is sent with the event version, then the guests and version are updated and unsaved changes stay`, `only accept, maybe and decline are sent`, `while an answer is sent nothing else can be answered or saved`, EventEditRepositoryTest respond tests | same | Pass |
| AC1: error mapping (offline, 412 conflict, 404 gone, other refusals) | `no connection shows offline...`, `a stale version is a conflict...`, `a missing event is gone`, `other refusals, like not invited or organizer, report that the answer failed...`, `the answer to a closed editor never shows in the next event`, `a reload while an answer is sent drops the answer's result` | same | Pass |
| CR-1: neutral refusal text with a Reload action | `a refused answer is explained` (checks the new English text, the Reload button, and that tapping it calls `reload`) | same | Pass |
| CR-2: Retry/Reload fetches the invitation calendars and own addresses again | `a retry after the sources failed to load fetches them again, so the invitation can be answered`, `a reload of a new event neither fetches the sources again nor drops the form` | same | Pass |
| AC2: strings in English and German | `CalendarEditStringsTest`, plus lint `MissingTranslation` in `lintDebug` | same | Pass |
| AC3: unit tests cover the new behavior; `sh scripts/check.sh` passes | the whole Android unit suite (4872 tests). `scripts/check.sh` was not run by me (the parent runs `.taskcheck`) | same | Pass (Android part); check.sh: Not run, left to the parent |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorViewModelRespondTest' --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorExtrasScreenTest' -q`

Result:

Pass (after one ktlint `chain-method-continuation` fix in the new test)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q`

Result:

Pass on the final run (exit 0, 4872 tests, 0 failures, 0 errors; finished 2026-11-08 23:49 +0100).
Two earlier runs on the same snapshot failed on one test outside the task: `ch.sonensei.cockpit.auth.AppStartRoutingTest > logging out from settings clears the cached files` (`ComposeTimeoutException` at `AppStartRoutingTest.kt:167`, a `waitUntil(5000 ms)`). It failed once when run alone, then passed when run alone again, and passed in the first full run (23:27) and the last one. The task did not change any auth code, so this is a flaky timing-based test, not a task defect. It may hit `.taskcheck` too, and a follow-up could make it stable.

## .taskcheck

Result:

Not run (left to the parent worker). The Android part of it (ktlintCheck, testDebugUnitTest, lintDebug) passed above.

## Bugs found

None.

## Coverage

Status: verified (fresh final run, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. JaCoCo XML `<line>` entries with `ci>0`, intersected with the added lines of `git diff -U0 0cec3b4b..HEAD -- android/app/src/main/java` (patch sha256 618c2b1b2ca9e95ff9faa6633711a76f5bc674f21f5c9aa0ed39da86d73a9ac4). Lines JaCoCo doesn't count as executable are left out. Result: 112/112 changed executable lines covered (100%). Branches on changed lines: 95/103 (92.2%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond-android; base 0cec3b4bab909270b22a0e32e7965555e46f97b5; HEAD 923123943b36afd62383de37f59aea6f1915018f; parent-supplied commit list (rebased, code identical as described in Scope)
- Source/test snapshot identifier (including uncommitted inputs): HEAD 923123943 plus the two test files with the sha256 above. Checked before (23:34) and after (23:51) the final run, unchanged.
- Exact included files; renamed/deleted/excluded files and reasons: the 6 Kotlin files above are included. The 2 string XML files are excluded (resources, not instrumented; covered by lint and CalendarEditStringsTest). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport -q` in `android/`; full debug unit suite
- Run start/completion time and exit status: about 23:45 to 23:49:51 +0100, exit 0
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 23:49:51, sha256 d2bdfab3006080082ea0b1d51a1268b6f2f9097e9dc1ac881340abbdcc0e9cc7)
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report was written by this run, and the test-result timestamps for the new tests are 22:46Z, after the test edits.
- Final run after latest test additions and production fixes: yes

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `CalendarWithEditor.kt` | 1239/1265 (97.9%) | 21/24 (87.5%) | 10/11 (90.9%) | 57/57 (100%) |
| `EventEditRepository.kt` | 841/857 (98.1%) | 51/56 (91.1%) | 42/43 (97.7%) | 111/111 (100%) |
| `EventEditorScreen.kt` | 4545/4731 (96.1%) | 91/97 (93.8%) | 122/155 (78.7%) | 311/313 (99.4%) |
| `EventEditorSections.kt` | 5865/5933 (98.9%) | 204/218 (93.6%) | 112/118 (94.9%) | 437/438 (99.8%) |
| `EventEditorViewModel.kt` | 4797/4919 (97.5%) | 402/464 (86.6%) | 100/101 (99.0%) | 462/462 (100%) |
| `EventExtrasRepository.kt` | 890/900 (98.9%) | 34/36 (94.4%) | 38/40 (95.0%) | 80/80 (100%) |
| **Aggregate (summed counters, 6 files; string XML excluded)** | 18177/18605 (97.7%) | 803/895 (89.7%) | 424/468 (90.6%) | 1458/1461 (99.8%) |

All paths are under `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/`. The file-level numbers include pre-existing code in those files.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorViewModel.kt:261` (reload on a new event) | CR-2 guard | Covered now by `a reload of a new event neither fetches the sources again nor drops the form` |
| `EventEditorSections.kt:293` (DELEGATED / null own status) | AC1 current status | Covered now by `a delegated or unknown own answer counts as not answered...` |
| `EventEditorViewModel.kt:152` (`form != null` false while not creating) | defensive, not reachable with an open edited event that has a form | Justified gap |
| `EventEditorViewModel.kt:160,161,163` (`canRespond` false because of loading / saving / an open invite dialog) | the answer is blocked during other work | Partly covered: the responding and conflict blocks are tested. Loading/saving/invite short circuits are plain boolean conjuncts, so this is a low-risk gap and no test was added |
| `EventEditorViewModel.kt:643` (`respond` with no occurrence) | defensive: `canRespond` is already false for new events | Justified gap |
| `EventEditorViewModel.kt:652,656` (`initial?` / `it.form?` null after an answer) | defensive null-safety after closing; the closed-editor case is covered by `the answer to a closed editor never shows in the next event` | Justified gap |
| `EventEditorSections.kt:304` (1 of 2 branches of `answer == current`) | both button kinds are drawn in `the current answer is named and selected` | Likely a Compose bytecode branch, not a missing behavior; no test added |

## Unable to test

- `sh scripts/check.sh` / `.taskcheck` was not run by me. The parent worker runs it. Its Android part passed above.
- A real server round trip (`POST /api/v1/calendar/events/{id}/respond` against the server) is outside these unit tests. The request shape is covered by EventEditRepositoryTest with a mock HTTP engine. The criteria name only unit tests, so no follow-up is needed.
- Flaky unrelated test `auth.AppStartRoutingTest > logging out from settings clears the cached files`: a follow-up outside this task could make it stable.

## Summary

Tests added and all validation passed. The tests cover the fixed behavior (CR-1 text and Reload action; CR-2 sources refetched on retry, and the new create-mode guard) and all three acceptance criteria. Task-scoped changed-line coverage is 112/112. No production defects found.
