# Test Report

Date: 2026-11-08

Task: and-cal-event-fields - Android events: reminders, attendees, invitations, time zone, links

Scope: `git diff eb07e0e3525ac8465a53e87c7b92db432caaf1d3 1490f8a043e72fee7d8e1bb4ded35f4f200b5d9a`
(task commits 474eae4b6, 07d8a3f9c, bdff4e363, 1b2466dd1, 85fad8305, merged in 1490f8a04; branch `main`),
plus the two uncommitted test-file edits of this run (hashes below). Ownership: commit list given by the
parent worker, consistent with the task's `touches` and `git log eb07e0e3..HEAD`.

Requirements:
- `tasks/and-cal-event-fields.md` acceptance criteria and recorded assumptions (rudi/agent-1, 2026-11-08)
- `docs/ARCHITECTURE.md` "Coverage" and "Code conventions" (EN/DE strings), `AGENTS.md`
- Parent instructions (scope, commands)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventFormExtrasTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorExtrasScreenTest.kt`

Existing task tests (already committed, reviewed, unchanged): `EventEditorViewModelExtrasTest.kt` (36),
`EventEditorExtrasScreenTest.kt` (19 before), `EventExtrasRepositoryTest.kt` (12), `EventFormExtrasTest.kt`
(9 before), `CalendarEditStringsTest.kt` (4).

## Tests added or updated

Count: 2

- `EventFormExtrasTest`: `the invitation prompt sends only over a loaded account, with someone to tell, and not twice`
  (`InvitationUiState.canSend`: no account, accounts loading, account not in list, sending, only removed guests with
  and without "tell removed", nothing to send).
- `EventEditorExtrasScreenTest`: `a links reload keeps the shown items, and nothing is created while the event saves`
  (reload with items keeps them instead of "Loading…"; Create task / note disabled while saving).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Reminders add/remove, presets + custom minutes | EventFormExtrasTest: `reminders are added once...`, `the reminder presets match the web...`, `a custom reminder is a whole number from 0 to the limit`, `a create always sends the reminders...`, `a change sends reminders and guests only when they changed...`; EventEditorExtrasScreenTest: `a preset reminder is added...`, `a custom reminder takes the minutes...`, `reminders of a read-only event...`; EventEditorViewModelExtrasTest: `a custom reminder and a removed preset are saved...`, `removing every reminder saves an empty list`; EventExtrasRepositoryTest: `a loaded event carries its reminders...` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Pass |
| Attendees add (contact autocomplete) / remove, participation status | EventFormExtrasTest: guests trim/dedupe, email check, `a guest's answer is mapped from the API...`; EventEditorViewModelExtrasTest: `guests are edited only in local calendars...`, `suggestions wait for a pause and two characters...`, `a query cleared while suggestions load...`, `failed suggestions show none`, `a typed guest must be an email address...`, `an older suggestions answer arriving after a newer query is dropped`, `suggestions still loading when a guest is added...`; EventEditorExtrasScreenTest: `guests show their answers...`, `every answer of a guest has its label`, `suggestions are listed and picked...`, `guests of a synced calendar are read-only...`; EventExtrasRepositoryTest: `suggestions ask for eight matches...`, `only writable calendars of the local source count as local` | same | Pass |
| On save offer invitations / cancellations (iTIP REQUEST/CANCEL) | EventEditorViewModelExtrasTest: `a new event with guests ... offers to invite them`, `a save without guests ... closes`, `removing every guest ... sends them a cancellation`, `a sent invitation goes to the guests ... also cancels for removed guests`, `removed guests are not told when that is unchecked`, `a failed send keeps the prompt...`, `while sending the prompt can neither change nor be dismissed...`, `skipping the prompt after a save...`, `mail accounts that fail to load...`, `send invitations asks to save unsaved changes first`, `send invitations for the saved event...`, `send invitations is not offered for...`, `a changed occurrence of a series with guests asks the scope, then offers invitations`, `a reopened invitation prompt shows the newer accounts answer...`, `a save tapped twice while it runs...`; EventExtrasRepositoryTest: `an invitation is posted ... counts the recipients`; EventEditorExtrasScreenTest: invitation prompt tests (4 + `send invitations is offered...`); EventFormExtrasTest: `the invitation prompt sends only over a loaded account...` (new) | same | Pass |
| Time zone of the event editable | EventEditorViewModelExtrasTest: `a changed time zone keeps the clock times and saves them in the new zone`, `a new event is created in the picked time zone`; EventEditorExtrasScreenTest: `the time zone picker searches zones...`, `a search without matches says so...`, `the time zone of a read-only event can't be changed`; EventFormExtrasTest: `the time zone list holds region ids and UTC...` | same | Pass |
| Linked items panel (read-only list) + Create task / note from event | EventEditorViewModelExtrasTest: `the links of an edited event are loaded...`, `a task from the event is titled with the event and its date...`, `a note whose link failed still reloads the links...`, `an older links answer arriving last...`, `a links reload while one is pending...`, `the links answer of a closed editor never shows...`; EventExtrasRepositoryTest: links/task/note/not-linked/not-created tests; EventEditorExtrasScreenTest: 4 links tests + `a links reload keeps the shown items...` (new) | same | Pass |
| New strings in English and German | CalendarEditStringsTest (4 tests); Android lint `MissingTranslation` via `lintDebug` | same | Pass |
| Unit/Compose tests; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` -> BUILD SUCCESSFUL, 3614 tests, 0 failures, 0 skipped | Pass (Android part). `sh scripts/check.sh`: Not run by test-writer (parent runs `.taskcheck`; previous owner noted a flaky unrelated server test and 9 min runtime) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.EventFormExtrasTest' --tests 'ch.sonensei.cockpit.calendar.edit.EventEditorExtrasScreenTest'`

