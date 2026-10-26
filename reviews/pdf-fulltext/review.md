# Code Review

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: This is the third review round. It covers the committed range 3f51fea97c71a44352444480ce2bdc35e64e643d..36421f239c49c6368522e9dadb54b1832366a4cc. The code last changed in 7904fb972, and 36421f239 only adds the fix report. The review read the whole diff and every changed main file in full at HEAD: `FileTextJob`, `FileTextIndexer`, `FileTextRepository`, `FileTextExtractor`, `FileTextProperties`, the migration, the `FilesService` changes, the build line, the test properties and ARCHITECTURE.md. It also read the round-2 test changes and `FileTextDriveOperationsTests`. Uncommitted changes in the test folder come from a concurrent test-writer and were not reviewed.

Requirements:
- tasks/pdf-fulltext.md (acceptance criteria 1-2 and the recorded assumptions)
- SPEC.md §4.4 (global search: file names plus the text of PDFs and text files), §14
- docs/ARCHITECTURE.md (stack table: PDFBox 3.0.8 is listed), AGENTS.md project notes (logging, migrations, module conventions)
- reviews/pdf-fulltext/review.md (round 2) and reviews/pdf-fulltext/fixes.md ("Second round")
- Parent instructions: third round, confirm that the previous findings are resolved, ignore uncommitted changes

Git range:
- Branch: main
- HEAD: 36421f239c49c6368522e9dadb54b1832366a4cc
- Base/start: 3f51fea97c71a44352444480ce2bdc35e64e643d
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Previous findings

- Round 2 CR-1 (shutdown interrupt marks a healthy file `FAILED`): resolved.
    - `FileTextIndexer.failed()` (`FileTextIndexer.kt:114-119`) stores nothing and stops the batch in two cases: when the thread is interrupted, or when the cause chain contains a `ClosedByInterruptException` or an `InterruptedIOException`.
    - `indexPending()` checks the interrupt flag before each file (`:73`).
    - `FileTextJob.close()` (`FileTextJob.kt:83-96`) sets `stopping`, calls `shutdown()` and waits `SHUTDOWN_GRACE`. Only after that does it call `shutdownNow()` and wait again.
    - Ticks after close do nothing (`:54`). The batch loop also checks `stopped()` (`:73`).
    - Spring destroys the job before the indexer and the DataSource it depends on.
    - If the thread outlives the second grace period and its extraction then succeeds, `store()` either writes correct text or fails to get a connection. A failure there is a `RuntimeException`, which `extract()` logs. In neither case is a file marked `FAILED`.
    - Tests: `an extraction interrupted by the shutdown stores nothing and stops the batch`, `an extraction failing while the thread is interrupted stores nothing`, `after close a tick starts nothing`, `close lets the running extraction finish ...`.
- Round 2 CR-2 (a rename that changes nothing reads `file_text` without the row lock): resolved.
    - `FilesService.indexed(file)` (`FilesService.kt:462-467`) now calls `texts.lockFile(file.id)` before `textOf`. Every path that indexes a file therefore takes the file row lock first, in the same order as `FileTextIndexer.store()`. The paths are store, update, restore file, restore folder and backup restore.
    - In `storeFile` the new row has not been flushed yet, so `lockFile` (plain JDBC) returns `null`, and `textOf` is `null` anyway. That is harmless.
    - The regression test `a rename that changes nothing waits for a running extraction and keeps its text` would fail without the lock. Its final search assertion would see the body overwritten with `null`, and the fixer's mutation run confirmed this.
- Round 2 CR-3 (Errors escape unlogged): resolved.
    - `index()` (`FileTextIndexer.kt:91-94`) catches any other `Error` and logs it at ERROR through SLF4J, with the file id, the exception class and the attempt number. It never logs a message or contents. Then it rethrows.
    - After `MAX_ERROR_ATTEMPTS` (3) such errors, the file is left out until the next start (`:66`), so older files go on. A success or an ordinary failure clears the count.
    - This is covered by `a file whose extraction keeps running out of memory is left out after repeated failures, older files go on`.

## Acceptance criteria check

1. "Text extracted (PDFBox) in a background job, size-limited, indexed; search finds it": implemented.
    - Background job: `FileTextJob` runs on a dedicated `file-text` thread, every 30 s, with a run limit of 50 batches and 5 minutes.
    - PDFBox: `FileTextExtractor` uses PDFBox with a temp-file stream cache.
    - Size limits: max-file-size, max-chars (matches `SearchIndexer.MAX_BODY_LENGTH` 100000) and max-pages.
    - Indexed: `FileTextIndexer.store()` writes `file_text` and the search document body under the file row lock. It writes only while the digest and the storage key are unchanged.
    - Search finds it: `FileTextIndexerTests`, `FileTextIndexerEdgeCaseTests` and `FileTextDriveOperationsTests` assert that `SearchService` finds the extracted words.
    - After a backup restore, `RestoreService` truncates every table, `file_text` included, so the texts are rebuilt from the blobs. This is tested and matches the recorded assumption.
2. "`./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` passes": the persistence tests extend `PostgresTestBase` (Testcontainers). The fixer reports `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` with 309 tests and 0 failures, and `./gradlew check` passing. This reviewer did not run Gradle, to avoid colliding with the concurrent test-writer's build. `tasks.py verify pdf-fulltext` still lists both criteria as unverified. The parent must run the named command and record it before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- During the grace period, `close()` lets the current batch of up to 20 files continue, because `stopping` is checked between batches and not between files. After 5 s the thread is interrupted, and the indexer stops without storing anything. Shutdown therefore takes at most about 10 s and never stores a wrong result. That is acceptable.
- The logging follows the conventions. Every log line names only the file id and the exception class.
- `files.domain.FilesService` depends on `files.fulltext.FileTextRepository`, and `fulltext` depends on `files.domain.PreviewService` and `files.infra.BlobStore`. This is a package cycle inside the `files` module. It does not cross a module boundary, and the task assumption records the edit, so it is not reported.
- `FileTextDriveOperationsTests` leaves an entry in the shared indexer's in-memory `unreadable` map for a file it deletes afterwards. The ids are unique and the map only widens the `findPending` limit, so this does not affect other tests.

## Review conclusion

No material issues found.
