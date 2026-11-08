# Test Report

Date: 2026-11-08

Task: cal-invitation-respond-android (answer invitations in the Android event editor)

Scope: base d0368f222584f4231dd4e673147472c8562769c5, task commit e4c18e794 (rebased as d29f0d216; production
code under `android/app/src/main` is identical between e4c18e794 and the measured HEAD
aa33beb45b7cd788d12b30fec87b9f7c4e59f9f8, checked with `git diff --stat e4c18e794 HEAD -- android/app/src/main` -> empty).
Changed production files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{CalendarWithEditor,EventEditRepository,EventEditorScreen,EventEditorSections,EventEditorViewModel,EventExtrasRepository}.kt`,
plus string resources (en/de).

Requirements:
- Task file `tasks/cal-invitation-respond-android.md`, parent task cal-invitation-respond assumptions, and the
  behaviour described by the parent worker (respondable / canRespond / ownStatus, respond() result mapping).
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/`.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelRespondTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventExtrasRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorExtrasScreenTest.kt` (new tests plus a `respond` callback in the `show` helper)

## Tests added or updated

Count: 22 (13 ViewModel, 2 EventEditRepository, 2 EventExtrasRepository, 5 Compose/Robolectric screen tests)

## Requirement coverage

Command for every row: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Respondable only for an existing event with guests in a writable Google/Microsoft calendar | `an existing event with guests in a Google or Microsoft calendar can be answered...`, `a local event, one without guests or a new one can't be answered`, `while the invitation calendars are unknown or failed to load nothing can be answered` | see above | Pass |
| invitationCalendars = writable GOOGLE + MICROSOFT calendars only | `only writable Google and Microsoft calendars answer invitations` | see above | Pass |
| ownAddresses = mail account addresses, lower case; ownStatus matches case-insensitively | `the own addresses are the mail account addresses in lower case, once each`, `an existing event ... the own guest shows the answer`, `without a matching own address there is no current answer...` | see above | Pass |
| respond() POSTs `/api/v1/calendar/events/{id}/respond` with `{"response":...}` and the event version as quoted `If-Match` | `an answer is posted to the event with its version as If-Match and returns the event with the new status` | see above | Pass |
| Repository maps 412/409/404 to Failed(status), 5xx to Offline | `a refused answer keeps its status, a server error counts as offline` | see above | Pass |
| On Ok: attendees and version updated, unsaved form edits stay, next answer uses the new version | `an answer is sent with the event version, then the guests and version are updated and unsaved changes stay` | see above | Pass |
| Offline -> OFFLINE (retry possible) | `no connection shows offline and the answer can be sent again` | see above | Pass |
| 412 -> CONFLICT (blocks answer and save) | `a stale version is a conflict that blocks further answers and saves` | see above | Pass |
| 404 -> GONE | `a missing event is gone` | see above | Pass |
| Other failures (409, 422, unreadable) -> RESPOND_FAILED, retry and save still allowed | `other refusals, like not invited or organizer, report that the answer failed and allow another try` | see above | Pass |
| Guard while responding (no second answer, no save/delete); only ACCEPTED/TENTATIVE/DECLINED accepted | `while an answer is sent nothing else can be answered or saved`, `only accept, maybe and decline are sent` | see above | Pass |
| Late result dropped after close or reload | `the answer to a closed editor never shows in the next event`, `a reload while an answer is sent drops the answer's result` | see above | Pass |
| ResponseSection UI: three buttons, labels, tap sends answer, current one selected and named, disabled while responding / after conflict, refusal message, hidden when not respondable | `an unanswered invitation offers accept, maybe and decline and sends the tapped one`, `the current answer is named and selected`, `while an answer is sent or after a conflict the answers are disabled`, `a refused answer is explained`, `no answers are offered for a local calendar, a new event or one without guests` | see above | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`

Result:

Pass (ktlint clean after `./gradlew ktlintTestSourceSetFormat` on test sources only; every suite in the package
0 failures/0 errors, e.g. EventEditorViewModelRespondTest 13, EventEditorExtrasScreenTest 25,
EventEditRepositoryTest 16, EventExtrasRepositoryTest 14, EventEditorViewModelExtrasTest 36).

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (full Android unit suite)

Result:

Not run: time budget (~18 min) given by the parent; the full calendar.edit package was run.

## .taskcheck

Result:

Not run: left to the parent worker (runs before `tasks.py done`).

## Bugs found

None.

## Coverage

Status: verified (whole-file, for the task-changed Kotlin files; the run selected only `ch.sonensei.cockpit.calendar.edit.*` tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured); the changed regions were inspected manually below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitation-respond-android; base d0368f222584f4231dd4e673147472c8562769c5; measured HEAD aa33beb45b7cd788d12b30fec87b9f7c4e59f9f8; task commit e4c18e794 / rebased d29f0d216 as given by the parent; file scope from `git diff --stat d0368f222 e4c18e794`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD (clean); uncommitted test files sha256: EventEditRepositoryTest.kt c4d7b0d6..., EventEditorExtrasScreenTest.kt b6ce0a6b..., EventExtrasRepositoryTest.kt b4486edd..., EventEditorViewModelRespondTest.kt 2fceb5cd...
- Exact included files: CalendarWithEditor.kt, EventEditRepository.kt, EventEditorScreen.kt, EventEditorSections.kt, EventEditorViewModel.kt, EventExtrasRepository.kt. Excluded: `strings_calendar_edit.xml` (en/de, resources, not instrumentable); test file EventEditorViewModelExtrasTest.kt. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' createDebugUnitTestCoverageReport -q` in `android/`; selection = calendar.edit package.
- Run start/completion time and exit status: 2026-11-08 ~23:14 - 23:15:10 +0100, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 7a30172dc9e8eac4b2274dc35f5e4fe035b113404cf9dc4bd2616696b462ce73.
- Evidence outputs are fresh and inputs stayed unchanged: test results written 23:14:43, report 23:15:10 in this run; no test or production edits after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `CalendarWithEditor.kt` | 1221/1265 (96.5%) | 19/24 (79.2%) | 10/11 (90.9%) | 55/57 (96.5%) |
| `EventEditRepository.kt` | 825/857 (96.3%) | 51/56 (91.1%) | 40/43 (93.0%) | 109/111 (98.2%) |
| `EventEditorScreen.kt` | 4523/4709 (96.1%) | 91/97 (93.8%) | 122/155 (78.7%) | 311/313 (99.4%) |
| `EventEditorSections.kt` | 5862/5933 (98.8%) | 201/218 (92.2%) | 112/118 (94.9%) | 437/438 (99.8%) |
| `EventEditorViewModel.kt` | 4780/4913 (97.3%) | 400/462 (86.6%) | 99/101 (98.0%) | 458/459 (99.8%) |
| `EventExtrasRepository.kt` | 890/900 (98.9%) | 34/36 (94.4%) | 38/40 (95.0%) | 80/80 (100.0%) |
| **Aggregate (summed counters, 6 files, none missing)** | 18101/18577 (97.4%) | 796/893 (89.1%) | 421/468 (90.0%) | 1450/1458 (99.5%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorViewModel.kt:636,645,649` (partial) | `occurrence ?: return`, `initial?.`, `it.form?.` null branches in respond() | Defensive: unreachable while `canRespond` holds (needs a form and an open event); left uncovered |
| `EventEditorViewModel.kt:152,160-163` (partial) | short-circuit combinations of respondable/canRespond (e.g. loading/saving/invitation while respondable) | Main conditions covered (responding, CONFLICT, GONE, creating, no guests, unknown calendars); remaining combinations are shared with canSave tests; left |
| `EventEditorSections.kt:293` (partial) | Note text for `ownStatus == null` / DELEGATED | Low risk; null ownStatus covered at ViewModel level only; left |
| `EventEditorViewModel.kt:193`, `EventEditorScreen.kt:240,442`, `CalendarWithEditor.kt:36-37`, `EventEditRepository.kt:68,71` | Pre-existing code outside this task's change | Not in task change; left |

## Unable to test

- Real Google/Microsoft provider round-trip and organizer notification: server-side behaviour, not reachable from Android unit tests. No follow-up needed for this task.
- Full Android unit suite and `.taskcheck` not run within the given time budget (parent runs `.taskcheck`).

## Summary

Tests added and all validation passed (focused package suite + ktlint); no production defects found. Full suite and `.taskcheck` are left to the parent.
