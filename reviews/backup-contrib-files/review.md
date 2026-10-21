# Code Review

Date: 2026-10-21

Task: backup-contrib-files - Backup contributor: files

Scope: The whole task diff `git diff 6ff53658..f8d8b2a2 -- server`, with focus on the last commit f8d8b2a2. That commit's only code change adds two tests to `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributorTests.kt`. The review covers all task files: `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributorTests.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupFixture.kt`. The working tree was clean (committed code only).

Requirements:
- tasks/backup-contrib-files.md (both acceptance criteria and the three recorded assumptions)
- SPEC.md §10.2 (backup format, restore), §14
- AGENTS.md project notes, docs/QUALITY.md
- The earlier reviews of this task (CR-1 to CR-5, all resolved) and reviews/backup-contrib-files/fixes.md
- Context read: the `file`/`folder` schema in `V202610201345__files_model.sql` (`file.folder_id references folder (id)`, not deferred)

Git range:
- Branch: main
- HEAD: f8d8b2a27271d3cfeb1925f0874b72e023f96fab
- Base/start: 6ff536586d0fc6d46e143d86b4fa9e5d46e4f727
- Commits reviewed: 5 (fef77c88 implementation, fa85290c review, 49bf0765 tests, 77a638cb fixes, f8d8b2a2 extra tests and reports)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Review of commit f8d8b2a2:
- `import fails when folders form a cycle without a root`: Folders are exported in id order, so `folders[0]` is `Documents` (id 1). The test sets its `parentId` to its own child `tax` (id 2). No folder has a null parent, so `ordered` is empty and its size differs from the folder count. The import then throws `InvalidBackupException` with the message "... form a cycle" (`FilesBackupContributor.kt:62-64`) before it inserts any rows. The `count("folder") shouldBe 0` assertion is meaningful. This test adds a cycle with no root to the existing orphan test. The earlier review had reasoned that this case was caught but had no test for it.
- `a file row that cannot be inserted fails the import and leaves no blob behind`: The test empties `folders.json`, but the blob `tax/2025.pdf` stays in the zip. The import therefore reads the blob, writes it (`:69`), passes the size and SHA-256 check, and then fails in `insert` on the non-deferred foreign key `file.folder_id -> folder.id`. That failure exercises the `catch` that deletes the new blob (`:75-78`) on a database error, not just on a mismatch. If someone removed that `catch`, a blob would stay behind and `storedBlobs().shouldBeEmpty()` would fail, so the test protects the behavior. `shouldThrow<Exception>` is broad, but the thrown type (a Spring `DataIntegrityViolationException`) is not part of the contract. The no-blob assertion is what makes the test meaningful.
- Both tests use only fictitious data and the existing helpers (`edit`, `wipe`, `import` in one transaction), so they follow the surrounding conventions.

Whole-task check: I re-read the contributor and found no change since the previous review, which found no issues. The two criteria are still covered:
- Criterion 1: `data/folders.json`, `data/files.jsonl`, and `files/` in the original tree with ` (2)` names on a clash, each path stored in the record. The files query streams with a fetch size of 1000, and blobs are streamed one at a time. Tests check the 60 MB file's allocated memory on both export and import.
- Criterion 2: `FilesBackupFixture` seeds nested folders, name clashes and a 60 MB file (`LARGE_SIZE = 60L * 1024 * 1024`). `BackupRoundTripTest` discovers it.

Test execution (this review, on clean committed HEAD f8d8b2a2): `cd server && ./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest'` -> BUILD SUCCESSFUL. The JUnit results:
- `FilesBackupContributorTests`: 24 tests, 0 failures, 0 errors, 0 skipped. That is the 22 tests from before plus the 2 new ones.
- `BackupRoundTripTest`: 1 test, 0 failures, 0 errors.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The parent worker still has to record the evidence for criteria 1 and 2 with `tasks.py verify`.
