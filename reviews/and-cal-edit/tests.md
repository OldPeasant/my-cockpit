# Test Report

Date: 2026-10-21

Task: and-cal-edit - Android event create/edit

Scope: task commits on base a75919afc79e9d4d5e8316d864477b49d5f3c5c6, given by the parent as d82af939, 54268ba5 and 746e3979. They were later rebased onto main as 6239cdda, af74b6b4 and 6f8e592d (HEAD 6f8e592d72399c315925dacc481b7838b80a64f5, rebased onto c06d6cfc). `git diff 746e3979 HEAD` restricted to the task paths is empty, so the task's code is identical in both. Included production files: `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/{CalendarWithEditor,EventEditRepository,EventEditorScreen,EventEditorViewModel,EventForm}.kt`, `android/app/src/main/res/values/strings_calendar_edit.xml` (resources, not instrumented) and the one-line hook in `calendar/agenda/CalendarScreen.kt` (`git diff c06d6cfc HEAD`). Excluded: `reviews/` (documentation) and the Hilt-generated `*_Factory`/`*_HiltModules*` Java files, which are generated.

Requirements:
- tasks/and-cal-edit.md acceptance criteria and recorded assumptions
- reviews/and-cal-edit/review.md (CR-1..CR-4) and reviews/and-cal-edit/fixes.md
- AGENTS.md, docs/ARCHITECTURE.md conventions (fictitious test data, ktlint)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModelEdgeCasesTest.kt` (uncommitted, sha256 74e58ebeaa5b01470abc96b163d95eba4112512b7ac544ce8d67a1ee74806d00)

## Tests added or updated

Count: 3 (added; no existing test changed)

- `EventEditorViewModelEdgeCasesTest.a single all-day event shows its stored days with the last day inclusive, and is sent back exclusive`
- `EventEditorViewModelEdgeCasesTest.a one-day all-day event ends on its first day, also when its end is missing or not after the start`
- `EventEditorViewModelEdgeCasesTest.an override without a zone of its own uses the zone of its series`

They close the remaining CR-4 gaps: the non-generated all-day path of `editForm` (lines 353-355), the `maxOf(..., first)` guard and the missing-end fallback, and the `master?.timeZone` fallback (line 333).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 event form: fields, validation | EventFormTest (17), EventEditorViewModelTest `an invalid form shows its errors and is not sent`, EventEditorScreenTest `validation messages show once a save was tried`, `the form shows the event and edits its fields` | parent: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` on 746e3979 | Pass (178 tests, 0 failures) |
| AC1 recurrence presets (+ kept custom rule, CR-3) | EventFormTest `presets are FREQ rules...`, `presets are matched regardless of case...`; VM `a custom rule stays a choice after a preset was picked...`, `a preset rule offers no custom choice`; Screen `the repeat presets are offered`, `a custom rule of a series is kept as a choice` | same | Pass |
| AC1 THIS/FOLLOWING/ALL prompt | VM `a series occurrence ... asks THIS, FOLLOWING or ALL`, `a new repeat rule offers only FOLLOWING and ALL...`; EdgeCases `the scope prompt ignores a second save...`; Screen `the scope prompt offers the given scopes`, `a new repeat rule leaves THIS out of the prompt`; Repository patch scope/occurrence tests | same | Pass |
| AC2 Robolectric/Compose screen tests | EventEditorScreenTest (17), CalendarWithEditorTest (3) | same | Pass |
| CR-1 re-read of generated occurrences | VM `...moved since it was shown...`, `...became an override...`, `...no longer has says it is gone...`, `...can't be read again...`, `a save answered with 404...`; Repository re-read tests (3); Screen `an occurrence that is gone...` | same | Pass |
| CR-2 close ignored while saving | EdgeCases `closing while a save is running is ignored...`, `closing after a failed save closes the editor`; Screen `while a save runs the editor can't be closed`, `no answer while saving asks to try again...` | same | Pass |
| CR-4 editForm: all-day generated occurrence, override + master, master-load failure, invalid zone | EdgeCases `a generated all-day occurrence shows its own days...`, `an override shows its own times and the rule of its series...`, `a series that can't be loaded fails the load...`, `an unknown zone falls back to the device zone...` | same | Pass |
| CR-4 remainder: all-day single event, one-day/degenerate all-day end, override zone from series | the 3 new tests above | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'` | Not run: Gradle is denied in this sub-agent session, so the parent must run it |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.*'` (run by the parent on 746e3979, before the 3 new tests)

Result:

Pass: BUILD SUCCESSFUL, 178 tests, 0 failures. Not run for the 3 new tests (Gradle denied here).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (run by the parent on 746e3979)

Result:

Pass: 1222 Android unit tests, 0 failures (before the 3 new tests).

## .taskcheck

Result:

Not run (Gradle and `sh scripts/check.sh` are not available to this sub-agent).

## Bugs found

None. Reading `editForm` against the new cases gives the expected results: the stored exclusive end minus one day, `maxOf` for an end that is not after the start, and `first + 1` for a missing end. No production defect is suspected. This has not been confirmed by running the tests.

## Coverage

Status: partial. Values come from a fresh parent run on 746e3979, whose task files are identical to HEAD 6f8e592d. They do not include the 3 tests added here.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (every task file is new, so whole-file coverage is close to changed-line coverage, except for the one-line CalendarScreen.kt hook).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-cal-edit; base a75919afc79e9d4d5e8316d864477b49d5f3c5c6; measured code 746e3979 (equal to 6f8e592d for the task paths); commits and scope from the parent, checked with `git diff --stat c06d6cfc HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6f8e592d plus the modified EdgeCases test (sha256 above). The coverage run predates that modification.
- Exact included files; renamed/deleted/excluded files and reasons: the five `calendar/edit/*.kt` files. CalendarScreen.kt (a one-line hook) was not reported separately by the parent. strings_calendar_edit.xml is resources and is not instrumented. Hilt-generated Java files are excluded as generated.
- Provider/version, command, working directory, and test selection: JaCoCo through `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests; provider version not recorded.
- Run start/completion time and exit status: completed 2026-10-21 18:05:40 +0200 (report mtime), exit success as reported by the parent.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 c9ea64d0212a9cbd27ca84c7fc33fab61a3143b6b7ba1e85fdd6172165c6b425. I re-read it, and its counters match the numbers below.
- Evidence outputs are fresh and inputs stayed unchanged: fresh for 746e3979 production code. Test inputs have changed since (3 tests added).
- Final run after latest test additions and production fixes: no. The parent should rerun the coverage command after running the new tests.

| Changed file | Statements (instructions) | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `calendar/edit/EventEditRepository.kt` | 512/546 (93.8%) | 33/38 (86.8%) | 32/33 (97.0%) | 80/85 (94.1%) |
| `calendar/edit/EventForm.kt` | 929/945 (98.3%) | 95/101 (94.1%) | 32/32 (100%) | 90/90 (100%) |
| `calendar/edit/CalendarWithEditor.kt` | 488/508 (96.1%) | 18/28 (64.3%) | 6/6 (100%) | 33/33 (100%) |
| `calendar/edit/EventEditorScreen.kt` | 3904/4093 (95.4%) | 81/87 (93.1%) | 88/108 (81.5%) | 258/261 (98.9%) |
| `calendar/edit/EventEditorViewModel.kt` | 1532/1611 (95.1%) | 150/178 (84.3%) | 42/43 (97.7%) | 175/175 (100%) |
| Aggregate (5 files above) | 7365/7703 (95.6%) | 377/432 (87.3%) | 200/222 (90.1%) | 636/644 (98.8%) |

Aggregates use summed counters. Not in the aggregate: CalendarScreen.kt hook (not reported separately), strings_calendar_edit.xml (not instrumented) and the Hilt-generated files (excluded).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `EventEditorViewModel.kt:353-358` (all-day non-generated path, null-time fallbacks) | AC1: editing a stored all-day event, off-by-one on the exclusive end (CR-4) | 3 new tests added (above). The timed `?: ZonedDateTime.now` / `?: start` fallbacks need an event with no times; the API never sends one, so this is a defensive gap. |
| `EventEditorViewModel.kt:333` (zone from master) | the override's zone | new test `an override without a zone of its own uses the zone of its series` |
| `EventEditorViewModel.kt:65-85,125-184,242` (null-guard branches such as `?: return`) | early-return guards for calls made while closed or without a prompt | Partly covered by the EdgeCases guard tests. The rest are defensive branches, so no tests were added. |
| `CalendarWithEditor.kt:29,53,57` (10 branches) | Compose `remember`/lambda null branches | Compose-generated or defensive branches. The three entry points (FAB today, FAB on the day shown, tap to edit) are tested. |
| `EventEditRepository.kt:83,104,142-153,168,196-197` | 1 method uncovered (line 142-153 block) and the DI/mapping fallbacks | The uncovered method is in the DI/mapping glue. The behavior (load, create, patch, re-read, error mapping) is covered by 11 repository tests. This is a non-material gap. |
| `EventEditorScreen.kt:59-66,204-235` (20 uncovered lambdas: picker/preview glue) | UI glue | The behavior paths are tested through the Compose tests (pickers confirm/cancel, prompt, conflict, read-only). The rest is non-material. |

## Unable to test

- I could not run the 3 new tests, ktlint, coverage or `.taskcheck`, because Gradle is denied in this sub-agent session. The parent must run `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'` and then `./gradlew createDebugUnitTestCoverageReport` before signing off.

## Summary

Tests added. Validation of the new tests is pending (parent to run). The earlier suite and coverage passed. CR-4 is now covered: the existing EdgeCases tests cover the all-day generated occurrence, override with master, master-load failure and invalid zone, and the 3 new tests cover the all-day single event, the degenerate all-day end and the override's zone fallback. No production defects found.
