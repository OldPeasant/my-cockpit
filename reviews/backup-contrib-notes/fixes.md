# Fix Report

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: current task, P3 findings CR-1 (partial, as the parent decided) and CR-2 from `reviews/backup-contrib-notes/review.md`. Fix commit: 9dde4cd6e0fde0026a3456d37562173ab516a8b0.

## Fixed findings

### CR-1 (partial) — The notes module reads the core `link` table with its own SQL

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`

Problem:
`links()` compared link types against the hard-coded string `"NOTE"`, both in the SQL and in Kotlin.

Fix:
The constant `NOTE` is now `ItemType.NOTE.name` (`core.items.domain.ItemType`), and the SQL takes it as bind parameters (`where source_type = ? or target_type = ?`) instead of a literal. As the parent decided, the direct read of the `link` table stays; an assumption for it is already recorded on the task.

Validation:
- `./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'` — Pass

### CR-2 — "Streamed" note queries load the whole result set into memory

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`

Problem:
`queryForStream(sql)` without a fetch size makes PgJDBC load every row before the stream starts. This affected the note query (tags included) and the note_revision query, although the KDoc says "streamed from the database".

Fix:
Added a private helper `streamed(sql)` that returns a `PreparedStatementCreator` setting `fetchSize = FETCH_SIZE` (1000, the same value and pattern as `FilesBackupContributor`). Both the `notes()` query (note + note_tag array) and the `note_revision` query use it now. Backups run in a transaction (BackupService), which PgJDBC needs for the fetch size to take effect, as with the files contributor. No other behaviour changed.

Validation:
- `./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'` — Pass

## Not fixed

### CR-1 (remaining part) — move the link read behind a core service

Status:
Out of scope (parent decision)

Reason:
The parent decided to keep reading the `link` table directly; the deviation is recorded as a task assumption.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — no remaining changes beyond the fix
- `cd server && ./gradlew test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'` — BUILD SUCCESSFUL; XML: NotesBackupContributorTests 17 tests, 0 failures, 0 errors, 0 skipped; BackupRoundTripTest 1 test, 0 failures, 0 errors, 0 skipped

Full test suite:
- `cd server && ./gradlew check` — BUILD SUCCESSFUL (8m 49s); 225 result files, 2898 tests, 0 failures, 0 errors, 0 skipped

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass
- `detekt` — Not applicable (no such task in the server build)

`.taskcheck`:
- Not run (left to the parent worker; the server part of it, `./gradlew check`, passed above)

Remaining unresolved findings:
0 (the remaining part of CR-1 is out of scope by parent decision)

## Final status

All validated findings fixed and validation passed.