Result:

Pass (EventFormExtrasTest 10/10, EventEditorExtrasScreenTest 20/20, ktlint clean)

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (run before and after the additions;
final run 2026-11-08T15:19:27+01:00 to 15:26:15+01:00, JDK 21.0.12.1, Gradle 9.8.1)

Result:

Pass: BUILD SUCCESSFUL, exit 0; 3614 unit tests, 0 failures, 0 errors, 0 skipped; all 16
`calendar.edit` suites green; lint only reports 2 `AutoboxingStateCreation` hints in unrelated files
(journal, notes).

## .taskcheck

Result:

Not run (by test-writer). The parent worker runs `sh scripts/check.sh` before `done`.

## Bugs found

None. No production defect was found. The previously failing links-reload race tests (bdff4e363) pass since
1b2466dd1.

Note (not a defect at present): `edit()` / `create()` do not cancel `linksJob`, `loadLinksJob`, `suggestJob` or
`inviteJob`; only `close()` (`reset()`) does. The UI only calls `edit`/`create` while the editor is closed
(`CalendarWithEditor.kt` shows the calendar only when `!editorState.open`), so a stale answer cannot reach another
event today; `the links answer of a closed editor never shows in the next event` covers the close path.

## Coverage

Status: verified (fresh JaCoCo run after the latest test additions, task commits unchanged).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no existing changed-line tooling; whole-file numbers include old code
in the modified files and hits from pre-existing tests).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-cal-event-fields`; base
  `eb07e0e3525ac8465a53e87c7b92db432caaf1d3`; HEAD `1490f8a043e72fee7d8e1bb4ded35f4f200b5d9a`; commits
  474eae4b6, 07d8a3f9c, bdff4e363, 1b2466dd1, 85fad8305 (from parent); file list from
  `git diff --stat eb07e0e3 HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1490f8a04 plus uncommitted
  `EventFormExtrasTest.kt` sha256 `de469af87769087710f2a07aaaa15fea5ca248f52a5b577fae6d9fe5b1867c6a` and
  `EventEditorExtrasScreenTest.kt` sha256 `a85eba89c8ac2e68050aa3411e5d816fd90dfccde18afeecff8dae49addb267d`
  (verified unchanged before and after the run). `reviews/and-cal-event-fields/review.md` was also modified in the
  working tree during this run by another helper (not a code input).
- Exact included files (all in `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/`):
  `CalendarWithEditor.kt`, `EventEditRepository.kt`, `EventEditorExtrasState.kt`, `EventEditorScreen.kt`,
  `EventEditorSections.kt`, `EventEditorViewModel.kt`, `EventExtrasRepository.kt`, `EventForm.kt`. No renames or
  deletions. Excluded: `res/values*/strings_calendar_edit.xml` (resources, not instrumented; checked by
  `CalendarEditStringsTest` and lint), test files, `reviews/`.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`;
  `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests; per-file counters read from
  the XML `<sourcefile>` elements of package `ch/sonensei/cockpit/calendar/edit`.
