# Code Review

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: This is the third review round plus a delta review. The full committed range is 3f51fea97c71a44352444480ce2bdc35e64e643d..045288e1e6c89ddc1d4c8b009794112f50d2d973.
- Third round (3f51fea97..36421f239): the code last changed in 7904fb972, and 36421f239 only adds the fix report. The review read the whole diff and every changed main file in full: `FileTextJob`, `FileTextIndexer`, `FileTextRepository`, `FileTextExtractor`, `FileTextProperties`, the migration, the `FilesService` changes, the build line, the test properties and ARCHITECTURE.md. It also read the round-2 test changes and `FileTextDriveOperationsTests`.
- Delta (36421f239..045288e1e, `git diff 36421f239 045288e1e -- server/`): commit 045288e1e adds 11 tests to `FileTextIndexerEdgeCaseTests.kt` (5) and `FileTextJobTests.kt` (6), and updates the reports. `git diff --quiet 36421f239 045288e1e -- server/src/main` confirms that production code is unchanged. The delta review checked whether these tests are correct, whether they can be flaky, and whether they follow the conventions. It checked each test against the current `FileTextIndexer`, `FileTextJob` and `FileTextRepository.findPending` code.

Requirements:
- tasks/pdf-fulltext.md (acceptance criteria 1-2 and the recorded assumptions)
- SPEC.md §4.4 (global search: file names plus the text of PDFs and text files), §14
- docs/ARCHITECTURE.md (stack table: PDFBox 3.0.8 is listed), AGENTS.md project notes (logging, migrations, module conventions, fictitious test data)
- reviews/pdf-fulltext/review.md (round 2) and reviews/pdf-fulltext/fixes.md ("Second round")
- Parent instructions: third round, confirm that the previous findings are resolved, then a delta review of the tests in 045288e1e only

Git range:
- Branch: main
- HEAD: 045288e1e6c89ddc1d4c8b009794112f50d2d973
- Base/start: 3f51fea97c71a44352444480ce2bdc35e64e643d
- Commits reviewed: 7 (6 in the third round, plus 045288e1e in the delta)

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

## Delta review of 045288e1e (tests only)

Each new test was checked against the production path it covers:

- `FileTextIndexerEdgeCaseTests`
    - `an interruption wrapped as the cause ...`: `findPending` orders newest first (`created_at desc`). The test moves `after.txt` one hour back, so `wrapped.txt` comes first, and `interrupted()` finds the `ClosedByInterruptException` two levels down the cause chain. `indexPending() shouldBe 1` with both rows `null` proves that the batch stopped.
    - `an InterruptedIOException of the extractor stores nothing`: this covers the second type in `interrupted()`. The assertions are correct.
    - `an exception with a cyclic cause chain ...`: `outer.initCause(inner)` is legal because `IOException(String)` leaves the cause unset. The cycle is bounded by `take(MAX_CAUSES)`, and the blob exists, so the file is marked `FAILED`. The test would hang or fail without the bound.
    - `a batch started on an interrupted thread looks at no file`: this exercises the interrupt check at `FileTextIndexer.kt:73`. The `finally { Thread.interrupted() }` clears the flag on the JUnit thread, so later tests are not affected. The follow-up `indexPending() shouldBe 1` shows that the file stays pending.
    - `a read error between out of memory errors resets their count ...`: the mock sequence is OOM, OOM, `NoSuchFileException` (a `FileSystemException`, so the file goes into `unreadable` and `crashed` is cleared), then OOM, OOM, then success. Without the reset, the second `indexPending()` of the second pair would leave the file out and return 0 instead of throwing. The test therefore discriminates. The `MutableClock` advance by `UNREADABLE_RETRY_DELAY` makes the file eligible again.
- `FileTextJobTests`
    - The three `close` tests use a relaxed `ExecutorService` mock. They check the grace-then-`shutdownNow` order with `verifyOrder` and the exact `SHUTDOWN_GRACE` millis, the no-interrupt path, and the `InterruptedException` path, where `shutdownNow` runs and the interrupt flag is restored. The flag is cleared in `finally`.
    - `close with a plain executor only stops new ticks`: this covers the `as? ExecutorService ?: return` branch. A `run()` after close queues nothing.
    - `a run on an interrupted thread starts no batch` and `close during a run starts no further batch` both use the class's direct executor. They cover the two halves of `stopped()`. Each would fail without its check, because `indexPending` returns a nonzero value or is never mocked to stop.
- Flakiness: none of the new tests uses timing, sleeps or real threads. The interrupt-flag tests run JDBC (`findPending`) on an interrupted thread. Hikari only blocks, and could therefore see the interrupt, when no idle connection exists. That does not happen in these single-threaded tests, and the Postgres driver's socket I/O is not interruptible. The risk is negligible.
- Conventions: the test data is fictitious ("fictitious", "Flaky ostrowski"), the names follow the existing backtick style, and nothing is logged or suppressed. `./gradlew ktlintCheck` passes.
- Run: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` at 045288e1e reported BUILD SUCCESSFUL. The fulltext suites ran 71 tests with 0 failures, 0 errors and 0 skipped: FileTextJobTests 21, FileTextIndexerEdgeCaseTests 24, FileTextExtractorTests 15, FileTextDriveOperationsTests 7 and FileTextIndexerTests 4.

## Acceptance criteria check

1. "Text extracted (PDFBox) in a background job, size-limited, indexed; search finds it": implemented.
    - Background job: `FileTextJob` runs on a dedicated `file-text` thread, every 30 s, with a run limit of 50 batches and 5 minutes.
    - PDFBox: `FileTextExtractor` uses PDFBox with a temp-file stream cache.
    - Size limits: max-file-size, max-chars (matches `SearchIndexer.MAX_BODY_LENGTH` 100000) and max-pages.
    - Indexed: `FileTextIndexer.store()` writes `file_text` and the search document body under the file row lock. It writes only while the digest and the storage key are unchanged.
    - Search finds it: `FileTextIndexerTests`, `FileTextIndexerEdgeCaseTests` and `FileTextDriveOperationsTests` assert that `SearchService` finds the extracted words.
    - After a backup restore, `RestoreService` truncates every table, `file_text` included, so the texts are rebuilt from the blobs. This is tested and matches the recorded assumption.
2. "`./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` passes": this reviewer ran the exact command at 045288e1e. It passed with 71 tests and 0 failures, using Testcontainers Postgres through `PostgresTestBase`. The parent still has to record this with `tasks.py verify` before `done`.

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
- `FileTextDriveOperationsTests` leaves an entry in the shared indexer's in-memory `unreadable` map for a file it deletes afterwards. The ids are unique and the map only widens the `findPending` limit, so this does not affect other tests. The new delta tests either use their own `indexerWith(...)` instances or, in the interrupted-thread test, call the shared indexer only for a successful extraction, so they leave no such state behind.

## Review conclusion

No material issues found.
