# Code Review

Date: 2026-10-21

Task: backup-contrib-files - Backup contributor: files

Scope: The task's committed changes from base f4c6df3c77701087cc415b6853d5bd05c7b5d8f9 to 5c2126f5: commits 97c51a16 (implementation), f39f92a5 (tests) and 5c2126f5 (fixes for the previous review's CR-2 to CR-5). Since then the clone has been rebased onto main, which moved the base to 6c4f9434. The commits are now 1c29b9ff, a15e5cb2, b856e075 and 9985ecdf. `git range-diff f4c6df3c..5c2126f5 6c4f9434..HEAD` shows every commit as patch-identical (`=`), so the reviewed code is the same. Files: `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributorTests.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupFixture.kt`. A concurrent test-writer has left an uncommitted change to `FilesBackupContributorTests.kt`. That change was not reviewed.

Requirements:
- tasks/backup-contrib-files.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §10.2 (backup format, restore), §14
- AGENTS.md project notes, docs/QUALITY.md
- The previous review (CR-1 to CR-5) and reviews/backup-contrib-files/fixes.md
- Context read: `BackupContributor` contract, `InvalidBackupException` (BackupFormat.kt), `RestoreService.replace`/`validate`, `RestoreController` exception handler, `BackupService.export` (snapshot transaction), `BackupRoundTripTest` fixture discovery

Git range:
- Branch: main
- HEAD: 9985ecdfbe1cf1ae696a9a341e5383aa9cba2b14 (patch-identical to 5c2126f5)
- Base/start: f4c6df3c77701087cc415b6853d5bd05c7b5d8f9 (after the rebase: 6c4f94340b7a77a8152cc09736300ba3bd01ba59)
- Commits reviewed: 3 code commits (plus the review-only commit a97c914a/a15e5cb2)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Status of the previous findings:
- CR-1 (no layout or failure-path tests): resolved. `FilesBackupContributorTests` checks the exact `files/` entry names and the `path` of each `files.jsonl` record for these cases:
  - duplicates, where the smaller id wins regardless of insert order;
  - case-only clashes;
  - an existing ` (2)` name, which is skipped;
  - a folder that beats an older file;
  - an active item that beats an older trashed one;
  - root-level clashes;
  - same-named folders under different parents;
  - path uniqueness regardless of case, and that each path matches its blob.

  It also checks the fields of `folders.json` and `files.jsonl`, including that the storage key is absent. Restore tests cover ids, times, versions and the Trash state, new blob keys, search re-indexing of active items only, SHA-256 and size mismatches (no file rows and no blobs left behind), a missing blob, and an orphan folder. A 60 MB file test checks that bytes allocated per thread stay below a quarter of the file size, for both export and import.
- CR-2: resolved. A bad backup now throws `InvalidBackupException` with one problem each (`FilesBackupContributor.kt:62-64`, `:68`, `:71-73`). The problems name only the data file and the file id, never user content. `RestoreService.replace` rethrows `InvalidBackupException` after unstashing (`RestoreService.kt:171`), and `RestoreController` maps it to 422 with the problems. The `catch` that deletes the newly written blob also runs for the new mismatch exception (`:75-78`). The tests now expect `InvalidBackupException`.
- CR-3: resolved. The `file` query sets `fetchSize = 1000` through a `PreparedStatementCreator` (`:167-172`). The default `JdbcTemplate` fetch size of -1 does not override it. Export runs inside `BackupService`'s `snapshot` transaction, so PgJDBC uses a cursor, and the KDoc now says so. Loading the folder query in full is intentional, because every folder path is kept anyway.
- CR-4: resolved. The class KDoc only claims uniqueness regardless of case. Recorded assumption #3 corrects assumption #1.
- CR-5: resolved. The file `2025` now sits in `archive`, next to the folder `2025`. That makes it a real folder-wins clash in a nested folder (`FilesBackupFixture.kt:29-30`, `:40`).

New issues: I re-read the full contributor and found no new defects. Points I checked:
- Folder ordering on import still detects both orphans and cycles. Folders in a cycle are never reached from the roots, so the size check catches them.
- The two export passes use the same ordering and the same snapshot, so they agree on paths.
- `FolderPaths.namesIn` claims the child folder names before the files, so a folder wins over a file.
- The fixture is discovered by `BackupRoundTripTest` through classpath scanning, and its `contributor` id matches `FilesBackupContributor.ID`.

Test execution: I tried to run `cd server && ./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest'`, but Gradle failed with `java.io.EOFException` in `:test`. This is most likely because the test-writer was running Gradle at the same time. The working tree also contained that helper's uncommitted test change, so this run could not have tested exactly the committed code. The most recent result files in `server/build/test-results/test` are from before this review: `FilesBackupContributorTests` 22 tests with 0 failures and 0 errors, and `BackupRoundTripTest` 1 test with 0 failures. They match what fixes.md reports, but this review did not produce them itself. `python tasks.py verify backup-contrib-files` shows no evidence recorded yet for either criterion. The parent worker must run both test commands on the final committed code and record the results before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All five previous findings are resolved in the committed code. Criteria 1 and 2 still need evidence recorded with `tasks.py verify`, from a clean run of the focused tests and `*BackupRoundTripTest` on the final code. This review could not complete such a run because Gradle failed during a concurrent build.
