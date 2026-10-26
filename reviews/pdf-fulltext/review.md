# Code Review

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: Committed range 7294e9c62762ab22230acf6142206e60106b94ae..550f26737, as named by the parent worker: implementation ce35cbb45, tests 808b28fd1, review fixes 550f26737. Committed code only. The fix of the previous review (CR-1 to CR-5) was checked against the current files. Note: the local `main` ref has since moved to 20cedc1e6 (other agents' commits, such as heartbeats or concurrent test work). Those commits are not part of this review. Bash was not available to this reviewer, so the files were read directly at their current state, not through `git diff`. The parent reported the working tree as clean, apart from a test-writer that may be working in the fulltext test folder.

Requirements:
- tasks/pdf-fulltext.md (acceptance criteria 1-2, the three recorded assumptions)
- SPEC.md §4.4 (global search, file text), §14 (link/search/backup coverage)
- docs/ARCHITECTURE.md (stack table: PDFBox 3.0.8 is listed), AGENTS.md project notes (logging, lint, module conventions)
- reviews/pdf-fulltext/review.md (previous revision) and reviews/pdf-fulltext/fixes.md
- Parent instructions: the dedicated executor, the in-memory retry timer, the FilesService lock-order changes, shutdown, logging, conventions

Git range:
- Branch: main
- HEAD: 550f26737 (reviewed end of range; local main is now 20cedc1e64b56b395c1efe361fae600f2299014c)
- Base/start: 7294e9c62762ab22230acf6142206e60106b94ae
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## Previous findings

- CR-1 (shared scheduler thread): resolved. `FileTextJob.run` (`FileTextJob.kt:49-63`) only hands work to a dedicated single daemon thread named `file-text`. An `AtomicBoolean` skips ticks that overlap a busy run, and the flag is cleared in `finally` and when the hand-off is rejected. Each run is bounded by 50 batches and 5 minutes. Tests cover the hand-off, the skip, the rejection and the thread name in a real context.
- CR-2 (Error poison pill): resolved for `StackOverflowError` (`FileTextIndexer.kt:74-78`), with a test. Rethrowing other `VirtualMachineError`s is a deliberate choice, recorded in the task assumptions. See CR-3 below for how those are logged.
- CR-3 (lock order): resolved for delete and restore. `deleteFile`, `deleteFolder`, `restoreFile` and `restoreFolder` call `files.flush()` (which takes the `file` row lock through the UPDATE) before they touch `search_document` or read `file_text`. That matches `FileTextIndexer.store()` (file row `FOR UPDATE`, then search upsert). One remaining path is reported as CR-2 below.
- CR-4 (transient errors permanent): resolved for `FileSystemException`, `FileNotFoundException` and unreadable paths. These go into a 15-minute in-memory retry map, and `store()` now also compares `storage_key`. The tests cover the retry and the replaced blob. CR-1 below describes a remaining path that still produces a permanent `FAILED`.
- CR-5 (tests): resolved. There are extractor limit tests (max-chars, the UTF-8 boundary, max-pages) and a test for the rebuild after `file_text` is wiped.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Shutdown during an extraction can permanently mark a healthy file `FAILED`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt:77-79`, `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt:87-99`
    - Category: Correctness
    - Problem: `close()` calls `shutdownNow()`, which interrupts the `file-text` thread. `close()` does not wait for the thread to end. The extractor reads text files with `Files.newInputStream` (`FileTextExtractor.kt:55`), which is backed by an interruptible `FileChannel`. PDFBox 3 reads the PDF through `RandomAccessReadBufferedFile`, which is also channel-based. An interrupt during the read throws `ClosedByInterruptException`. That is an `IOException`, but not a `FileSystemException` or a `FileNotFoundException`, and the blob is still readable. So `failed()` takes the parse-failure branch and calls `store(file, FAILED, null)`. The context closes `FileTextJob` before the beans it depends on (indexer, DataSource), and `shutdownNow()` returns right away. So the store transaction often still finds a pooled connection and commits. `findPending` never returns a file with a `FAILED` row for its sha256 again.
    - Why it matters: Restarts and deploys are routine. They are most likely during a long backlog (the first start after this migration, or after every backup restore, which truncates `file_text`). Each one can make an arbitrary valid file's contents unsearchable for good. The only way to recover is to change the file's contents. This is the same kind of silent loss that the previous CR-4 fixed for I/O errors.
    - Suggested fix: Never store a result for an interrupted extraction. In `failed()`, return without a row when `Thread.currentThread().isInterrupted` is set or the cause is `ClosedByInterruptException`/`InterruptedIOException`, and stop the batch. Alternatively, stop the job cooperatively: use `shutdown()` plus a stop flag checked between files, and optionally a short `awaitTermination` in `close()`, so no thread keeps writing while the DataSource closes. Add a test in which the extractor throws `ClosedByInterruptException` (or the thread is interrupted), and assert that the file gets no row.

## P3 Low

- [ ] **CR-2 | A rename that changes nothing reads `file_text` without the file row lock and can overwrite the extracted body with `null`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:243-260`, `:462`
    - Category: Correctness
    - Problem: `updateFile` relies on `files.flush()` to take the `file` row lock before `indexed(file)` reads `texts.textOf` and upserts the search document. When the request changes nothing (a rename to the same name, a move to the current folder, or a PATCH with neither field), Hibernate finds no dirty state and issues no UPDATE, so no lock is taken. If the job's `store()` runs at the same time, `textOf` reads before the job commits and gets `null`. The `search.upsert` then waits on the job's `search_document` row lock and overwrites the body with `null` after the job commits. `file_text` now holds an EXTRACTED row for the current sha256, so the file is never picked again, and its contents stay unsearchable until a real rename or restore. The window is small, but the loss is silent and permanent.
    - Why it matters: Same lock-order invariant as the previous CR-3. The fix covered the paths that always write, not the one that may not.
    - Suggested fix: Lock the row explicitly before reading the text in `indexed(file: FileItem)`, for example `select 1 from file where id = ? for update` (or a `FileRepository` lock method), or always lock in `updateFile`. Then the order "file row, then file_text/search_document" holds whatever the dirty state.

- [ ] **CR-3 | Errors other than `StackOverflowError` escape the job unlogged and are retried on every tick**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt:65-75`
    - Category: Quality
    - Problem: `extract()` catches only `RuntimeException`. An `OutOfMemoryError` (or a `LinkageError`) from PDFBox propagates out of the runnable. The `finally` frees the job, the executor's worker thread dies and is replaced, and the JDK's default uncaught-exception handler prints the stack trace to `System.err`. It goes neither through SLF4J nor through the structured (logstash) logging. The fix report deliberately leaves the file pending. As a result, the same file (newest first in `findPending`) is parsed again every 30 s and fails the same way, which stops extraction of all older files. Nothing in the application log names the file id.
    - Why it matters: Rethrowing is a defensible policy, but the operator then needs a log line to find the file. Today the only trace is unstructured stderr output, every 30 s.
    - Suggested fix: In `extract()` (or the runnable), catch `Error` and log it at WARN/ERROR with the exception class through the logger before rethrowing. The indexer could log the file id it was working on. Optionally, after repeated `OutOfMemoryError`s for the same file id, add it to the in-memory skip map, as is done for unreadable blobs.

## Notes (not findings)

- Logging follows the conventions. The INFO lines name only the file id and the exception class, never names, texts or exception messages.
- The in-memory retry map (`FileTextIndexer.kt:48-64`) is a `ConcurrentHashMap` on a singleton. Expired entries are removed on every call, and the `findPending` limit is widened by the number of waiting files, so skipped files can't starve the batch. It is lost on restart, which only means an earlier retry. That is acceptable.
- Purge, trash and rename racing the job are covered deterministically by `FileTextIndexerEdgeCaseTests`. The delete/restore lock-order fix has no concurrent test. It was checked by reading the code (flush before the search write, under READ COMMITTED).
- `tasks/pdf-fulltext.md` still shows both acceptance criteria unticked in this revision. The parent must record `verify` evidence (criterion 2 needs the actual `./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` run with Testcontainers).

## Review conclusion

Material issues found; fix before completing the task.
