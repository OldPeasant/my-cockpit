# Test Report

Date: 2026-10-21

Task: backup-contrib-gtd - Backup contributor: GTD

Scope: task commits 848777b0, a558de14, d196772f, 27a89797 (review report only), 84ec1d2f on top of base 9cd736102b1fbe7ec196327604d18c645ef26674; measured HEAD 84ec1d2f2602c6fb9c0940455f0e1372f0672298 plus one uncommitted test addition (below). Production code: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/`.

Requirements:
- `tasks/backup-contrib-gtd.md` acceptance criteria and recorded assumptions
- SPEC.md §5, §10.2 (as referenced by the task and the contributor KDoc)
- `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions: check coverage of the criteria and of the latest change (84ec1d2f, `time_estimate_min` read as `(rs.getObject(...) as Number?)?.toInt()`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributorTests.kt`

## Tests added or updated

Count: 1

- Added `a time estimate of zero, a positive or large value or none is exported and restored as it was`: tasks with estimates 0, 90, 100000 and null. Checks `timeEstimateMin` in `data/tasks.jsonl` (null/absent for the task without one) and the restored `time_estimate_min` column after wipe and import. Before this, the contributor tests only checked one non-null estimate (15) on export. The import test restored null estimates only, and only the shared round-trip fixture (90) exercised a non-null estimate end to end.

The other 20 contributor tests and the round-trip test were already there and were reviewed against the criteria. They were not changed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: data/tasks.jsonl, projects.json, areas.json, contexts.json, checklist_items.jsonl | `tasks jsonl holds every task with its fields, sorted context ids and next occurrence, the Trash included`; `projects, areas, contexts and checklist items are exported with their fields, next steps and Trash state`; `an empty GTD exports empty data files and empty list mirrors, and imports again`; `import restores every row with its ids, times, versions, references and Trash state`; `import indexes tasks and projects for search, not the ones in the Trash`; new `a time estimate of zero, a positive or large value or none is exported and restored as it was` | `cd server && ./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck` | Pass |
| AC2: readable/tasks/{inbox,next,waiting,someday,logbook}.md and readable/projects/(area)/(project).md with checkboxes | `each list mirror holds its open tasks by position, without done or trashed ones`; `next md lists scheduled tasks in a section of their own, soonest first`; `waiting md shows whom a task waits for and since when`; `waiting md is ordered by waiting-since, tasks without a date last, then by position`; `a task's details, notes and checklist are written below its checkbox`; `logbook md holds the done tasks newest first, without trashed ones`; `a project mirror has its fields, description and tasks as checkboxes with the next step marked`; `a completed project without tasks shows its status and completion date, a blank description is left out`; `a due date without time and a waiting-for text outside the Waiting list are shown`; `projects without area go to No area, trashed projects and their tasks get no mirror`; the five file-name tests (separators/control chars, 100 code-point cut, Windows-forbidden chars, emoji cut, case-insensitive clashes) | same command | Pass |
| AC3: Round-trip test passes with GtdBackupFixture (tasks in every list, recurring tasks, projects with next steps, areas, contexts) | `BackupRoundTripTest.every contributor's data survives export, wipe and import unchanged` with `GtdBackupFixture` (Inbox, Next, Waiting, Someday, Scheduled, tickler, done, Trash, recurring FIXED with next occurrence, AFTER_COMPLETION, projects with next steps in an area and without one, completed, trashed, 2 areas, 2 contexts, checklist) | same command | Pass |
| Latest change 84ec1d2f: null and non-null time estimates round-trip | new `a time estimate of zero, a positive or large value or none is exported and restored as it was`; `BackupRoundTripTest` (fixture estimate 90 and null on the other tasks) | same command | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL in 1m 27s, exit code 0. JUnit XML: GtdBackupContributorTests tests=21, failures=0, errors=0, skipped=0. BackupRoundTripTest tests=1, failures=0, errors=0, skipped=0. ktlintMainSourceSetCheck and ktlintTestSourceSetCheck passed.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. The parent asked not to run it on this slow machine. The fixer ran it on d196772f (BUILD SUCCESSFUL, see `fixes.md`). Since then only 84ec1d2f (one line) and this test have changed, and both were compiled, linted and run above.

## .taskcheck

Result:

Not run (parent instruction; the parent runs `.taskcheck` before `done`).

## Bugs found

None.

## Coverage

Status: verified (package `ch.sonensei.cockpit.gtd.backup`, focused test selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate figure. Both files are new in this task (every line is a changed line), so whole-file line coverage here is the same as changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-gtd. Base 9cd736102b1fbe7ec196327604d18c645ef26674, HEAD 84ec1d2f2602c6fb9c0940455f0e1372f0672298. The parent supplied the commits 848777b0, a558de14, d196772f, 27a89797, 84ec1d2f and the scope; `git diff --stat 9cd73610..HEAD` shows only the task's `gtd/backup` files and `reviews/backup-contrib-gtd/`. This matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 before and after the run (unchanged, checked with `sha256sum -c`): GtdBackupContributor.kt e19f93ba8d7ac2f612518197d8f69547396bdebdb585c5bff688df74ed038937; GtdBackupRecords.kt 6adc903c84d0895eaacb3125cc10e7002c47cfe7280b09bf1e7389680d95ec8c; GtdBackupContributorTests.kt (uncommitted, +21 lines) c10728286375cfde0072eb24a2a1ee1cf6a30f004d87c5f55b2085e9d2924176; GtdBackupFixture.kt cbc86075c608315d3b4697b21065ef3a438e642b6766e27807996861cf45fb16.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt` and `GtdBackupRecords.kt`. No renames or deletions. Excluded: test files and `reviews/`. The JaCoCo package also lists `BackupContributor.kt` and `Comparisons.kt` with no counters. These are inline-function source mappings from other packages, not task files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version, Gradle 9.8.1, OpenJDK 21.0.12.1). Command `./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck`, run in `server/`. Selection: those two test classes only.
- Run start/completion time and exit status: started about 2026-10-21T08:22:30+02:00. test.exec was written at 08:23:51 and the report at 08:24:06. Exit code 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 c2866b95f39847c66fbf048855015495c88747ebb7742fbd62dd77c97f2bee6e (sessioninfo id dell26-f7365a62); `server/build/jacoco/test.exec` sha256 d2f8ca440fd8fcf0773afaa262aeccb13c1a8b6a73abfac2a66e1cc30406a247.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test sources changed, so Gradle re-executed `test` (9 tasks executed). The exec file and report timestamps fall inside this run, and the snapshot hashes match after the run.
- Final run after latest test additions and production fixes: yes (it includes 84ec1d2f and the new test).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupContributor.kt` | 1575/1586 (99.3%) | 69/72 (95.8%) | 32/32 (100%) | 245/245 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup/GtdBackupRecords.kt` | 1045/1045 (100%) | 84/84 (100%) | 83/83 (100%) | 126/126 (100%) |
| Aggregate (summed counters, both files, none unmeasured) | 2620/2631 (99.6%) | 153/156 (98.1%) | 115/115 (100%) | 371/371 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GtdBackupContributor.kt:110, 121, 133` (1 of 2 branches each) | The `null` branch of `reader.json(...).orEmpty()` when `areas`/`contexts`/`projects` is missing from a backup. No criterion requires this; a backup written by this contributor always has all three files (the empty-GTD test checks they are written even when empty). | Left uncovered. Defensive tolerance for a missing data file. Testing it would need a hand-made ZIP without those entries, and no criterion covers that. |
| `GtdBackupContributor.kt:323` (5 instructions partly missed) | The exception-path bytecode of `Stream.use { }` (closing the stream when the block throws). | Left uncovered. Compiler-generated cleanup path; the normal close is covered. |

## Unable to test

- Full `./gradlew check` and `.taskcheck` were not run here (parent instruction, slow machine); see above.

## Summary

Tests added and all validation passed. The acceptance criteria were already well covered. One focused test was added for the latest change (time estimates 0, 90, 100000 and null through export and import). Task-scoped coverage is 100% of lines and 98.1% of branches; the only gaps are defensive or compiler-generated paths. No production defects were found.
