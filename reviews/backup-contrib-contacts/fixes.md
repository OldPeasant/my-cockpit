# Fix Report

Date: 2026-10-22

Task: backup-contrib-contacts — Backup contributor: contacts

Scope: current task (`server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup`), finding CR-2 of `reviews/backup-contrib-contacts/review.md` as approved by the parent worker.

## Fixed findings

### CR-2 — Copies helpers that siblings share instead of reusing them

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/backup/ContactsBackupContributor.kt`

Problem:
The contributor converted rows inline (`Timestamp.from`, `rs.getObject("id", UUID::class.java)`, `rs.getTimestamp(...).toInstant()` / `?.toInstant()`) instead of using the shared row helpers in `core/backupcontrib/BackupRows.kt` that the notes, calendar and mail contributors use.

Fix:
`import` now uses `toTimestamp()` / `toTimestampOrNull()` for `created_at`, `updated_at`, `deleted_at`. `row` uses `rs.uuid("id")`, `rs.instant("created_at")`, `rs.instant("updated_at")`, `rs.instantOrNull("deleted_at")`. The unused `java.sql.Timestamp` import is gone. The nullable `photo_file_id` still uses `rs.getObject(..., UUID::class.java)`: `BackupRows.kt` has no nullable UUID helper, and the sibling contributors read their nullable UUID columns the same way (e.g. `MailBackupContributor` `folder_id`, `CalendarBackupContributor` `parent_event_id`). Behavior is unchanged.

The optional second part of the finding (sharing the `encoded(path)` percent-encoding with `NotesBackupContributor`) was not done, as the parent worker instructed: it would change files outside this task's `touches`. The reviewer itself marked it as a possible follow-up.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTripTest'` — Pass (BUILD SUCCESSFUL; ContactsBackupContributorTests 9 tests, 0 failures, 0 errors; BackupRoundTripTest 1 test, 0 failures, 0 errors)

## Not fixed

### CR-2 (part) — Shared `encoded(path)` helper for `../../files/` links

Status:
Out of scope

Reason:
Moving the encoding next to `FilesBackupContributor.paths` changes the files and notes modules, which are outside this task's `touches`. The parent worker told the fixer not to do it. It can be a follow-up task.

### CR-1 — contributor tests

Status:
Invalid (already resolved)

Reason:
The parent worker reported it already fixed by `ContactsBackupContributorTests.kt` (commit 8ab11d57). Not part of this fix run; those tests still pass.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests '*ContactsBackupContributorTests' --tests '*BackupRoundTripTest'` — Pass (10 tests, 0 failures)

Full test suite:
- Not run by the fixer (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `./gradlew ktlintCheck` (part of the command above) — Pass; compilation passed as part of `test`

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 (the optional shared-encoder part of CR-2 is deferred as out of scope)

## Final status

All validated findings fixed and validation passed.
