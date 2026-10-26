# Code Review

Date: 2026-10-26

Task: backup-round-trip-tmp-leak - BackupRoundTripTest leaks a 61 MB cockpit-round-trip-data temp dir per run, filling /tmp (Disk quota exceeded)

Scope: committed task changes `20862ca86..ae683aed6` (one code commit ae683aed6 plus task-file commits for heartbeat/assume)

Requirements:
- tasks/backup-round-trip-tmp-leak.md (acceptance criteria 1-2, recorded assumption)
- AGENTS.md project notes (test conventions, lint, scratch files)
- Parent agent's review brief (Spring context cache, @Nested/PER_CLASS, DynamicPropertySource ordering, criterion 2 completeness, main-code work dirs)

Git range:
- Branch: main
- HEAD: ae683aed699fceb94948c0d8139a8c1aad291450
- Base/start: 20862ca863abb1e45d8169eca7566ca4970e7f9a
- Commits reviewed: 3 (1 code commit, 2 task-file commits)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification notes

- **The change:** 12 server test classes now have a `@JvmStatic @AfterAll` in their companion object that calls `deleteRecursively()` on the companion-level temp dir. This is the same pattern `BackupRoundTripTest` already uses on main (`BackupRoundTripTest.kt:151-158`). Criterion 1's code change (the round-trip test) was already on main. Whether a full run leaves no new directory is runtime evidence, and the parent records it.
- **Spring context cache:** each of the 12 classes declares its own `@DynamicPropertySource` method in its companion, so each one gets a separate context cache key. No other class can reuse a cached context whose `cockpit.data-dir` or SPA location points at a deleted directory. None of the 12 classes uses `@DirtiesContext`. A context that stays cached after its class has finished is not used by another class.
- **Background writers after deletion:** in main code, `cockpit.data-dir` is only used by the files stores (`BlobStore`, `ChunkStore`, `ThumbnailStore`), `AndroidApkStore` and `RestoreService`. All of them write when a request or call is made. No scheduled job writes into the data dir, so nothing writes into the deleted directory after the class ends. Per-test `RestoreTestDatabases.wipe(jdbc, dataDir)` and `BackupRoundTripTest` recreate the directory before each test, so the order of `@AfterAll` and the class's own tests is safe.
- **@Nested / PER_CLASS:** none of the 12 changed classes uses `@Nested` or `@TestInstance(PER_CLASS)`. The static companion `@AfterAll` runs once per class, after all of its tests, and also when a test failed.
- **Completeness of criterion 2:** `grep createTemp` over `server/src` finds 13 companion-level `Files.createTempDirectory` calls in tests. All 13 are now cleaned up (12 in this commit, plus `BackupRoundTripTest` on main). The remaining test calls are `Files.createTempFile(work, ...)` inside JUnit `@TempDir` directories, which JUnit deletes. This includes `FilesBackupContributorTests`' large zip (`work.resolve("large.zip")`). The default test data dir is `build/test-data` (`src/test/resources/config/application.properties:9`), not `/tmp`.
- **Main-code work dirs (the assumption):** checked and accurate.
  - `BackupJobService.destroy()` deletes every remaining zip (`BackupJobService.kt:165-169`). A failed run deletes its target (`:139`), and discarded jobs delete theirs (`:161`).
  - `RestoreService` deletes invalid uploads right away (`RestoreService.kt:82`) and deletes pending uploads on the next upload and in `destroy()` (`:264-270`). The restore stash lives inside the data dir and is deleted or unstashed (`:201`, `:234-240`).
  - What remains is at most one empty `cockpit-backups*` / `cockpit-restores*` directory per Spring context that ran a backup or restore. These are not large temp files or directories in the sense of criterion 2.
- **Conventions:** the change is limited to test code. The KDoc comment matches the existing one in `BackupRoundTripTest`. No new dependencies, no lint suppressions, no secrets or personal data. The change goes beyond `touches` (other modules' test folders and `core/spa`). The assumption recorded in the task file covers this, and criterion 2 explicitly asks for it.

## Review conclusion

No material issues found.
