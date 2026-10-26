# Fix Report

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: Findings CR-1 to CR-5 of `reviews/pdf-fulltext/review.md`. Changes are limited to `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/`, `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` and the tests in `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/`. Base commit dc2d00636, task commits 191a2b5a3 and c729586e4.

## Fixed findings

### CR-1 - Long-running PDF extraction runs on the single shared scheduler thread

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJobTests.kt`

Problem:
`FileTextJob.run` did all its extraction on Spring's default scheduler, which has one thread. Reminders, syncs and the other jobs share that thread, so a backlog of up to 1000 files, or one slow PDF, held them up.

Fix:
The `@Scheduled` `run()` now only hands the work to the job's own `Executor`. The configuration gives it a single daemon thread named `file-text` (`FileTextJob.newExecutor()`). An `AtomicBoolean` makes a tick do nothing while the previous run is still busy. The flag is cleared in `finally`, and also when the executor rejects the task during shutdown. Besides the 50-batch limit, each run is now bounded by time: no new batch starts once `MAX_RUN_TIME` (5 min) has passed, measured with an injectable `nanoTime`. `close()` shuts the executor down when the context closes (Spring infers `close` as the destroy method). There is no per-file time budget, because PDFBox can't be interrupted safely. The dedicated thread already protects the other jobs.
New tests cover the time limit, a tick skipped while busy, the job being freed after a run throws, the job being freed after a rejected hand-off, `close()` shutting the executor down, and extraction running on the `file-text` thread in the configured context. The existing loop tests now use an executor that runs the task immediately.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass (FileTextJobTests: 13 tests)

### CR-2 - A `java.lang.Error` from PDFBox makes one file a poison pill

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerEdgeCaseTests.kt`

Problem:
`index` caught only `Exception`. A `StackOverflowError` from a crafted PDF escaped, so no row was written and every later run aborted on that same file.

Fix:
`index` now catches `StackOverflowError` as well as `Exception` and stores the file as `FAILED`. Other `VirtualMachineError`s (for example `OutOfMemoryError`) and linkage errors are still rethrown on purpose. They usually mean the JVM or the deployment is unhealthy, not that one file is bad, and recording them as `FAILED` would wrongly make files unsearchable for good. A test documents this.
New tests: a stub extractor throws `StackOverflowError` for one file, which becomes `FAILED` while the other file in the batch is still extracted. Another stub throws `OutOfMemoryError`, which propagates and leaves the file pending.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass

### CR-3 - Lock order between the job and FilesService delete/restore

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt`

Problem:
`deleteFile`, `deleteFolder` and `restoreFile` wrote `search_document` before the `file` row update was flushed. That is the opposite order to `FileTextIndexer.store()`, which locks the file row first. The result was a possible deadlock, or a restore that read `file_text` before a running extraction committed and indexed a `null` body.

Fix:
`deleteFile`, `deleteFolder` and `restoreFile` now flush (`files.flush()`) right after changing the entities and before any search write or `textOf` read, as `updateFile` already did. `deleteFolder` trashes the whole subtree first, then flushes, then removes the search documents. `restoreFolder` had the same pattern, so it gets the same change: restore all rows, flush, then index. After a restore waits on the job's file-row lock, its `textOf` runs as a new statement under READ COMMITTED and sees the committed text.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass (all 296 files tests, including the existing trash, restore and purge tests)
- `./gradlew check` - Pass

### CR-4 - Transient read errors recorded as permanent `FAILED`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextRepository.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerEdgeCaseTests.kt`

Problem:
Every extraction exception was stored as `FAILED` and never retried, including I/O errors on the blob. `store()` compared only the sha256, so a failure on an old blob could be attached to a row whose blob had since been replaced.

Fix:
A failure counts as a blob read error when it is a `FileSystemException` (for example `NoSuchFileException` or `AccessDeniedException`) or a `FileNotFoundException`, or when the blob is no longer readable. Such a file gets no row. The indexer keeps it in memory and skips it for `UNREADABLE_RETRY_DELAY` (15 min, measured with the injected `Clock`), then tries it again. These skipped files are not counted in `indexPending()`, so they can't starve older files or keep the job's loop running. Parse failures are still stored as `FAILED`. `LockedFile` now carries `storage_key`, and `store()` drops the result when either the sha256 or the storage key changed meanwhile.
Test change: `a file whose blob is missing is marked failed and not tried again` asserted the old behaviour that CR-4 asks to change. It is replaced by `a file whose blob is missing gets no row, does not block other files, and is tried again later`. The new test checks that the file gets no row, that an older file in the same batch is still extracted, that the file is not retried before the delay, and that it is extracted once the blob is back and the delay has passed. A new test also checks that text read from a blob replaced meanwhile (storage key changed) is dropped and extracted again.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass

### CR-5 - Post-restore rebuild not tested

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerEdgeCaseTests.kt`

Problem:
The review also named tests for the character limit, page limit and job. Those were added in c729586e4 (`FileTextExtractorTests`: max-chars for text and PDF, UTF-8 boundary, max-pages; `FileTextJobTests`), so that part was already covered. What was still missing was a test that text becomes searchable again after `file_text` is wiped, as happens in a backup restore.

Fix:
Added `after the extracted texts are wiped (backup restore) they are rebuilt from the blobs and found again`. The test truncates `file_text`, rebuilds the search document from the name only, checks that the content no longer matches, runs `indexPending()`, and then checks that the content is found again.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass (no remaining changes)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass: 296 tests, 0 failures, 0 skipped (fulltext: Extractor 15, IndexerEdgeCase 15, Job 13, Indexer 4)

Full test suite:
- `cd server && ./gradlew check` (ktlint, detekt and all server tests) - BUILD SUCCESSFUL in 8m 39s

Lint/type-check/build:
- Included in `./gradlew check` - Pass

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` also covers web and android). The parent worker runs it. The server part (`./gradlew check`) passed.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

