# Code Review

Date: 2026-10-21

Task: backup-contrib-gtd - Backup contributor: GTD

Scope: The whole task diff `9cd736102b1fbe7ec196327604d18c645ef26674..8641ddd90edadb9dcb96969c7659d162e845cada` (6 commits: 848777b0, a558de14, d196772f, 27a89797, 84ec1d2f, 8641ddd9), reviewed on the final code. This is the final re-review. It checks that the P3 from the previous review (the `Integer::class.java` warning) is fixed, and it reviews the new time-estimate test. Only committed code was reviewed, and the working tree was clean.

Requirements:
- tasks/backup-contrib-gtd.md: acceptance criteria 1-3 and the three recorded assumptions
- SPEC.md §5, §10.2, §14 (as used by the task and the earlier reviews)
- AGENTS.md task protocol, quality gate and project conventions
- Contracts used by the contributor: `backup/domain/BackupContributor.kt`, `BackupRoundTripTest`, `BackupFixture`
- reviews/backup-contrib-gtd/tests.md and fixes.md, and the previous review.md (at 27a89797)

Git range:
- Branch: main
- HEAD: 8641ddd90edadb9dcb96969c7659d162e845cada
- Base/start: 9cd736102b1fbe7ec196327604d18c645ef26674 (`git merge-base HEAD origin/main`)
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Status of the earlier findings:
- Previous P3 CR-1 (`java.lang.Integer` class literal): resolved. `GtdBackupContributor.kt:279` now reads `(rs.getObject("time_estimate_min") as Number?)?.toInt()`. `Integer` no longer appears anywhere in `gtd/backup`. A null column gives null, and PgJDBC returns `Integer` for an `integer` column, so the cast to `Number` is safe.
- Earlier CR-1..CR-3 and TW-1 (from the first review and the test-writer): still resolved. Between 27a89797 and HEAD the only production change is the single line above (`git diff 27a89797..HEAD -- server`).

Verification notes:
- New test `a time estimate of zero, a positive or large value or none is exported and restored as it was` (`GtdBackupContributorTests.kt:597`) uses ids `id(20..23)`, which ascend in the export order (`order by t.id`). So the `take(3)` and index-3 assertions are correct. It checks both the exported JSON and the restored column after `wipe()` plus `import`. This covers the changed line, including zero and null.
- Criterion 1: the contributor exports `areas`, `contexts`, `projects` (JSON) and `tasks`, `checklist_items` (JSON Lines), with every schema column. Task contexts are exported as sorted `contextIds`, as the recorded assumption says. Import keeps ids, times and versions. It sets `next_occurrence_id` and `next_task_id` only after all tasks exist.
- Criterion 2: `readable/tasks/{inbox,next,waiting,someday,logbook}.md` and `readable/projects/<area>/<project>.md` are written with checkboxes. Scheduled tasks go into a section of next.md, the Trash is left out, and file names are sanitised and kept unique, all matching the recorded assumptions.
- Criterion 3: `GtdBackupFixture` seeds every list, a tickler task, done and trashed tasks, FIXED and AFTER_COMPLETION recurrence (linked by `nextOccurrenceId`), projects with next steps (with and without an area, completed, trashed), two areas, two contexts and a checklist. tests.md reports `./gradlew test --tests '*GtdBackupContributorTests' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck` passing (21 + 1 tests) at 84ec1d2f plus this test, now committed as 8641ddd9. Task-scoped coverage: 100% of lines, 98.1% of branches. This reviewer did not run Gradle (read-only, parent instruction).
- No user content is logged and all test data is fictitious. No secrets in the diff. All changes stay inside the task's `touches` plus `reviews/backup-contrib-gtd/`.
- Note for the parent worker, not a code finding: `python tasks.py verify backup-contrib-gtd` still shows criteria 1-3 as unverified. They need to be recorded, and `quality.json` written, before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The previous P3 is resolved. The new time-estimate test is correct and covers the changed line.
