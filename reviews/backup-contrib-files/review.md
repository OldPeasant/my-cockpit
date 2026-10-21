# Code Review

Date: 2026-10-21

Task: backup-contrib-files - Backup contributor: files

Scope: Task commit 560a95bf on top of base 8ca518820cb7d5c65f253f026e0c210b38557e49 (`git diff 8ca51882..560a95bf`): `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupFixture.kt`. Uncommitted test files that another helper may be adding in the test package were not reviewed.

Requirements:
- tasks/backup-contrib-files.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §8.2 (Files), §10.1 and §10.2 (backup zip format, restore), §12, §14
- `BackupContributor`, `BackupDataWriter` and `BackupDataReader` contract (server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupContributor.kt)
- AGENTS.md project notes, docs/ARCHITECTURE.md, existing contributors in `core/backupcontrib`
- Context read: ZipBackupWriter, ZipBackupReader, BackupFormat.requireSafePath, BackupService, RestoreService, BlobStore, FileNames, FilesService, files Flyway migrations, BackupRoundTripTest, BackupFixture

Git range:
- Branch: main
- HEAD: 560a95bf400f06a34fcdddbbb0d92744c4b070e4
- Base/start: 8ca518820cb7d5c65f253f026e0c210b38557e49
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 4 P3

What I checked and found to be correct:
- The export writes `data/folders.json` (one document), `data/files.jsonl` (streamed) and one `files/<path>` entry per file. Each blob is streamed with `BlobStore.open` and `transferTo`, so the whole content is never in memory. Import streams each blob into `BlobStore.write`. Memory use does not grow with file size, including for the 60 MB fixture file.
- Paths are safe. Every name went through `FileNames.validate`, which rejects `/`, `\`, control characters, `.` and `..`. `ZipBackupWriter.file` also checks `requireSafePath`. On restore, `reader.file(path)` only looks up a zip entry, and blobs are written under new keys that `BlobStore` generates. No path from the backup ever reaches the file system.
- Clash resolution is deterministic. Folder paths are worked out level by level, ordered by Trash state and then id. File names are claimed per folder after the folder's child folders, ordered by `deleted_at is not null, id`. Every input to the order (ids, names, parents, `deleted_at`) is restored exactly, so a second export after restore gives byte-equal `folders.json` and `files.jsonl` and the same `files/` paths. Both export passes run in the same repeatable-read snapshot, so they agree.
- Restore inserts folders parents first and detects orphans and cycles (`ordered.size == folders.size`). It keeps ids, timestamps and versions through JDBC, checks size and SHA-256 and deletes the new blob when that check fails, and re-indexes active items the same way `FilesService` does (`name`, null body). If the import fails, the blobs it wrote are cleaned up because `RestoreService` stashes and restores the data directory.
- Every column of `folder` and `file` is covered. `upload_session` and thumbnails are left out, as the recorded assumption says.
- The fixture seeds nested folders, the special Attachments folders, case and duplicate clashes, a file next to a folder of the same name, a trashed folder clashing with an active one, empty and unicode files, items in the Trash and a 60 MB file generated on the fly.
- Not run here: I had no permission to run Gradle or Testcontainers, so I did not run the round-trip test myself. `python tasks.py verify backup-contrib-files` shows no evidence recorded yet for either criterion.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | No test checks the layout that criterion 1 requires; the round trip only proves the export is self-consistent**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupFixture.kt:26`
    - Category: Tests
    - Problem: The only test code in the commit is the round-trip fixture. `BackupRoundTripTest` compares the export with itself after restore, so it passes for any deterministic naming. A layout that was wrong but stable would still pass, for example a file that does not get ` (2)`, a numbered folder, a file that wins over a folder, a trashed item that keeps the original name, or `files.jsonl` `path` values that do not match the zip entries. The restore failure paths are not tested either: a size or SHA-256 mismatch must delete the new blob and fail, a missing blob must fail, and a folder whose parent is missing must fail (`FilesBackupContributor.kt:61`, `:64`, `:67-71`).
    - Why it matters: Criterion 1 ("original tree with original names (clashes → ' (2)', mapping in files.jsonl)") needs evidence of the actual paths, and the round trip cannot give it. The clash rules (folder over file, active over trashed, older over newer, case-insensitive) are easy to break later without anyone noticing.
    - Suggested fix: Add a focused test. Export the fixture-like data, then check the `files/` entry names and the `path` field of each `files.jsonl` record. Expected for the fixture: `Documents/tax/2025.pdf`, `2025 (2).pdf`, `2025 (3).PDF`, the trashed draft as `2025 (4).pdf`, the trashed folder as `Documents/Tax (2)/old.txt`, and the file `Documents/tax (3)`. Add import tests for a mismatched blob (the new blob is deleted and the import fails), a missing blob and an orphan folder.

