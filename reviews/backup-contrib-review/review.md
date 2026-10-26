# Code Review

Date: 2026-10-26

Task: backup-contrib-review - Backup contributor: weekly reviews

Scope: committed changes `a4a2ef2727eed76417bdc7ea21cb2922cee586d4..3c3d5d0558b87db99d85bbb6cf57e2350be46650` on `main`. This covers the two task commits, d3e07c4e4 (`ReviewBackupContributor.kt`, `ReviewBackupFixture.kt`) and 3c3d5d055 (`ReviewBackupContributorTests.kt`). This review replaces the earlier one, which covered only the first commit.

Requirements:
- `tasks/backup-contrib-review.md`: both acceptance criteria and the recorded assumption (`data/weekly_reviews.jsonl` rather than SPEC §10.2's `weekly_reviews.json`; no readable mirror; a backup with more than one open review or an unknown step is rejected at upload and at import)
- SPEC.md §8.6 (weekly review model), §10.2 (zip format), §14 (every entity covered by a BackupContributor and the round-trip test)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module packages `<module>.{api,domain,infra}` plus the established `<module>.backup`, logging rules, fictitious test data)
- Backup framework contract: `backup/domain/BackupContributor.kt`, `BackupFormat.kt`, `RestoreService.validate`/`replace`, `BackupService.restoreWarnings`
- Existing contributors used for comparison: `journal/backup/JournalBackupContributor.kt`, `files/backup/FilesBackupContributor.kt`, `core/backupcontrib/UserBackupContributor.kt`
- Test report `reviews/backup-contrib-review/tests.md` (context only)

Git range:
- Branch: main
- HEAD: 3c3d5d0558b87db99d85bbb6cf57e2350be46650
- Base/start: a4a2ef2727eed76417bdc7ea21cb2922cee586d4
- Commits reviewed: 2

Uncommitted changes: only `reviews/backup-contrib-review/`, which is not reviewed.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## What I checked

Correctness against the acceptance criteria:
- AC1, export: `ReviewBackupContributor.export` writes every `weekly_review` row, ordered by id, to `data/weekly_reviews.jsonl`. Each record has id, createdAt, updatedAt, version, startedAt, completedAt (null while the review is open) and step. These match the columns in `V202610221000__review_weekly_review.sql`. Times go through the shared `instant`/`toTimestamp` helpers, so microsecond precision is kept, as in the other JDBC-based contributors.
- AC1, restore with the at-most-one-open-review rule intact: `validReviews` rejects a backup that has more than one record with `completedAt == null`, or a step that is not a `ReviewStep` name. The `ReviewStep` names match the migration's `weekly_review_step` check constraint exactly. The contributor runs the check in `restoreWarnings` and again in `import`. `RestoreService.validate` turns the resulting `InvalidBackupException` into report problems at upload. `RestoreService` rethrows it during `replace`, so the import transaction, which includes the wipe, rolls back. The database's partial unique index `weekly_review_one_open` stays as a second safeguard. Inserting with JDBC keeps ids, versions and timestamps exactly, for the same reason the other contributors give.
- A backup without `weekly_reviews.jsonl` imports nothing and is still valid. `jsonLines` yields no records in that case.
- The behavior matches the recorded assumption: no readable mirror, and invalid data is rejected rather than repaired. The difference from SPEC §10.2 (`.jsonl` instead of `.json`) is the more specific task requirement, and it is recorded as an assumption for humans to review.
- `weekly_review` has no foreign keys, so an empty `dependsOn` is correct. Weekly reviews are not part of the link or search system, so there is nothing to re-index, unlike the journal contributor.

Architecture and integration:
- The contributor follows the framework contract and the existing contributors' pattern: a `@Component` in `<module>.backup`, JDBC, an unchanged data-name format, and a stable id `review`. It reads `ReviewStep` from its own module's domain and touches no other module.
- Problem messages name only the file and the review UUID, with no user content. This follows the `restoreWarnings` contract and matches `FilesBackupContributor`'s messages. The contributor logs nothing.

Tests:
- `ReviewBackupFixture` seeds two completed reviews and one open review (at a step other than the first) through the JPA repository. This meets AC2's "an open and a completed review".
- `ReviewBackupContributorTests` (11 tests) covers the export shape and ordering, the empty export, an exact row-level import round trip, service behavior after a restore (current review, last completed, `daysSinceLast`, `start` resuming the review instead of creating one), rejection of two open reviews and of an unknown step at validation and at import with no rows restored, several problems reported together, and acceptance of every valid step. The assertions check behavior, the tests do not mock the logic under test, and all data is fictitious.
- No existing tests were changed or removed.
- I ran the tests myself: `cd server && ./gradlew test --tests '*ReviewBackupContributorTests' --tests '*BackupRoundTripTest'` exited 0. The JUnit XML shows ReviewBackupContributorTests with 11 tests and 0 failures, and BackupRoundTripTest with 1 test and 0 failures, at timestamps 2026-10-26T11:25Z.
- Evidence status: `python tasks.py verify backup-contrib-review` still lists both criteria as unverified. That is expected before `done`. The parent worker still has to record evidence for each criterion. It must not claim either criterion without the command and its result. The run above is valid evidence for AC2.

Security: there are no new endpoints, inputs, secrets or configuration changes. Backup input is validated before any write.

## Review conclusion

No material issues found.