# Second round

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: Findings CR-1 to CR-3 of the second review round (`reviews/pdf-fulltext/review.md`, reviewed up to 550f26737). The fixes are in commit 7904fb972, made by an earlier fixer run that ended before it wrote this report. This run checked that commit against each finding and against the current code, and found nothing missing, so it changed no code. Base commit 3f51fea97, task commits 53918d98e..7904fb972.

## Fixed findings

### CR-1 (P2) - Shutdown during an extraction can permanently mark a healthy file `FAILED`

Files changed (7904fb972):
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJobTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerEdgeCaseTests.kt`

Problem:
`close()` called `shutdownNow()`, which interrupted the `file-text` thread without waiting for it. An interrupted channel read throws `ClosedByInterruptException`. That is an `IOException`, so `failed()` treated it as a parse failure and stored a permanent `FAILED` row, possibly while the DataSource was closing.

Fix:
The fix covers both options the review suggested.
- The indexer stores nothing for an interrupted extraction. `failed()` returns without a row and stops the batch when the thread is interrupted, or when the cause chain (searched up to 10 levels) holds a `ClosedByInterruptException` or `InterruptedIOException`. `indexPending()` also checks the interrupt flag before each file.
- The job stops cooperatively. A `stopping` flag makes later ticks do nothing, and the batch loop stops between batches. `close()` calls `shutdown()`, waits `SHUTDOWN_GRACE` (5 s) for the running extraction, and only then calls `shutdownNow()` and waits again. The job depends on the indexer and through it on the DataSource, so Spring destroys the job first, and no thread keeps writing while the pool closes.

Tests: `an extraction interrupted by the shutdown stores nothing and stops the batch` (the extractor throws `ClosedByInterruptException`: no row for this file or the next one in the batch, and both are extracted on a later run), `an extraction failing while the thread is interrupted stores nothing`, `after close a tick starts nothing`, and `close lets the running extraction finish without interrupting it and starts no further batch`.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass

### CR-2 (P3) - A rename that changes nothing reads `file_text` without the file row lock

Files changed (7904fb972):
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerEdgeCaseTests.kt`

Problem:
A no-op `updateFile` (same name, same folder) made Hibernate issue no UPDATE, so `files.flush()` took no row lock. `indexed(file)` could then read `textOf` before a concurrent `FileTextIndexer.store()` committed, and overwrite the search body with `null` for good.

Fix:
`indexed(file: FileItem)` calls `texts.lockFile(file.id)` before `texts.textOf(file.id)`. `FileTextRepository.lockFile` already existed: `store()` uses it. It runs `select ... from file where id = ? for update` and returns `null` for a missing row. So every path that indexes a file now takes the file row lock before it reads `file_text` and writes `search_document`, whatever the dirty state. This is the same order `store()` uses. For a newly stored file that has not been flushed yet, `lockFile` returns `null` without an error, and `textOf` is `null` anyway, so nothing changes there.

Test: `a rename that changes nothing waits for a running extraction and keeps its text`. It holds the file row lock and writes text as `store()` does, starts a same-name rename concurrently, and checks that the rename waits and that the text is still found afterwards. This run checked that the test catches the bug: with the `lockFile` call temporarily removed, the test fails. The mutation was reverted, and the working tree is unchanged.

Validation:
- `./gradlew test --tests '*FileTextIndexerEdgeCaseTests*rename that changes nothing*'` without the lock - Fail (as expected). With the lock - Pass, as part of the full files run.

### CR-3 (P3) - Errors other than `StackOverflowError` escape the job unlogged

Files changed (7904fb972):
- `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexerEdgeCaseTests.kt`

Problem:
An `OutOfMemoryError` or `LinkageError` from PDFBox escaped the job. It reached stderr through the JDK default handler, without the file id and outside SLF4J. The same newest file was parsed again on every tick, which blocked all older files.

Fix:
`index()` catches any other `Error`. It logs the error at ERROR through SLF4J with the file id, the exception class and the attempt number, never the message or contents, and then rethrows it, so the "file stays pending" policy is kept. The count is kept per file in memory. After `MAX_ERROR_ATTEMPTS` (3) such errors, the file is left out of `indexPending()` until the next start, so older files go on. A successful extraction or an ordinary failure clears the count.

Test: `a file whose extraction keeps running out of memory is left out after repeated failures, older files go on`. It checks the 3 rethrows, that the older file is then extracted, and that a new indexer (a restart) tries the file again.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.files.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - Pass (nothing reformatted)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` (Testcontainers Postgres) - BUILD SUCCESSFUL: 309 tests, 0 failures, 0 errors, 0 skipped (fulltext: Job 15, IndexerEdgeCase 19, Extractor 15, DriveOperations 7, Indexer 4)
- CR-2 mutation check: the regression test fails without `texts.lockFile` - confirmed, then reverted

Full test suite:
- `cd server && ./gradlew check` (ktlint, detekt and all server tests) - BUILD SUCCESSFUL in 8m 43s: 4894 tests, 0 failures, 0 errors, 0 skipped

Lint/type-check/build:
- Included in `./gradlew check` - Pass

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` also covers web and android). The parent worker runs it. The server part passed.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
