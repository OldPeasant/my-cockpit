# Fix Report

Date: 2026-10-21

Task: backup-contrib-files - Backup contributor: files

Scope: Approved findings CR-2, CR-3, CR-4 and CR-5 from `reviews/backup-contrib-files/review.md` (base f4c6df3c77701087cc415b6853d5bd05c7b5d8f9, task commits 97c51a16, a97c914a, f39f92a5). CR-1 is covered by the tests the test-writer added in f39f92a5 (the parent marked it as needing no action).

## Fixed findings

### CR-2 — Bad files data in a backup fails restore with a generic error instead of an `InvalidBackupException`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributorTests.kt`

Problem:
`check`/`checkNotNull` threw `IllegalStateException` for a missing blob, a size or SHA-256 mismatch, and orphan or cyclic folders. Restore then reported only a generic failure.

Fix:
These cases now throw `InvalidBackupException` with one problem each. The problem names the data file and the file id, or the folder issue, and contains no user content: `data/folders.json has folders whose parent is missing or that form a cycle`, `data/files.jsonl: the blob of file <id> is missing`, `data/files.jsonl: the blob of file <id> does not match its size or SHA-256`. The `catch` that deletes the new blob is unchanged. The four existing failure-path tests now expect `InvalidBackupException`. Their message assertions (id, "SHA-256", "size", "missing", "parent is missing") were kept as they were.

Validation:
- `./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest'` — Pass

### CR-3 — The KDoc says file rows are streamed, but PgJDBC loads the whole result set

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`

Problem:
`queryForStream` ran with fetch size 0, so PgJDBC read every file row into memory before returning the first one. The KDoc claimed folder-by-folder streaming.

Fix:
The `file` export query now goes through a `PreparedStatementCreator` that sets `fetchSize = 1000` (private `FETCH_SIZE`). Only this query in this contributor is affected, and the shared `JdbcTemplate` is not changed. The export runs in a transaction (autocommit off), so PgJDBC uses a cursor. The KDoc now says this. The folder query was left alone, because every folder is kept in memory anyway to work out paths.

Validation:
- `./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest'` — Pass (includes the 60 MB/large-file and layout tests)

### CR-4 — "Extracts on every file system" claims more than the case-only uniqueness gives

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`

Problem:
The class KDoc said paths are unique "so the tree extracts on every file system". The code only guarantees uniqueness regardless of case.

Fix:
The KDoc now says only "Paths are unique regardless of case and among folders and files of a folder together". No names were changed. The recorded assumption in `tasks/backup-contrib-files.md` (line 18) still has the broader wording. Assumptions are recorded through `tasks.py`, so the parent worker has to correct it there.

Validation:
- `./gradlew ktlintCheck` — Pass

### CR-5 — A fixture case does not test the clash its comment describes

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupFixture.kt`

Problem:
The file `2025` sat in `Documents/tax`, but the folder `2025` sits in `Documents/tax/archive`, so the two never clashed.

Fix:
`archive` is now a named variable, and the file `2025` is placed in it, next to the folder `2025`. This covers a second folder-wins clash in a nested folder (the file is exported as `Documents/tax/archive/2025 (2)`). `FilesBackupContributorTests` does not use the fixture, so no expected paths had to change there. The round-trip test exercises the fixture.

Validation:
- `./gradlew test --tests '*BackupRoundTripTest'` — Pass

## Not fixed

### CR-1 — No test checks the layout that criterion 1 requires

Status:
Invalid (no longer applies)

Reason:
Already resolved by the test-writer's `FilesBackupContributorTests.kt` (commit f39f92a5: layout, clash and restore-failure tests). The parent listed it as needing no action.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*FilesBackup*' --tests '*BackupRoundTripTest' ktlintCheck` — tests Pass (FilesBackupContributorTests 22/22, BackupRoundTripTest 1/1). In that same invocation `ktlintCheck` ran before the format had settled the import order. A separate `./gradlew ktlintCheck` run afterwards — Pass.
- After the final KDoc rewrap: `./gradlew ktlintCheck compileKotlin` — Pass

Full test suite:
- `cd server && ./gradlew check` — Pass (BUILD SUCCESSFUL). Run before the final KDoc-only rewrap, which changed comments only and was re-checked with ktlint and compile.

Lint/type-check/build:
- `./gradlew ktlintCheck compileKotlin` — Pass

`.taskcheck`:
- Not run (the parent runs it; the server part `./gradlew check` passed)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
