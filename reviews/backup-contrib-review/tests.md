# Test Report

Date: 2026-10-26

Task: backup-contrib-review - Backup contributor: weekly reviews

Scope: `a4a2ef2727eed76417bdc7ea21cb2922cee586d4..3c3d5d0558b87db99d85bbb6cf57e2350be46650` on `main`. The parent supplied the base and the two task commits: d3e07c4e4 adds `ReviewBackupContributor.kt` and `ReviewBackupFixture.kt`, and 3c3d5d055 adds `ReviewBackupContributorTests.kt`. Those 11 tests came from an earlier test-writer run that stopped before writing its report. Only `reviews/backup-contrib-review/` was uncommitted.

Requirements:
- `tasks/backup-contrib-review.md`: both acceptance criteria and the recorded assumption (`data/weekly_reviews.jsonl`, no readable mirror, a backup with more than one open review or an unknown step is rejected at upload and import)
- SPEC.md §8.6 and §14, and §10.2 as modified by the assumption
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Parent instructions: check the existing tests against the criteria, add tests only for real gaps, measure coverage for ReviewBackupContributor

## Test files changed

- None. I reviewed the existing tests (`server/src/test/kotlin/ch/sonensei/cockpit/review/backup/ReviewBackupContributorTests.kt`, `ReviewBackupFixture.kt`) and left them unchanged.

## Tests added or updated

Count: 0. The 11 existing tests in 3c3d5d055 cover every criterion and every changed behavior. I found no real gap: line and branch coverage of the production file is 100%, and each branch is tested on purpose by a named test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: export writes `data/weekly_reviews.jsonl` with id, startedAt, completedAt, step, createdAt/updatedAt and version, ordered by id; an open review has `completedAt` null | `weekly reviews jsonl holds every review by id with all fields, the open one with a null completedAt` | `server/gradlew -p server test --rerun jacocoTestReport ktlintCheck` | Pass |
| AC1 / assumption: the file is present when there are no reviews; no readable mirror | `weekly reviews jsonl is present and empty when there are no reviews` | same | Pass |
| AC1: restore keeps ids, times, versions, steps and completion exactly | `import restores every review exactly, with its id, times, version, step and completion` | same | Pass |
| AC1: after a restore the open review is current, the last completed review is right, and `start` resumes the open review instead of creating a second one | `after a restore the open review is current, the last completed one is right, and starting resumes it`; `after a restore of only completed reviews none is open and a new one can be started` | same | Pass |
| AC1 / assumption: more than one open review is rejected at upload (`RestoreService.validate`, `restoreWarnings`) and at import, and nothing is restored | `a backup with two open reviews is reported invalid at upload and its import fails without restoring any` | same | Pass |
| Assumption: an unknown step is rejected at upload and at import, and nothing is restored | `a backup with an unknown step is reported invalid at upload and its import fails without restoring any`; `every problem of an invalid backup is reported at once` (lowercase and empty step names, problems collected together) | same | Pass |
| Every valid `ReviewStep` name is accepted (no false rejection) | `every review step name is accepted`; `a valid backup with one open and several completed reviews has no restore warnings or problems` | same | Pass |
| A backup without `weekly_reviews.jsonl` (e.g. an older version) is valid and imports nothing | `a backup without weekly reviews restores none and is valid` | same | Pass |
| AC2: `./gradlew test --tests '*BackupRoundTripTest'` passes with `ReviewBackupFixture` seeding an open review and completed reviews | `BackupRoundTripTest` (1 test, which uses `ReviewBackupFixture`) | same (full suite); the parent also ran it alone | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*ReviewBackupContributorTests' --tests '*BackupRoundTripTest'` (run by the parent before this session)

Result:

Pass (11/11 and 1/1). The same tests passed again in the full run below: `TEST-ch.sonensei.cockpit.review.backup.ReviewBackupContributorTests.xml` 11 tests, 0 failures; `TEST-ch.sonensei.cockpit.backup.BackupRoundTripTest.xml` 1 test, 0 failures.

## Full test execution

Command:

`server/gradlew -p server test --rerun jacocoTestReport ktlintCheck` (from the repo root)

Result:

Pass. Exit code 0, BUILD SUCCESSFUL in 6m 43s. The JUnit XML results show 4776 tests, 0 failures, 0 errors and 0 skipped. `ktlintCheck`, which covers the main and test source sets including the task's test files, passed.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` exited 0 and ended with "all checks passed" (server, web and android).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. The only production file in scope is new in this task, so every executable line in it is a changed line. Whole-file and changed-line coverage are therefore the same here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-contrib-review`. Base `a4a2ef2727eed76417bdc7ea21cb2922cee586d4`, measured HEAD `3c3d5d0558b87db99d85bbb6cf57e2350be46650`. The parent supplied the task commits d3e07c4e4 and 3c3d5d055. I confirmed the scope with `git diff --name-status a4a2ef27..HEAD`: it lists only the three files above, all added.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 3c3d5d055 with no uncommitted source or test changes. sha256 values: `ReviewBackupContributor.kt` d0d4d682…a8ea1, `ReviewBackupContributorTests.kt` 25fbbbfe…dd67d6, `ReviewBackupFixture.kt` e3356382…5c8e. The hashes were identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/review/backup/ReviewBackupContributor.kt`. `ReviewBackupFixture.kt` is excluded because it is test code under `src/test`. JaCoCo also lists a `BackupContributor.kt` source file under package `ch/sonensei/cockpit/review/backup`. That entry holds the interface default methods from `backup/domain/BackupContributor.kt`, which the compiler copied into the review class. The file is not task-owned, so it is excluded. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (plugin default version, Gradle 9.8.1). Command `server/gradlew -p server test --rerun jacocoTestReport ktlintCheck`, run from the repo root `/home/eric/work/my-cockpit-agents/agent-2`. Test selection: the full server suite, forced to re-execute with `--rerun`.
- Run start/completion time and exit status: 2026-10-26T12:14:52+01:00 to 2026-10-26T12:21:36+01:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, written 2026-10-26 12:21:36.598 +0100, sha256 d17d7f070372705e2e909e38dffa0d196c6a426a13b169d23069c152b5e5db24. The HTML report is in `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task to execute, the report was written at the end of this run, and the source and test hashes did not change.
- Final run after latest test additions and production fixes: yes. No tests were added and production code has not changed since 3c3d5d055.

Values below use covered/total (percentage) from the JaCoCo per-sourcefile counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/review/backup/ReviewBackupContributor.kt` | 269/269 (100%) | 12/12 (100%) | 16/16 (100%) | 50/50 (100%) |

Aggregate (one file): the same as the row above. No files in scope are unmeasured. The excluded files and the reasons are listed above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | none | none |

High coverage here does not stand in for behavior checks. Each branch in `validReviews` has its own assertion: unknown step, more than one open review, both problems together, and no problem. The `completedAt` null and non-null paths are checked in both export and import.

## Unable to test

- Nothing from the acceptance criteria. Restore through the HTTP upload endpoint is not tested here. That is backup-core behavior, and these tests reach the same path through `RestoreService.validate`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
