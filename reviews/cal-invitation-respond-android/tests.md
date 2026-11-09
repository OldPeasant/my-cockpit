# Test Report

Date: 2026-11-09

Task: cal-invitation-respond-android (Respond to invitations in the Android event editor). Re-validation of the final code at bf4361ac5. That commit merged the task's work into the latest main: main had added `EventEditorCallbacks.of(editor)`, and the merge added `respond = editor::respond` there. `gtd/today/TodayWithEditor.kt` now builds its callbacks with `EventEditorCallbacks.of(editor)`, so the Today editor can answer invitations too.

Scope: immutable base 924770e973ebb0f112aea4b401645a50693c231f (the claim commit, given by the parent). Final code: bf4361ac5fc66c7a1fa96e098ca0ad9b3a3d0041, the merge with parents 924770e97 and da117c30d (the task's wip line). The parent established ownership: everything in `git diff 924770e97 bf4361ac5` belongs to the task. While I worked, HEAD moved to 700f0237a7a40677edec9b0a814ec1dceeaa3ac5 (a heartbeat commit and a merge of main). `git diff --stat bf4361ac5 700f0237a` changes only `tasks/cal-invitation-respond-android.md`, so the measured Android code is the same as at bf4361ac5.
Changed production files (`git diff --name-only 924770e97 bf4361ac5 -- android/app/src/main`): `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{EventEditRepository,EventEditorScreen,EventEditorSections,EventEditorViewModel,EventExtrasRepository}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayWithEditor.kt`, `android/app/src/main/res/values{,-de}/strings_calendar_edit.xml`. `CalendarWithEditor.kt`, listed in the earlier report, is no longer changed by the task: main's `EventEditorCallbacks.of` replaced the task's edit there.

Requirements:
- `tasks/cal-invitation-respond-android.md`: 3 acceptance criteria, plus the assumption that the web subtask's rules apply
- `reviews/cal-invitation-respond-android/review.md` and `fixes.md`, from the earlier revision: the CR-1 and CR-2 behavior
- The parent's note on the new wiring: `EventEditorCallbacks.of` and the Today editor
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/{calendar/edit,gtd/today}/`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/CalendarWithEditorTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/today/TodayWithEditorTest.kt`

## Tests added or updated

Count: 2 added. In both files, `show()` also gained an optional editor parameter, and the existing tests use its default.

There was a real gap. The ViewModel and screen tests already covered answering, but the screen tests pass their own `EventEditorCallbacks(respond = ...)`. No test checked that the editor really reached from the Calendar or Today screen sends the answer. If `respond = editor::respond` went missing from `EventEditorCallbacks.of`, the buttons would silently do nothing (the default is `{}`), and every existing test would still pass.
- `CalendarWithEditorTest` › `an invitation opened from the calendar can be answered in the editor`: opens an event that has guests and is in an invitation calendar from the calendar view, taps Maybe, and checks that `EventEditRepository.respond(id, 2, TENTATIVE)` is called once. The own status then becomes TENTATIVE and the Maybe button is selected.
- `TodayWithEditorTest` › `an invitation opened from Today can be answered in the editor`: the same through Today's events, with Decline. This covers the new Today wiring.

Both use a real `EventEditorViewModel` with mocked repositories (the project's pattern) and fictitious addresses (`me@example.org`, `ada@example.org`). The Dashboard uses the same `EventEditorCallbacks.of(editor)`, so these two tests cover its wiring too. No Dashboard test was added.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Accept / Maybe / Decline appear only for existing events with guests in Google/Microsoft calendars | `EventEditorViewModelRespondTest`: `an existing event with guests in a Google or Microsoft calendar can be answered...`, `a local event, one without guests or a new one can't be answered`, `while the invitation calendars are unknown or failed to load nothing can be answered`; `EventEditorExtrasScreenTest`: `no answers are offered for a local calendar, a new event or one without guests` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' --tests 'ch.sonensei.cockpit.gtd.today.*' --tests 'ch.sonensei.cockpit.dashboard.*' createDebugUnitTestCoverageReport -q` | Pass |
| AC1: the current status is shown | `the current answer is named and selected`, `an unanswered invitation offers accept, maybe and decline...`, `a delegated or unknown own answer counts as not answered...`, `without a matching own address there is no current answer...` | same | Pass |
| AC1: sending an answer updates the status (new version and guests, unsaved edits kept) | `an answer is sent with the event version, then the guests and version are updated and unsaved changes stay`, `only accept, maybe and decline are sent`, `while an answer is sent nothing else can be answered or saved`, the respond tests in `EventEditRepositoryTest` | same | Pass |
| AC1: the answer works in the editor opened from the Calendar and Today screens (`EventEditorCallbacks.of` wiring) | `an invitation opened from the calendar can be answered in the editor`, `an invitation opened from Today can be answered in the editor` (new) | same | Pass |
| AC1: error mapping (offline, 412 conflict, 404 gone, other refusals) | `no connection shows offline...`, `a stale version is a conflict...`, `a missing event is gone`, `other refusals, like not invited or organizer, report that the answer failed...`, `the answer to a closed editor never shows in the next event`, `a reload while an answer is sent drops the answer's result` | same | Pass |
| CR-1: neutral refusal text with a Reload action | `a refused answer is explained` | same | Pass |
| CR-2: Retry/Reload fetches the invitation calendars and own addresses again | `a retry after the sources failed to load fetches them again, so the invitation can be answered`, `a reload of a new event neither fetches the sources again nor drops the form` | same | Pass |
| AC2: strings in English and German | `CalendarEditStringsTest` (in `calendar.edit`) | same | Pass. `lintDebug` (MissingTranslation) was not rerun here: the merge changed no resources |
| AC3: unit tests cover the new behavior; `sh scripts/check.sh` passes | the tests above | same | Unit tests: Pass. `sh scripts/check.sh`: Not run, left to the parent (the full Android suite takes over 20 min on this host) |

## Focused test execution

Command (in `android/`):

`./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' --tests 'ch.sonensei.cockpit.gtd.today.*' --tests 'ch.sonensei.cockpit.dashboard.*' createDebugUnitTestCoverageReport -q`

Result:

Pass (exit 0, 2026-11-09 03:37:49 to 03:38:42 +0100). The results in `app/build/test-results/testDebugUnitTest/` (all written by this run) list 37 test classes and 465 tests: 0 failures, 0 errors, 0 skipped. Among them: CalendarWithEditorTest with 7 tests, TodayWithEditorTest with 3, EventEditorViewModelRespondTest with 26. ktlintCheck passed. The first try failed only on ktlint `function-signature` in my own test edit (`CalendarWithEditorTest.kt:116`). I fixed the formatting and reran.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run in this pass. On this host the full Android suite takes longer than a tool call can run (see the task notes), and the parent runs it as part of `.taskcheck`. The merge changed only the callback wiring and the Today file. Their tests and the Dashboard tests (the other `EventEditorCallbacks.of` user) were all part of the focused run. Known flaky test outside the task: `auth.AppStartRoutingTest > logging out from settings clears the cached files` (task fix-flaky-app-start-logout-test).

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None.

## Coverage

Status: verified for the task's files, with a reduced test selection. The tests in `calendar.edit`, `gtd.today` and `dashboard` ran. Tests outside these packages can only add hits, so the numbers are a lower bound.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. In the JaCoCo XML, the `line` entries with `ci` above 0 were intersected with the added lines of `git diff -U0 924770e97 HEAD` for each file (script `.tmp/tw-respond-cov.py`, Git-ignored). Lines JaCoCo doesn't count as executable are left out. Result: 113/113 changed executable lines covered (100%). Branches on changed lines: 95/103 (92.2%). The `respond = editor::respond` line in `EventEditorScreen.kt` and the changed line in `TodayWithEditor.kt` are covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond-android; base 924770e973ebb0f112aea4b401645a50693c231f; final code bf4361ac5fc66c7a1fa96e098ca0ad9b3a3d0041. The working tree was at HEAD 700f0237a7a40677edec9b0a814ec1dceeaa3ac5, whose code is the same as bf4361ac5 (the only difference is the task file). Scope: the parent's base and final revision, `git diff 924770e97 bf4361ac5`
- Source/test snapshot identifier (including uncommitted inputs): Android sources at bf4361ac5, plus two uncommitted test files. sha256 `CalendarWithEditorTest.kt` 3d738938059e03e42873253d2c130f29a36902b0970f5e0ee8e3ff20514a7d7c (after the ktlint fix), `TodayWithEditorTest.kt` 7a1140f3402bd16b4ce265014fa10d8e291d75cdc3bca06664ea01de4777e558. Both hashes were the same after the run. The only other uncommitted file is `reviews/cal-invitation-respond-android/review.md`, written by the code reviewer, which is not a test input
- Exact included files; renamed/deleted/excluded files and reasons: the 6 Kotlin files listed in Scope. The 2 string XML files are excluded because JaCoCo does not instrument resources; `CalendarEditStringsTest` covers them. No files were renamed or deleted
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; the focused command above, in `android/`; the `calendar.edit`, `gtd.today` and `dashboard` packages
- Run start/completion time and exit status: 2026-11-09 03:37:49 to 03:38:42 +0100, exit 0
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-09 03:38:42 +0100, sha256 0ba53219293f3986850472e67bc252ead1ef4516710f540c57ce29c6cde78240)
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report and test results have mtimes inside the run window, and the inputs had the same hashes before and after
- Final run after latest test additions and production fixes: yes (final code bf4361ac5, with the new tests)

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventEditRepository.kt` | 841/857 (98.1%) | 51/56 (91.1%) | 42/43 (97.7%) | 111/111 (100%) |
| `calendar/edit/EventEditorScreen.kt` | 4658/4835 (96.3%) | 92/97 (94.8%) | 124/156 (79.5%) | 333/335 (99.4%) |
| `calendar/edit/EventEditorSections.kt` | 5877/5946 (98.8%) | 207/224 (92.4%) | 112/118 (94.9%) | 437/438 (99.8%) |
| `calendar/edit/EventEditorViewModel.kt` | 4797/4919 (97.5%) | 402/464 (86.6%) | 100/101 (99.0%) | 462/462 (100%) |
| `calendar/edit/EventExtrasRepository.kt` | 890/900 (98.9%) | 34/36 (94.4%) | 38/40 (95.0%) | 80/80 (100%) |
| `gtd/today/TodayWithEditor.kt` | 488/517 (94.4%) | 27/34 (79.4%) | 10/11 (90.9%) | 32/32 (100%) |
| **Aggregate (summed counters, 6 files; string XML excluded, no file unmeasured)** | 17551/17974 (97.6%) | 813/911 (89.2%) | 426/469 (90.8%) | 1455/1458 (99.8%) |

All paths are under `android/app/src/main/java/ch/sonensei/cockpit/`. The file-level numbers include code that existed before the task in those files (`EventEditorScreen.kt`'s method count includes many Compose lambdas from before the task).

Partial branches on changed lines (line numbers at bf4361ac5):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorViewModel.kt:155` (`form != null` is false while not creating) | defensive: an open edited event always has a form | Justified gap |
| `EventEditorViewModel.kt:163,164,166` (`canRespond` is false because of loading / saving / an open invite dialog) | no answer can be sent during other work | Partly covered: the tests check the block while an answer is sent and during a conflict. The other three are plain boolean conjuncts, so the risk is low and no test was added |
| `EventEditorViewModel.kt:646` (`respond` with no occurrence) | defensive: `canRespond` is already false for new events | Justified gap |
| `EventEditorViewModel.kt:655,659` (`initial?` / `it.form?` are null after an answer) | defensive null safety after the editor closes. `the answer to a closed editor never shows in the next event` covers the closed-editor case | Justified gap |
| `EventEditorSections.kt:304` (1 of 2 branches of `answer == current`) | `the current answer is named and selected` draws both button kinds | Probably a branch in the Compose bytecode, not a missing behavior; no test added |

## Unable to test

- I did not run `sh scripts/check.sh` / `.taskcheck` or the full Android suite and `lintDebug`. The parent runs them, and on this host they take longer than a tool call can run.
- The real server round trip (`POST /api/v1/calendar/events/{id}/respond` against the server) is not part of these unit tests. `EventEditRepositoryTest` checks the request shape with a mock HTTP engine. The criteria name only unit tests.

## Summary

Tests added and all validation passed. The merge with main moved the respond wiring into `EventEditorCallbacks.of` and gave the Today editor the answer buttons too. No test covered that wiring, so I added two Compose integration tests: answering from the editor opened in the Calendar screen and in the Today screen. They pass, together with all focused tests (465 tests in `calendar.edit`, `gtd.today` and `dashboard`) and ktlintCheck. Task-scoped coverage: 113/113 changed executable lines and 95/103 changed branches. The remaining gaps are defensive or low risk. No production defects were found. Left to the parent: `sh scripts/check.sh` (the full Android suite), which has a known flaky test outside the task.
