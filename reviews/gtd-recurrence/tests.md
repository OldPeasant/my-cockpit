# Test Report

Date: 2026-10-20

Task: gtd-recurrence - Recurring tasks

Round: 2 (final revision `288b6ddbe03c313a1ef573bab7574f3facc38058`).

Scope: task commits `c4fcbf7955734df6ca768627aff7791b38755d70..288b6ddbe03c313a1ef573bab7574f3facc38058` on branch `main` (base = `git merge-base HEAD origin/main`, after the rebase on main): `e2da2ae` implementation, `39d9765` edge-case tests and round-1 report, `d12cd98` review report, `288b6dd` fix CR-1. No uncommitted production or test changes; only this report is modified.

Round 2 change: `288b6dd` changes only one comment line in `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt` (the `PROBE_DAY` comment now names a time-of-day part like `BYHOUR=9` as the example, see `reviews/gtd-recurrence/fixes.md`). No executable code changed, so no new tests are needed. The behavior the new comment describes is already tested by `a rule with a time of day part ical4j cannot expand over days is rejected`. All round-1 tests were re-run on the final revision (results below). Round 1 measured `cf49e8e` on the old base `a2a9d346`; after the rebase that commit is `e2da2ae`.

Requirements:
- `tasks/gtd-recurrence.md` acceptance criteria and recorded assumptions (`python tasks.py assumptions`, gtd-recurrence #1-#5)
- `SPEC.md` §5.2 (task data model: `recurrence_rule`, `recurrence_mode`)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` project notes
- Parent instructions (gaps to probe: trashed project, trashed next occurrence then uncomplete/recomplete, If-Match on complete, search index of the new occurrence)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskRecurrenceEdgeCaseTests.kt` (new, Postgres via Testcontainers)
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/TaskRecurrenceTests.kt` (extended, no database)

## Tests added or updated

Count: 16 in round 1 (11 new in `TaskRecurrenceEdgeCaseTests`, 5 new in `TaskRecurrenceTests`; no existing test changed). Round 2: 0 (comment-only change).

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

Command for all rows: `cd server && ./gradlew cleanTest cleanJacocoTestReport test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck` (Postgres 16 via Testcontainers), re-run in round 2 on `288b6dd`.

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

Round 2 on `288b6dd`: Pass (BUILD SUCCESSFUL in 20s; 13 suites, 192 tests, 0 failures/errors/skipped; ktlintCheck passed, its tasks UP-TO-DATE because their inputs did not change since the last passing run).

Round 1: Pass (BUILD SUCCESSFUL; 13 suites, 192 tests, 0 failures/errors/skipped; ktlint clean after reformatting one chained call in the new test file).

## .taskcheck

Result:

Round 2 on `288b6dd`: Pass. `sh scripts/check.sh` printed "all checks passed" (server and web checks; android SKIPPED by the script via `COCKPIT_NO_ANDROID=1`, which is the configured behavior on this machine).

Round 1: Pass. `sh scripts/check.sh` printed "all checks passed" (server `./gradlew check` with all 143 server test suites green, web `npm run check`; android skipped in the same way).

## Bugs found

None. No test written for the task's requirements failed against the production code.

Observations (no requirement violated, no failing test; for the parent to decide):
- TW-1 (Low, from reading the code, not tested): `complete()` re-validates the task via `modify()`, so a task stored before this task's validation existed with a rule but no mode, or an invalid rule (the old edge test stored `"r".repeat(500)`), can no longer be completed (400 on `recurrenceRule`/`recurrenceMode`). This only matters for pre-existing data; `TaskService.kt` `createNextOccurrence` line 285 (`recurrenceMode ?: return null`) is the defensive branch for that case.
- TW-2 (Low, tested): purging the next occurrence from the Trash sets `next_occurrence_id` back to null (FK `on delete set null`), so uncompleting and completing the original again after a purge would create a new occurrence. Assumption #3 does not cover the purge case; trashing (without purging) keeps the link, and no duplicate is created then (tested).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from `git diff -U0 c4fcbf79..HEAD` per scoped file (round 1: `a2a9d346..HEAD`), intersected with the JaCoCo XML `line` elements (attribute `nr`) of that source file (only lines JaCoCo reports as executable); a line is covered when `ci > 0`. Branch counts on those lines are JaCoCo's `cb`/`mb` for the same lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-recurrence`; base `c4fcbf7955734df6ca768627aff7791b38755d70` (`git merge-base HEAD origin/main`, as the parent instructed, after the rebase on main); measured HEAD `288b6ddbe03c313a1ef573bab7574f3facc38058`; task commits `e2da2ae`, `39d9765`, `d12cd98`, `288b6dd` (`git log c4fcbf79..HEAD`). Their production files are the same three Kotlin files plus the migration as in round 1, which matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): the working tree is clean under `server/` (`git diff --quiet HEAD -- server` -> clean), so the snapshot is commit `288b6dd`. sha256 before and after the run (identical): `TaskRecurrence.kt` `c9ada1c8fab15971a34627fc4092ccc660dd469ad3a4004db02d6a5d7291446c`, `TaskService.kt` `1c33667a4efea487b810d261eb7d8d0a04ddf52e7467e646d55bd4278192fd24`, `Task.kt` `58cec0120646904f03e0f9d4b3a820e5fa8a9c4dbf7bf2687a0162ca0faff8f6`, `TaskRecurrenceEdgeCaseTests.kt` `d8ce749196fd21de1c5598cc6acc561002644271766d096faa480488d87eda02`, `TaskRecurrenceTests.kt` `108785aee087aee24ad8089579c7845c7aa2c61bf7cd7bf16eb536b3d74c7e10`, `TaskRecurrenceWebTests.kt` `47fb4b4541a51bcfe375e05243c1c6f889ab7b581cc703a8d56e39eca34b7d77`.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskRecurrence.kt` (new), `.../gtd/domain/TaskService.kt` (modified), `.../gtd/domain/Task.kt` (modified). Excluded: `server/src/main/resources/db/migration/gtd/V202610201830__gtd_task_next_occurrence.sql` (SQL, not instrumented by JaCoCo; exercised by every Testcontainers test via Flyway and by the purge tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (tool version not pinned in `server/build.gradle.kts`, Gradle default); `./gradlew cleanTest cleanJacocoTestReport test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck` in `server/`; selection `ch.sonensei.cockpit.gtd.*`.
- Run start/completion time and exit status (round 2): started after 2026-10-20T18:28:51Z, completed at about 2026-10-20T18:29:21Z (BUILD SUCCESSFUL in 20s, exit 0).
- Generated report paths and report hashes/run identifier (round 2): `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `b446b540bd4cb53bbc6dc2fcad597ba1ff4c0e55e53b32e02632eff295c1268f`, JaCoCo session `R1963MACOS.local-a1eaa9c7` (round 1: session `R1963MACOS.local-498acd81`, sha256 `278c2aba...`).
- Evidence outputs are fresh and inputs stayed unchanged: yes (`cleanTest` and `cleanJacocoTestReport` ran in the same invocation; report mtime 20:29 local; snapshot hashes unchanged).
- Final run after latest test additions and production fixes: yes. It ran on the final revision `288b6dd`, after the CR-1 fix and the rebase. The counters are identical to round 1, as expected for a comment-only change.

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
| `TaskService.kt:101` (the other 2 missed branches) | outside the task's changed lines (pre-existing code) | out of scope |

## Unable to test

- TW-1 (completing pre-existing tasks with a rule but no mode / an invalid rule): not tested, because the expected behavior is not specified; reported as an observation.
- The Android part of `.taskcheck` was skipped by the script on this machine (`COCKPIT_NO_ANDROID=1`); the task touches no Android code.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Round 2 confirms this for the final revision `288b6dd`: the CR-1 fix only changes a comment and needs no tests, and the gtd suite, ktlint, coverage and `.taskcheck` all passed on that revision.