## P3 Low

- [ ] **CR-2 | Bad files data in a backup fails restore with a generic error instead of an `InvalidBackupException`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt:61`
    - Category: Correctness
    - Problem: `check`/`checkNotNull` at lines 61, 64 and 67 throw `IllegalStateException`. `RestoreService.replace` turns that into `RestoreFailedException`, so the owner sees only that the restore failed, not which data is wrong. The `RestoreController` handler for `InvalidBackupException` (422 with problems) is not used. Nothing in `restoreWarnings` and the validation step catches these cases before the owner confirms.
    - Why it matters: A hand-edited or partly corrupt backup that still passes the manifest check fails only after the owner confirms, and the message gives no reason. The ids involved are not user content, so they could be shown.
    - Suggested fix: Throw `InvalidBackupException(listOf("data/files.jsonl: the blob of file <id> is missing / does not match"))` and `InvalidBackupException(listOf("data/folders.json has folders whose parent is missing"))`. Keep the blob cleanup in the `catch`.

- [ ] **CR-3 | The KDoc says file rows are streamed, but PgJDBC loads the whole result set**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt:150`
    - Category: Quality
    - Problem: The KDoc says the rows are "streamed from the database folder by folder, so only the names of one folder are kept in memory". `jdbc.queryForStream` runs with the default fetch size: there is no `fetch-size` or `defaultRowFetchSize` anywhere in `src/main`. With fetch size 0, the PostgreSQL driver reads every row into memory before the first one comes back, and this happens twice per export. Only metadata is loaded, never blob content, so criterion 1's constant memory for large files still holds. `LinksBackupContributor` and `RemindersBackupContributor` use the same pattern.
    - Why it matters: The comment promises a memory bound that the code does not have for a drive with many files, and that could mislead later work.
    - Suggested fix: Set a fetch size for these queries. The export runs inside a transaction, so PgJDBC will then use a cursor. For example, use a `JdbcTemplate` copy with `fetchSize = 1000`, or a `PreparedStatementCreator` that calls `setFetchSize`. Otherwise, change the comment so it only claims constant memory for blob content.

- [ ] **CR-4 | "Extracts on every file system" claims more than the case-only uniqueness gives**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt:24`
    - Category: Quality
    - Problem: The KDoc and recorded assumption #1 say paths are unique "so the tree extracts on every file system". `UniqueNames` (line 203) only compares `lowercase()`. `FileNames.validate` still allows names that Windows cannot create or that clash there: `: * ? " < > |`, a trailing `.`, and device names such as `CON` or `NUL`. Names that differ only in Unicode normalization (NFC or NFD) also clash on macOS file systems.
    - Why it matters: A human reviewing the assumption may trust a guarantee the code does not give. It does not affect restore, because restore reads entries from the zip and does not extract them.
    - Suggested fix: Narrow the KDoc and the assumption to "unique regardless of case". Alternatively, also compare names after NFC normalization in `UniqueNames.claim`. Do not change the names themselves: the criterion requires the original names.

- [ ] **CR-5 | A fixture case does not test the clash its comment describes**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupFixture.kt:39`
    - Category: Tests
    - Problem: `file("2025", tax, ..., "a file named like a folder")` is placed in `Documents/tax`, but the folder `2025` sits in `Documents/tax/archive` (line 29). The two never clash, so the line adds no case. The folder-versus-file clash is only covered by `file("tax", documents, ...)` at line 40.
    - Why it matters: The fixture looks like it covers more than it does, and the comment is misleading.
    - Suggested fix: Remove the line or reword its comment. Alternatively, put the file in `archive` (the folder of `2025`) so it covers a second folder-wins case in a nested folder.

## Review conclusion

Material issues found; fix before completing the task. No defects in the production code block the criteria. CR-1 must be resolved, or its test gap covered by the concurrent test-writer, before criterion 1 can be marked verified with real evidence. Criterion 2 still needs `./gradlew test --tests '*BackupRoundTripTest'` to be run and recorded, which this review could not do.
