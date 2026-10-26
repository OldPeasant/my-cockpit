# Code Review

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: f38fd7b668a33021b3c6305569275df8edf0b3d5..HEAD (task code commit d29130b83 "feat(files): full-text search in PDFs and text files", plus two `assume` commits that touch only `tasks/`). Committed changes only. The working tree was clean.

Requirements:
- tasks/pdf-fulltext.md (acceptance criteria 1-2, the two recorded assumptions)
- SPEC.md §4.4 (search), §8.2 (files), §14 (link/search/backup coverage)
- docs/ARCHITECTURE.md (stack table, conventions), AGENTS.md project notes
- Parent instructions: correctness, concurrency (rename/trash/purge vs job), resource safety with hostile PDFs, logging, backup/restore interplay, conventions

Git range:
- Branch: main
- HEAD: d29130b833f0e454f1b8193214d4fef6a75a9c3a
- Base/start: f38fd7b668a33021b3c6305569275df8edf0b3d5
- Commits reviewed: 3 (1 code commit, 2 task-metadata commits)

Summary: 0 P0, 1 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Long-running PDF extraction runs on the single shared scheduler thread and blocks reminders and syncs**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt:32-45`
    - Category: Integration
    - Problem: `FileTextJob.run` is a plain `@Scheduled` method. No `spring.task.scheduling.*` setting, virtual-thread switch or custom `TaskScheduler` exists in `server/src/main/resources/application*.properties` or the code, so Spring Boot's default scheduler with a pool size of 1 runs it. That thread also runs `ReminderScheduler` (every 15 s), `CalendarSyncScheduler`, the contacts syncs, `MailSearchReconciler`, IMAP pool eviction and `TrashPurgeJob`. One run processes up to `MAX_BATCHES_PER_RUN` (50) x `batch-size` (20) = 1000 files. Each can be a PDF of up to 20 MB and 500 pages, parsed by PDFBox with no time limit. A backlog of that size is normal: the first start after this migration (every existing file is pending) and every backup restore (the wipe truncates `file_text`). During such a run, or on one slow or crafted PDF, due reminders don't fire and syncs don't run, for minutes or until the extraction ends.
    - Why it matters: Reminders are time-critical. A user upload, or a mail attachment saved to the drive, can delay unrelated core features with no limit, and nothing in the logs explains why.
    - Suggested fix: Run the extraction off the shared scheduler thread. For example, the scheduled method hands work to a dedicated single-thread executor and skips the run while one is still busy. Alternatively, give the app a scheduler pool larger than 1, as long as other jobs don't assume serial execution. Also bound one run by wall-clock time, not only by batch count, and consider a per-file time budget.

## P2 Medium

- [ ] **CR-2 | A `java.lang.Error` from PDFBox makes one file a poison pill that stops extraction of all older files**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt:48`, `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt:41`
    - Category: Correctness
    - Problem: `index` catches only `Exception`, and the job catches only `RuntimeException`. A `StackOverflowError` (deeply nested objects or recursive resources in a crafted PDF) or an `OutOfMemoryError` escapes both, so no `FAILED` row is written. Pending files are ordered `created_at desc` (`FileTextRepository.findPending`). After newer files are processed, the poison file is first in every batch, and each run aborts on it before any older file. Every 30 s the same file is parsed again and the scheduler logs the error.
    - Why it matters: One hostile PDF permanently disables full-text indexing of every file uploaded before it (criterion 1, "search finds it"), and it burns CPU on every run.
    - Suggested fix: Catch at least `StackOverflowError` (or `Throwable` minus `VirtualMachineError`s you choose to rethrow) per file and store `FAILED`. Alternatively, record an attempt before extracting (for example a `FAILED` row with the file's sha256, written first and overwritten on success), so a crash never retries the same contents. Add a test with a stub extractor that throws an `Error`.

- [ ] **CR-3 | Lock order between the job and FilesService delete/restore can deadlock or lose the extracted body**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt:67-71`, interacting with `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:266-267`, `:173-176`, `:331-334`
    - Category: Correctness
    - Problem: `store()` locks the `file` row `FOR UPDATE` first, then upserts `search_document`. `deleteFile` and `deleteFolder` do it the other way round. They mark the entity trashed (the JPA update is flushed only at commit), call `search.delete` right away (JDBC, which takes the `search_document` row lock), and only then issue the `UPDATE file` at commit. Interleaved with `store()`, the job waits on the search row and the delete waits on the file row, so Postgres aborts one of them. If it aborts the user's delete, the user gets a server error. The class comment's claim that "a rename, move or delete waits" holds only for `updateFile`, which flushes before it indexes. `restoreFile` doesn't flush before `indexed(file)` either, so its `textOf` can read the state before the job commits. The search document then gets a `null` body while `file_text` holds a matching row, and the file is never re-indexed.
    - Why it matters: These are rare (the job's store transaction is short), but they are user-visible failures (a failed delete) or silent loss of content search for a file.
    - Suggested fix: Take the locks in the same order everywhere: in `deleteFile`/`deleteFolder`/`restoreFile`, flush (or lock the file row) before touching `search_document`, as `updateFile` already does with `files.flush()`. Alternatively, in `store()`, lock the search document as well in the same order. A small edit to FilesService is already accepted in the task's assumption.

## P3 Low

- [ ] **CR-4 | Transient read errors are recorded as permanent `FAILED`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt:47-52`
    - Category: Correctness
    - Problem: Every exception is stored as `FAILED` for the file's sha256, and `findPending` never picks that file again. That includes I/O errors reading the blob (for example the data directory being swapped during a restore, which keeps file ids and sha256 but changes the storage key, or a transient disk error). `store()` compares only the sha256, not `storage_key`, so a failure while reading the old blob is attached to the restored row.
    - Why it matters: Such a file is never content-searchable again, with no way to recover except changing its contents.
    - Suggested fix: Store `FAILED` only for parse failures (PDFBox exceptions, `InvalidPasswordException`). Leave I/O errors on the blob pending, or retry `FAILED` rows after a backoff using `extracted_at`. Also compare `storage_key` in `store()`.

- [ ] **CR-5 | Character and page limits and the post-restore rebuild are not tested**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerTests.kt`
    - Category: Tests
    - Problem: The tests cover the file-size limit, unsupported types and broken PDFs, but nothing exercises `max-chars` (the `BoundedWriter.Full` path in `FileTextExtractor.kt:63-82`, and the text-file byte-limit and UTF-8 boundary logic) or `max-pages`. Nothing checks that text becomes searchable again after a backup restore, which the recorded assumption relies on ("rebuilt from blobs after a restore"). `FileTextJob.run` is also untested.
    - Why it matters: "Size-limited" is part of criterion 1, and the truncation code has off-by-one-prone boundary logic.
    - Suggested fix: Add extractor tests with small `max-chars`/`max-pages` property values (a multi-page PDF, a multi-byte UTF-8 text cut at the boundary). Add one indexer test that truncates `file_text` (or runs a restore) and asserts that the text is found again after `indexPending`.

## Notes (not findings)

- Logging follows the conventions: only file ids and exception class names. No file names, texts or exception messages are logged.
- Backup/restore: `file_text` is a cache with `on delete cascade`. `RestoreService.wipeDatabase` truncates every table, so the job re-extracts after a restore, which matches the assumption. The backup round trip is unaffected.
- Purge vs job: purging deletes the row (cascade), and `lockFile` then returns `null`, so the job skips it. Trash vs job: inactive files get no search document. Rename vs job: safe in both orders, because `updateFile` flushes before `textOf`.
- PDFBox runs with a temp-file-only stream cache, outside any transaction, so no connection is held during parsing. Encrypted PDFs with a user password fail cleanly.
- The acceptance criteria had no recorded `verify` evidence yet in `tasks/pdf-fulltext.md` at review time. The parent worker must record it.
- The parent named the task commit as d32fc7810, but the commit in the reviewed range is d29130b83 (same subject). This review covers d29130b83.

## Review conclusion

Material issues found; fix before completing the task.
