# Fix Report

Date: 2026-10-21

Task: backup-contrib-notes - Backup contributor: notes

Scope: current task, P3 findings CR-1 (partial, as the parent decided), CR-2 and CR-3 from `reviews/backup-contrib-notes/review.md`. Fix commit for CR-1/CR-2: 9dde4cd6e0fde0026a3456d37562173ab516a8b0. CR-3 was fixed in a later pass (working tree change, committed by the parent worker).

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

### CR-3 — Front matter is not valid YAML for titles or tags with C1 control or other non-printable characters

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/notes/backup/NotesBackupContributorTests.kt`

Problem:
`quoted()` escaped only C0 controls and DEL. U+0080-U+009F, U+FFFE and U+FFFF are outside YAML's printable set, and U+0085, U+2028 and U+2029 are line breaks in YAML 1.1 (SnakeYAML folds them), so such titles or tags gave invalid front matter or a changed value.

Fix:
`quoted()` now also writes U+0080-U+009F (including U+0085 NEL), U+2028, U+2029, U+FFFE and U+FFFF as `\uXXXX` escapes; the KDoc names the escaped set. The existing YAML-validity test now puts U+0085, U+2028 and U+0090 in the title and U+0085, U+2028, U+2029, U+0080, U+0090, U+009F, U+FFFE and U+FFFF in tags, asserts that the raw front matter contains each as a `\uXXXX` escape, and that SnakeYAML parses title and tags back unchanged.

Validation:
- `cd server && ./gradlew ktlintFormat ktlintCheck test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'` — Pass (BUILD SUCCESSFUL; NotesBackupContributorTests 18 tests, BackupRoundTripTest 1 test, 0 failures, 0 errors, 0 skipped)

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

- CR-3 pass: `cd server && ./gradlew ktlintFormat ktlintCheck test --tests '*NotesBackupContributorTests' --tests '*BackupRoundTripTest'` — BUILD SUCCESSFUL; NotesBackupContributorTests 18 tests, BackupRoundTripTest 1 test, 0 failures, 0 errors, 0 skipped; ktlintFormat left no changes beyond the fix

Full test suite:
- CR-3 pass: not re-run (change is limited to `quoted()` and one test; the parent worker runs `.taskcheck`)
- CR-1/CR-2 pass: `cd server && ./gradlew check` — BUILD SUCCESSFUL (8m 49s); 225 result files, 2898 tests, 0 failures, 0 errors, 0 skipped

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass
- `detekt` — Not applicable (no such task in the server build)

`.taskcheck`:
- Not run (left to the parent worker; the server part of it, `./gradlew check`, passed above)

Remaining unresolved findings:
0 (the remaining part of CR-1 is out of scope by parent decision)

## Final status

All validated findings fixed and validation passed.
