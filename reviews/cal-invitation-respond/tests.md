# Test Report

Date: 2026-11-09

Task: cal-invitation-respond - Accept, decline or tentatively accept invitations (web + Android). Integration-only parent task. Its subtasks cal-invitation-respond-web and cal-invitation-respond-android are done and merged on main, and this task has no code commits of its own.

Scope: the integrated feature on main at base/HEAD e29b9fc2453edb643132595fd0ae50dd075f2d13, the base the parent gave (= `git merge-base HEAD origin/main`). During the run HEAD moved to aca5f4cefc7646da1b70d26370d0c29302288614. `git diff --stat e29b9fc24 aca5f4cef` changes only `reviews/cal-invitation-respond/review.md`, so the measured code is the same. The working tree was clean (no uncommitted source or test inputs).
- Web: `web/src/app/features/calendar/edit/` (event dialog respond UI, `event-edit.service.ts` `respondToInvitation`/own addresses), `web/src/locale` `calendar.respond.*`.
- Android: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/` (EventEditor respond actions, EventEditRepository), `gtd/today/TodayWithEditor.kt` (wiring via `EventEditorCallbacks.of`), `res/values{,-de}/strings_calendar_edit.xml`. Tests: `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/` and `gtd/today/TodayWithEditorTest.kt`.
- The file scope comes from the subtask reports (`git diff` of each subtask's base..final), checked against the current tree: all the named tests and the `respond = editor::respond` wiring (`EventEditorScreen.kt:107`) are present on main.

Requirements:
- `tasks/cal-invitation-respond.md` acceptance criteria 1-5 and their two recorded assumptions (who counts as an invitee and the current status; criterion 3 N/A because the server does not mark invitation mails)
- `reviews/cal-invitation-respond-web/tests.md` and `reviews/cal-invitation-respond-android/tests.md` (read and reused, not redone)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

None. No new tests were needed (see Summary).

## Tests added or updated

Count: 0

I checked the integrated tests on main for each criterion and found no real gap. Each subtask's test-writer had already closed the gaps it found: web CR-1 'saved' on close and repeated answers; Android `EventEditorCallbacks.of` wiring from Calendar and Today.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 (web): Accept / Maybe / Decline for events where the user is an invitee, current status shown | `event-dialog.spec.ts` 'answering invitations' block: offers the choices with no answer highlighted; offers none without guests or in local/read-only calendars, nor for a new event; highlights the current answer of the guest with a mail account address; no highlight without a matching address; `event-edit.service.spec.ts` lists own addresses in lower case | `cd web && npx ng test --watch=false --include src/app/features/calendar/edit` | Pass (8 files, 198 tests) |
| AC1 (web): sending updates the status (POST .../respond with If-Match, new version, guests updated, form edits kept, 'saved' on close) | sends the answer, shows the new status and uses the new version; answers a generated occurrence through its series master (service); closes with 'saved' after an answer (Escape, backdrop, Save, Cancel); keeps unsaved edits; busy handling; 412 Reload, 409 and other errors | same | Pass |
| AC2 (Android): the same three actions, with the current status, sending and errors | `EventEditorViewModelRespondTest` (15), respond tests in `EventEditorExtrasScreenTest` and `EventEditRepositoryTest`; `CalendarWithEditorTest` 'an invitation opened from the calendar can be answered in the editor'; `TodayWithEditorTest` 'an invitation opened from Today can be answered in the editor' | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' --tests 'ch.sonensei.cockpit.gtd.today.*' createDebugUnitTestCoverageReport` | Pass (22 classes, 304 tests, 0 failures) |
| AC3: invitation mails in the mail read view | none | n/a | Not testable: N/A per the recorded assumption (the mail API has no invitation marker, so no mail-view code exists) |
| AC4 (web): strings in English and German | the dialog specs assert the English texts; German targets are enforced by `npm run build:de` in `npm run check` | n/a | Not run here: build:de is part of the parent's `.taskcheck` run. The parent reported `ng test --include src/locale` -> 10 passed |
| AC4 (Android): strings in English and German | `CalendarEditStringsTest` (4); `lintDebug` MissingTranslation in `.taskcheck` | Android command above | Pass (CalendarEditStringsTest 4/4); lintDebug left to `.taskcheck` |
| AC5: unit tests cover the new behavior on each changed part | all of the above | both focused commands | Pass on both clients; no server code changed |
| AC5: `sh scripts/check.sh` passes | none | n/a | Not run: the parent runs it, as instructed |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/calendar/edit`

Result:

Pass - 8 files, 198 tests (04:11:46). Two reruns with `--coverage` (04:12:07 and about 04:21:30) also passed with 198 tests.

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`