- Run start/completion time and exit status: 2026-11-08T15:26:15+01:00 to 15:26:23+01:00, exit 0 (it reused the
  execution data of the full test run that finished at 15:26:15 on the same inputs).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 `9566ddabba36411a4decb423a53f65772917df53fb552eaf613b382dbef5242b`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report regenerated after the additions; the earlier
  report before the additions had a different hash, f83c97e2...).
- Final run after latest test additions and production fixes: yes (no production change in this run).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `EventEditorViewModel.kt` | 4026/4142 (97.2%) | 341/396 (86.1%) | 88/89 (98.9%) | 399/399 (100.0%) |
| `EventEditorSections.kt` | 5474/5535 (98.9%) | 186/199 (93.5%) | 106/111 (95.5%) | 412/413 (99.8%) |
| `EventEditorScreen.kt` | 4478/4662 (96.1%) | 86/92 (93.5%) | 120/152 (78.9%) | 307/309 (99.4%) |
| `EventExtrasRepository.kt` | 787/797 (98.7%) | 30/32 (93.8%) | 32/34 (94.1%) | 71/71 (100.0%) |
| `EventEditorExtrasState.kt` | 293/293 (100.0%) | 20/20 (100.0%) | 29/29 (100.0%) | 32/32 (100.0%) |
| `EventForm.kt` | 1477/1493 (98.9%) | 124/129 (96.1%) | 50/50 (100.0%) | 127/127 (100.0%) |
| `EventEditRepository.kt` | 786/802 (98.0%) | 51/56 (91.1%) | 40/41 (97.6%) | 107/107 (100.0%) |
| `CalendarWithEditor.kt` | 1214/1252 (97.0%) | 25/36 (69.4%) | 10/11 (90.9%) | 56/56 (100.0%) |
| **Total (summed counters, 8 files, none unmeasured)** | 18535/18976 (97.7%) | 863/960 (89.9%) | 475/517 (91.9%) | 1511/1514 (99.8%) |

Before the two added tests: `EventEditorExtrasState.kt` branches 15/20 (75.0%), `EventEditorSections.kt` branches
183/199, total branches 854/960 (89.0%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorExtrasState.kt:35-36` (`canSend`) | Invitation can only be sent with a valid account and someone to tell | Added `the invitation prompt sends only over a loaded account...`; now 20/20 |
| `EventEditorSections.kt:541,560,565` (links loading with items; create disabled while saving) | Links reload / create guard | Added `a links reload keeps the shown items...`; 3 more branches covered |
| `EventEditorViewModel.kt` (55 partial branches: e.g. 338, 459, 488-593 guards) | Short-circuit guards (`?: return`, `extras == null`, non-`EventDetails` result) | Justified gap: each guarded behavior has a covered main path; the remaining halves are defensive (null extras only in legacy constructor use, result type always `EventDetails`) |
| `EventEditorViewModel.kt:722-727` (`editForm` date fallbacks) | Pre-existing load fallbacks, not task code | Out of task change; not added |
| `EventExtrasRepository.kt:108` (blank email/name), `:142` (`OTHER` type fallback) | Suggestions without address are dropped; unknown link type | Blank address path tested in `suggestions ask for eight matches...`; remaining missed half is an unreachable combination; `OTHER` fallback only for a future API type (all current types map) - low risk, left |
| `EventEditorScreen.kt:235,435` and 32 unhit methods | `LOAD_FAILED` label, custom repeat label: pre-existing editor code, not changed by this task | Outside task changes; not added |
| `CalendarWithEditor.kt:34,77,85,93` | Compose default arguments / FAB day-vs-other view and birthday routing (pre-existing) | Only the 10 callback lines were added by the task and are covered; rest pre-existing |
| `EventForm.kt:192,201,222`, `EventEditRepository.kt:99,120,209,237,238` | Pre-existing body/mapping branches | Outside task changes / defensive |

## Unable to test

- `sh scripts/check.sh` (`.taskcheck`, full repo incl. server and web) was not run by the test-writer; the parent
  worker runs it before `done`.
- Real iTIP mail delivery and server-side link creation: only the client calls are tested (mocked APIs); the server
  endpoints belong to other tasks. No follow-up needed for this task.
- Instrumented (device) UI tests: the project uses Robolectric Compose tests in `testDebugUnitTest`; no device run.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Two tests added (invitation send guard,
links panel reload/saving state); `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes.
