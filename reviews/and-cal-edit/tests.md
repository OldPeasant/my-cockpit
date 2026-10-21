# Test Report

Date: 2026-10-21

Task: and-cal-edit - Android event create/edit

Scope: task commits 5ef72d65, d4fad91e, f83a0341, 7b78b549 and bb5b6544 on base 384c5efc10df8d68b9bad30f76a154388de93b2d, measured at HEAD bb5b65443410faa9108ae741e7c5c29833aca434. The parent gave the base and the commit list. This final round reviews only bb5b6544 (CR-5), the one code change since the previous report. The working tree is clean (`git status --short` is empty), so HEAD fully identifies the state that was tested. Included production files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{CalendarWithEditor,EventEditRepository,EventEditorScreen,EventEditorViewModel,EventForm}.kt`, `android/app/src/main/res/values/strings_calendar_edit.xml` (resources, not instrumented) and the one-line editor hook in `calendar/agenda/CalendarScreen.kt`. Excluded: `reviews/` (documentation) and the Hilt-generated `*_Factory`/`*_HiltModules*` files (generated).

Requirements:
- tasks/and-cal-edit.md acceptance criteria and recorded assumptions
- reviews/and-cal-edit/review.md (CR-1..CR-5) and reviews/and-cal-edit/fixes.md
- The create contract as quoted in CR-5: `POST /api/v1/calendar/events` answers 409 when the id belongs to an event in the Trash, or when the calendar is read-only or cannot be changed
- AGENTS.md and docs/ARCHITECTURE.md conventions (fictitious test data, ktlint)

## Test files changed

None in this round. bb5b6544 (the parent's fixer commit) added 2 tests to `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelEdgeCasesTest.kt` (committed, sha256 cce0ec499c0fc8180ef7bc449aea331e92c99263708d0bd4e065117b210ff312). That file also contains the 3 tests from my previous round, which are now committed in 7b78b549.

## Tests added or updated

Count: 0 in this round. I reviewed the 2 CR-5 tests and found them adequate:

- `a refused create (409) is a failure, the form stays editable and the retry uses a new id`: checks the message is FAILED (not CONFLICT), the editor stays open, `canSave` is true, edits are still accepted (another calendar is picked), and the retry is sent to the new calendar with a different id.
- `other refused creates (412, 404, 422) are failures and the retry keeps the id`: checks that a create maps to FAILED for every status, including the statuses that map to CONFLICT or GONE for an edit, and that the id is kept for every status except 409.

The other side of the `creating` switch is pinned by existing tests. `a 409 is a conflict too` (EdgeCases) and `a conflict blocks saving until the event is reloaded` (412, VM test) check that an edit still maps to CONFLICT and blocks edits. `a valid new event is created with one id across retries` checks that an offline create keeps its id. So the fix is covered in both directions, and the earlier behavior has a regression guard.

Gap I deliberately did not test: `CalendarResult.Failed(null)` on a create (an unreadable answer). It is the one missed branch on line 227. The message is FAILED whatever the status, and keeping the id is the safe default (no duplicate if the create did reach the server). This is a defensive gap, not a material one.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 event form: fields, validation | EventFormTest, EventEditorViewModelTest `an invalid form shows its errors and is not sent`, EventEditorScreenTest `validation messages show once a save was tried`, `the form shows the event and edits its fields` | parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' lintDebug` on bb5b6544 | Pass (183 calendar tests, 0 failures) |
| AC1 recurrence presets (+ kept custom rule, CR-3) | EventFormTest preset tests; VM `a custom rule stays a choice after a preset was picked...`, `a preset rule offers no custom choice`; Screen `the repeat presets are offered`, `a custom rule of a series is kept as a choice` | same | Pass |
| AC1 THIS/FOLLOWING/ALL prompt | VM scope-prompt tests; EdgeCases `the scope prompt ignores a second save...`; Screen `the scope prompt offers the given scopes`, `a new repeat rule leaves THIS out of the prompt`; Repository patch scope tests | same | Pass |
| AC2 Robolectric/Compose screen tests | EventEditorScreenTest, CalendarWithEditorTest | same | Pass |
| CR-1 re-read of generated occurrences | VM re-read/override/gone tests, Repository re-read tests, Screen `an occurrence that is gone...` | same | Pass |
| CR-2 close ignored while saving | EdgeCases `closing while a save is running is ignored...`, `closing after a failed save closes the editor`; Screen `while a save runs the editor can't be closed` | same | Pass |
| CR-4 editForm paths (all-day, override zone, master-load failure, invalid zone) | EdgeCases CR-4 tests, including the 3 from the previous round | same | Pass |
| CR-5 refused create is FAILED, the form stays editable, and a 409 gets a new id | EdgeCases `a refused create (409) is a failure...`, `other refused creates (412, 404, 422)...`; regression guard `a 409 is a conflict too` (edit) | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*' lintDebug` (run by the parent on bb5b6544; Gradle is denied in this sub-agent session)