Result:

Pass - BUILD SUCCESSFUL. Gradle reported `testDebugUnitTest UP-TO-DATE`, so it reused the results of an identical earlier run (results written 04:09:42): 17 classes, 235 tests, 0 failures/errors/skips. EventEditorViewModelRespondTest 15/15, CalendarWithEditorTest 7/7, CalendarEditStringsTest 4/4. The tests were then actually rerun in the coverage command below: 304 tests in `calendar.edit` + `gtd.today`, 0 failures, BUILD SUCCESSFUL in 1m 14s.

Note: the Android subtask report says "EventEditorViewModelRespondTest with 26". The source file and the results both have 15 `@Test`s (`EventEditorExtrasScreenTest` has 26). This is a miscount in that report, not a lost test.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (without a test filter, so the whole Android unit suite ran)

Result:

Fail - 5219 tests, 1 failure: `ch.sonensei.cockpit.auth.AppStartRoutingTest > logging out from settings clears the cached files` (ComposeTimeoutException after 30000 ms). This is the known flaky test outside this task, tracked by task fix-flaky-app-start-logout-test, and has nothing to do with calendar or invitation code. All other 5218 tests passed. Because of that failure the run wrote no coverage report, so coverage was measured with the focused selection below.

Full web suite: not run in this pass. The web subtask's final full run (`npm run coverage`) passed 4583 tests, and the parent's `.taskcheck` runs it.

## .taskcheck

Result:

Not run (the parent is running `sh scripts/check.sh`, as instructed).

## Bugs found

None.

## Coverage

Status: verified for both clients' task-changed production files, with a focused test selection per client (tests outside the selection can only add hits, so these numbers are a lower bound).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not re-measured in this pass. The subtask reports measured it on their final code: web all changed executable lines covered (both files at 100% lines); Android 113/113 changed executable lines and 95/103 branches on changed lines (diff `-U0` intersected with JaCoCo `ci`). The per-file numbers below are equal to or slightly below theirs (Android without the `dashboard` package), so nothing regressed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond; base e29b9fc2453edb643132595fd0ae50dd075f2d13 (from the parent); measured code e29b9fc24, identical to aca5f4cef (only the review report differs). There are no task-owned code commits; the file scope is the union of the subtask scopes in their tests.md reports.
- Source/test snapshot identifier (including uncommitted inputs): Git tree at e29b9fc24, clean working tree, no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: web `web/src/app/features/calendar/edit/event-dialog.ts`, `event-edit.service.ts`; Android `calendar/edit/{EventEditRepository,EventEditorScreen,EventEditorSections,EventEditorViewModel,EventExtrasRepository}.kt`, `gtd/today/TodayWithEditor.kt`. Excluded: `event-dialog.html`/`.scss` (not instrumented by v8; the DOM tests exercise them), `web/src/locale/*.xlf` and `res/values{,-de}/strings_calendar_edit.xml` (resources, not instrumented; covered by build:de / CalendarEditStringsTest / lint). No renames or deletions. No file is unmeasured.
- Provider/version, command, working directory, and test selection: web Vitest v5.0.5 + `@vitest/coverage-v8`, `npx ng test --watch=false --include src/app/features/calendar/edit --coverage --coverage-reporters=json-summary` in `web/` (the `npm run coverage` flags restricted to the calendar edit specs); Android JaCoCo via AGP `enableUnitTestCoverage`, `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' --tests 'ch.sonensei.cockpit.gtd.today.*' createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: web json run 04:12:07-04:12:42 and json-summary run ending 04:22:09, both exit 0 with 198 passed; Android focused run completed 04:21:30, BUILD SUCCESSFUL (304 tests, 0 failures). An earlier unfiltered Android run (6m 31s) failed on the flaky test above and wrote no report; none of its output is used.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 debe89b0c27e95afd3e04cce4808a38b1a79743a22aa9755ce2060ab312d4063; `web/coverage/web/coverage-final.json` (04:12:42) sha256 387cbbbfa6d73bdc844a140c9f7ffa5dfd13431fd79b20ea1889195fc6109cbe (its statement/branch/function counters are the same); `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-11-09 04:21:30) sha256 e3ecddbd750f36de0ad673f1f289b74b3127aeede9a282d5abbd2cd633710482.
- Evidence outputs are fresh and inputs stayed unchanged: yes. All reports were rewritten by these runs, the working tree stayed clean, and the only commit during the run touched a review report.
- Final run after latest test additions and production fixes: yes (no code changed after the subtasks merged).

