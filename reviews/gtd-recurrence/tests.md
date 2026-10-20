# Test Report

Date: 2026-10-20

Task: gtd-recurrence - Recurring tasks

Scope: task commits `a2a9d346fb51ae2a0406191b8e3bb589f58cf497..cf49e8eb1503b3e8fb29ab1eab3e8d057809f42c` (one commit, `cf49e8e gtd-recurrence: RRULE validation and next occurrence on completion`), on branch `main`, plus the uncommitted test changes of this report (listed below). No uncommitted production changes.

Requirements:
- `tasks/gtd-recurrence.md` acceptance criteria and recorded assumptions (`python tasks.py assumptions`, gtd-recurrence #1-#5)
- `SPEC.md` §5.2 (task data model: `recurrence_rule`, `recurrence_mode`)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` project notes
- Parent instructions (gaps to probe: trashed project, trashed next occurrence then uncomplete/recomplete, If-Match on complete, search index of the new occurrence)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskRecurrenceEdgeCaseTests.kt` (new, Postgres via Testcontainers)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskRecurrenceTests.kt` (extended, no database)

## Tests added or updated

Count: 16 (11 new in `TaskRecurrenceEdgeCaseTests`, 5 new in `TaskRecurrenceTests`; no existing test changed)

New in `TaskRecurrenceEdgeCaseTests`:
- `a task whose project is in the Trash completes, and its next occurrence has no project`
- `with the next occurrence in the Trash, uncompleting and completing again creates no other one`
- `the next occurrence can be purged from the Trash, which clears the link to it`
- `a completed recurring task can be moved to the Trash and purged while its next occurrence stays`
- `a stale If-Match on complete is 412 and creates no occurrence, a current one completes`
- `the next occurrence is in the search index and announced as CREATED`
- `a FIXED task with only a scheduled date moves that date and gets no due date`
- `a FIXED task without dates is next due after the completion day in the user's zone`
- `a FIXED weekly Monday task due on a Wednesday is next due on the following Monday`
- `an AFTER_COMPLETION task moves its due and scheduled dates by the same number of days`
- `PATCH can clear rule and mode together, but not the rule alone`

New in `TaskRecurrenceTests`:
- `a COUNT is matched case-insensitively and a COUNT of 1 is valid`
- `a COUNT series yields its next date while occurrences remain`
- `an UNTIL with a UTC time, as calendar clients write it, is accepted and ends the series by day`
- `a rule with a time of day part ical4j cannot expand over days is rejected`
- `the RRULE prefix is rejected in any case`

## Requirement coverage

Command for all rows: `cd server && ./gradlew cleanTest cleanJacocoTestReport test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck` (Postgres 16 via Testcontainers).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `recurrenceRule` validated via ical4j, `recurrenceMode` FIXED/AFTER_COMPLETION | existing: `POST and PATCH accept a valid RRULE with a mode`, `invalid rules and a rule without a mode or a mode without a rule are 400`, `day-based rules are valid`, `invalid rules, the RRULE prefix, sub-daily frequencies and COUNT=0 are rejected`; new: `PATCH can clear rule and mode together, but not the rule alone`, `a rule with a time of day part ical4j cannot expand over days is rejected`, `an UNTIL with a UTC time, ...`, `the RRULE prefix is rejected in any case`, `a COUNT is matched case-insensitively ...` | see above | Pass |
| AC2: exactly one next occurrence, FIXED from the current due date | existing: weekly Monday FIXED, `a FIXED task completed late keeps its schedule ...`, copy-fields test; new: `a FIXED weekly Monday task due on a Wednesday ...`, `a FIXED task with only a scheduled date ...`, `a FIXED task without dates ...` | see above | Pass |
| AC2: AFTER_COMPLETION relative to the completion date in the user's zone | existing: late AFTER_COMPLETION, completed on Wednesday, DST spring/autumn; new: `an AFTER_COMPLETION task moves its due and scheduled dates ...`, `a FIXED task without dates is next due after the completion day in the user's zone` | see above | Pass |
| AC2: no pre-generation | existing: weekly Monday case (`taskCount() shouldBe 2` after one completion), `completing a task without a rule creates nothing`, COUNT/UNTIL test | see above | Pass |
| AC2: uncomplete does not create duplicates | existing: `uncompleting and completing again creates no duplicate, nor does completing a done task`; new: `with the next occurrence in the Trash, uncompleting and completing again creates no other one`, `a stale If-Match on complete is 412 and creates no occurrence ...` | see above | Pass |
| Assumption #1: copy of fields, project only if still active, search/events | new: `a task whose project is in the Trash completes ...`, `the next occurrence is in the search index and announced as CREATED` | see above | Pass |
| Assumption #3: `next_occurrence_id` column / FK behavior with the Trash | new: purge of the next occurrence (FK `on delete set null`), purge of the completed original | see above | Pass |
| AC3: gtd tests pass incl. weekly Monday, late AFTER_COMPLETION, DST boundary | whole `ch.sonensei.cockpit.gtd.*` suite: 13 suites, 192 tests, 0 failures, 0 errors, 0 skipped | see above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.TaskRecurrence*'` (and `--tests 'ch.sonensei.cockpit.gtd.TaskRecurrenceTests'` while iterating)

Result:

Pass (TaskRecurrenceEdgeCaseTests 11, TaskRecurrenceTests 10, TaskRecurrenceWebTests 15; 0 failures). Two of my own first assertions were wrong and were corrected: `remainingRule` writes `COUNT=` in upper case, and ical4j accepts an `UNTIL` with a UTC time; neither contradicts a requirement.

## Full test execution

Command:

`cd server && ./gradlew cleanTest cleanJacocoTestReport test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck`

Result:

Pass (BUILD SUCCESSFUL; 13 suites, 192 tests, 0 failures/errors/skipped; ktlint clean after reformatting one chained call in the new test file).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "all checks passed" (server `./gradlew check` with all 143 server test suites green, web `npm run check`; android SKIPPED by the script via `COCKPIT_NO_ANDROID=1`, which is the configured behavior on this machine).

## Bugs found

None. No test written for the task's requirements failed against the production code.

Observations (no requirement violated, no failing test; for the parent to decide):
- TW-1 (Low, from reading the code, not tested): `complete()` re-validates the task via `modify()`, so a task stored before this task's validation existed with a rule but no mode, or an invalid rule (the old edge test stored `"r".repeat(500)`), can no longer be completed (400 on `recurrenceRule`/`recurrenceMode`). This only matters for pre-existing data; `TaskService.kt` `createNextOccurrence` line 285 (`recurrenceMode ?: return null`) is the defensive branch for that case.
- TW-2 (Low, tested): purging the next occurrence from the Trash sets `next_occurrence_id` back to null (FK `on delete set null`), so uncompleting and completing the original again after a purge would create a new occurrence. Assumption #3 does not cover the purge case; trashing (without purging) keeps the link, and no duplicate is created then (tested).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from `git diff -U0 a2a9d346..HEAD` per scoped file, intersected with the JaCoCo XML `line` elements (attribute `nr`) of that source file (only lines JaCoCo reports as executable); a line is covered when `ci > 0`. Branch counts on those lines are JaCoCo's `cb`/`mb` for the same lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-recurrence`; base `a2a9d346fb51ae2a0406191b8e3bb589f58cf497` (given by the parent); HEAD `cf49e8eb1503b3e8fb29ab1eab3e8d057809f42c`; one task commit `cf49e8e` (`git log a2a9d346..HEAD`), matching the files the parent named and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production files equal HEAD (`git diff --quiet HEAD -- server/src/main` before and after the run); `TaskRecurrenceEdgeCaseTests.kt` sha256 `d8ce749196fd21de1c5598cc6acc561002644271766d096faa480488d87eda02`, `TaskRecurrenceTests.kt` sha256 `108785aee087aee24ad8089579c7845c7aa2c61bf7cd7bf16eb536b3d74c7e10` (same hashes before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt` (new), `.../gtd/domain/TaskService.kt` (modified), `.../gtd/domain/Task.kt` (modified). Excluded: `server/src/main/resources/db/migration/gtd/V202610201830__gtd_task_next_occurrence.sql` (SQL, not instrumented by JaCoCo; exercised by every Testcontainers test via Flyway and by the purge tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (tool version not pinned in `server/build.gradle.kts`, Gradle default); `./gradlew cleanTest cleanJacocoTestReport test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck` in `server/`; selection `ch.sonensei.cockpit.gtd.*`.
- Run start/completion time and exit status: started after 2026-10-20T18:19:47Z, completed by 2026-10-20T18:20:23Z; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `278c2aba752d0f85ea9a698e8d77e9ed15e20ea78eab9b5fd65d69a384168fef`, JaCoCo session `R1963MACOS.local-498acd81`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (`cleanTest` and `cleanJacocoTestReport` in the same invocation; report mtime 20:20 local; snapshot hashes unchanged).
- Final run after latest test additions and production fixes: yes (no production fixes; run after the last test change).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no separate statement counter; instruction counts are shown in the Statements column.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt` | 232/241 (96.3%) | 27/28 (96.4%) | 7/7 (100%) | 19/19 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt` | 2078/2088 (99.5%) | 260/263 (98.9%) | 36/36 (100%) | 235/235 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/Task.kt` | 185/185 (100%) | 2/2 (100%) | 44/44 (100%) | 28/28 (100%) |
| Aggregate (3 measured files; migration SQL excluded, not instrumentable) | 2495/2514 (99.2%) | 289/293 (98.6%) | 87/87 (100%) | 282/282 (100%) |

Changed-line coverage: `TaskRecurrence.kt` 19/19 lines, 27/28 branches; `TaskService.kt` 47/47 lines, 53/54 branches; `Task.kt` 1/1 lines, 0/0 branches (N/A). Total 67/67 changed executable lines, 80/82 branches on changed lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `TaskRecurrence.kt:23` (probe `getNextDate` failure) | rules ical4j parses but cannot expand over days | covered now by `a rule with a time of day part ical4j cannot expand over days is rejected` |
| `TaskRecurrence.kt:36`, 1 of 4 branches (`runCatching` failure inside `nextDate`) | defensive: a rule that passed `problem()` cannot fail here | justified gap; validated rules never reach it |
| `TaskService.kt:285` (`recurrenceMode ?: return null`) | defensive: rule without mode is rejected by validation | justified gap; only reachable with pre-existing data, see TW-1 |
| `TaskService.kt` other 2 missed branches | outside the task's changed lines (pre-existing code) | out of scope |

## Unable to test

- TW-1 (completing pre-existing tasks with a rule but no mode / an invalid rule): not tested, because the expected behavior is not specified; reported as an observation.
- The Android part of `.taskcheck` was skipped by the script on this machine (`COCKPIT_NO_ANDROID=1`); the task touches no Android code.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