Result:

Pass: BUILD SUCCESSFUL, 183 calendar tests, 0 failures, ktlint and Android lint clean.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (run by the parent on bb5b6544)

Result:

Pass: 1227 Android unit tests, 0 failures.

## .taskcheck

Result:

Not run on bb5b6544. The parent reports that `sh scripts/check.sh` passed on 4ef0c96d (before CR-5). bb5b6544 changes only the Android VM and its test, and those were checked by the focused run above (ktlint, unit tests and lint). The parent should run `.taskcheck` again on the final revision before `done`.

## Bugs found

None.

## Coverage

Status: verified, for line and branch counters. These come from a fresh parent run on bb5b6544. Statement and function counters were not given to me for this run, so they are N/A.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. Every task file is new, so whole-file coverage is close to changed-line coverage. For the CR-5 lines (EventEditorViewModel.kt 217-239), I read the line counters in the report: every line is covered, and the only missed branch is 1 of 6 on line 227.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-edit; base 384c5efc10df8d68b9bad30f76a154388de93b2d; HEAD bb5b65443410faa9108ae741e7c5c29833aca434; commits 5ef72d65, d4fad91e, f83a0341, 7b78b549 and bb5b6544, as given by the parent.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bb5b6544 with a clean working tree (no uncommitted inputs).
- Exact included files; renamed/deleted/excluded files and reasons: the five `calendar/edit/*.kt` files. The CalendarScreen.kt one-line hook is not reported separately. strings_calendar_edit.xml is resources and is not instrumented. Hilt-generated files are excluded as generated. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests (1227). Provider version not recorded.
- Run start/completion time and exit status: report written 2026-10-21 18:17:14 +0200, after the bb5b6544 commit (18:14:45). Exit status was success, as reported by the parent.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 ed098ae6cd0b3539129625f6b11a36c5394597ae351d0409e7b4126ca37d2ecf.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report postdates HEAD, nothing was changed since, and the tree is clean.
- Final run after latest test additions and production fixes: yes (bb5b6544 is the final code; no tests were added in this round).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventEditRepository.kt` | N/A | 33/38 (86.8%) | N/A | 80/85 (94.1%) |
| `calendar/edit/EventForm.kt` | N/A | 96/101 (95.0%) | N/A | 90/90 (100%) |
| `calendar/edit/CalendarWithEditor.kt` | N/A | 18/28 (64.3%) | N/A | 33/33 (100%) |
| `calendar/edit/EventEditorScreen.kt` | N/A | 81/87 (93.1%) | N/A | 258/261 (98.9%) |
| `calendar/edit/EventEditorViewModel.kt` | N/A | 162/188 (86.2%) | N/A | 178/178 (100%) |
| Aggregate (5 files above) | N/A | 390/442 (88.2%) | N/A | 639/647 (98.8%) |

Aggregates use summed counters. Not in the aggregate: the CalendarScreen.kt hook (not reported separately), strings_calendar_edit.xml (not instrumented) and the Hilt-generated files (excluded).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorViewModel.kt:227` (1 of 6 branches: `status == null` on a create) | CR-5: an unreadable answer to a create keeps the id and shows FAILED | Left uncovered: a defensive path. The message is the same as for any create failure, and keeping the id is the safe default. |
| `EventEditorViewModel.kt` other null-guard branches (`?: return`, timed `?: now`/`?: start` fallbacks) | Early-return guards for calls made while closed or without a prompt, and events without times (the API never sends one) | Partly covered by the EdgeCases guard tests. The rest are defensive branches. |
| `CalendarWithEditor.kt` (10 branches) | Compose `remember`/lambda null branches | Compose-generated or defensive branches. The three entry points (FAB today, FAB on the day shown, tap to edit) are tested. |
| `EventEditRepository.kt` (5 lines, 5 branches; DI/mapping glue) | Mapping fallbacks | The behavior (load, create, patch, re-read, error mapping) is covered by the repository tests. Non-material gap. |
| `EventEditorScreen.kt` (3 lines, 6 branches; picker/preview glue) | UI glue | The behavior paths are tested through the Compose tests (pickers, prompt, conflict, read-only). Non-material gap. |
| `EventForm.kt` (5 branches) | Validation/preset matching edge branches | Lines are fully covered. The remaining branches are compiler-generated or defensive. |

## Unable to test

- I could not run Gradle, coverage or `.taskcheck` myself: they are denied in this sub-agent session. All results above come from parent runs on bb5b6544, except `.taskcheck`, which was last run on 4ef0c96d. The parent should run `sh scripts/check.sh` on the final revision.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The 2 CR-5 tests cover the fix (FAILED for a refused create, the form stays editable, a new id after 409, the id kept otherwise). Existing tests guard the edit path, which still maps 409 to CONFLICT. No tests were added in this round.