Values below use covered/total (percentage). Android "Statements" = JaCoCo INSTRUCTION, "Functions" = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/edit/event-dialog.ts` | 247/252 (98.0%) | 178/188 (94.7%) | 58/58 (100%) | 207/207 (100%) |
| `web/src/app/features/calendar/edit/event-edit.service.ts` | 36/36 (100%) | 27/27 (100%) | 17/17 (100%) | 31/31 (100%) |
| Web aggregate (2 files) | 283/288 (98.3%) | 205/215 (95.3%) | 75/75 (100%) | 238/238 (100%) |
| `android/.../calendar/edit/EventEditRepository.kt` | 825/857 (96.3%) | 51/56 (91.1%) | 40/43 (93.0%) | 109/111 (98.2%) |
| `android/.../calendar/edit/EventEditorScreen.kt` | 4649/4835 (96.2%) | 91/97 (93.8%) | 123/156 (78.8%) | 333/335 (99.4%) |
| `android/.../calendar/edit/EventEditorSections.kt` | 5877/5946 (98.8%) | 207/224 (92.4%) | 112/118 (94.9%) | 437/438 (99.8%) |
| `android/.../calendar/edit/EventEditorViewModel.kt` | 4786/4919 (97.3%) | 402/464 (86.6%) | 99/101 (98.0%) | 461/462 (99.8%) |
| `android/.../calendar/edit/EventExtrasRepository.kt` | 890/900 (98.9%) | 34/36 (94.4%) | 38/40 (95.0%) | 80/80 (100%) |
| `android/.../gtd/today/TodayWithEditor.kt` | 451/517 (87.2%) | 25/34 (73.5%) | 10/11 (90.9%) | 28/32 (87.5%) |
| Android aggregate (6 files, summed counters) | 17478/17974 (97.2%) | 810/911 (88.9%) | 422/469 (90.0%) | 1448/1458 (99.3%) |

The aggregates are per client. Web statements (v8) and Android instructions (JaCoCo) are different units, so no combined figure is given. The excluded resource and template files are listed above. File-level numbers include code that existed before the task.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TodayWithEditor.kt:26-29` | Hilt `hiltViewModel()` default arguments; the tests inject view models | Non-material DI glue. The respond wiring through the Today editor is covered by 'an invitation opened from Today can be answered in the editor' |
| `EventEditRepository.kt:68,71` | Hilt `@Provides` Retrofit factories | Non-material DI glue; `respond()` (line 182) is covered via the mock HTTP engine tests |
| `EventEditorViewModel.kt:196` | `@Inject` constructor with the system clock | Non-material DI glue |
| `EventEditorScreen.kt:267,469`, `EventEditorSections.kt:630` | Pre-existing Compose code, not respond-specific | Outside the respond behavior; left as is |
| `EventEditorViewModel.kt` partial branches on `canRespond`/`respond` (loading, saving or invite dialog conjuncts; defensive nulls) | No answer while other work runs | Justified in the Android subtask report (plain boolean conjuncts / defensive); no new test |
| `event-dialog.ts` `respond()` guard and create-mode arm of `occurrenceCalendar` | Defensive guards; the buttons are hidden or disabled in those states, which the tests assert | Justified in the web subtask report; no new test |

## Unable to test

- AC3: nothing to test (N/A per the recorded assumption; a server task would have to add an invitation marker first).
- German web translations and Android MissingTranslation lint: enforced by `npm run build:de` and `lintDebug` inside `.taskcheck`, which the parent runs.
- Real server/provider round trip of `POST /api/v1/calendar/events/{id}/respond`: outside the unit-test criteria; request shape and errors are covered with mocked HTTP on both clients.
- The full Android suite has the known flaky `AppStartRoutingTest` logout test (outside the task). If `.taskcheck` hits it, that failure is not caused by this feature.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On integrated main, the respond feature has unit tests on both clients for criteria 1, 2 and 4: web 198/198 in calendar edit, Android 304/304 in `calendar.edit` + `gtd.today`. Fresh coverage: web 238/238 lines, Android 1448/1458 lines, and every uncovered line is Hilt glue or code that existed before the task. No tests were added. `sh scripts/check.sh` remains for the parent.
