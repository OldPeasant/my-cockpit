# Fix Report

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: findings CR-1..CR-5 of `reviews/files-storage/review.md` and the observations in `reviews/files-storage/tests.md`. Base commit 3ff1ad46f32daf1f2c6613517d588473b4919dbd, task commits 714e932, 5a04818, 35bec85, 0072d84, dbdebd4. Commit dbdebd4 held an untested partial fix (locking, 409 mapping, adoption, docs row); this fix pass reviewed it, completed it (CR-4 KDoc) and added the missing tests. The changes are uncommitted in the working tree: `FilesService.kt` (KDoc only) and the new `FilesConcurrencyTests.kt`.

## Fixed findings

### CR-1 - Upload racing a folder delete leaves an active file in a trashed folder, which purge deletes

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` (dbdebd4)
- `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/FilesRepositories.kt` (dbdebd4)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesConcurrencyTests.kt` (new)

Problem:
`storeFile` checked the folder without a lock, streamed the blob and then inserted the row. A `deleteFolder` committed in between missed the new file, which stayed active in a trashed folder, and `purgeFolder` then deleted it with its blob.

Fix:
Reviewed and kept the dbdebd4 design. After the blob is written, `storeFile` re-reads the target folder `FOR SHARE` and requires `deleted_at is null` (`FolderRepository.lockActiveForShare`, PESSIMISTIC_READ). `createFolder` does the same for the parent. `deleteFolder` locks the whole subtree `FOR UPDATE` in id order (`lockSubtree`, which repeats until no new folder appears) before it collects the contents. Under PostgreSQL READ COMMITTED, an insert that waits for a running delete has its WHERE re-evaluated on the new row version, gets no row and fails with `NotFoundException`, so the rollback hook removes the blob. A delete that waits for an insert sees the committed file and trashes it with the folder. As a second line of defence, `purgeFolder` deletes only rows in the Trash. Active folders or files found below a purged folder are moved to the root (folders get a free name) instead of being deleted. The share lock is taken only after the blob write, so the transfer holds no row lock.

Validation:
- New tests in `FilesConcurrencyTests`:
  - `an upload into a folder deleted while its blob is written fails and leaves neither row nor blob`: a blocking InputStream holds the upload between the check and the insert while the folder is deleted.
  - `a folder delete waits for an upload that passed the check, and trashes the new file with the folder`: the test waits for a `pg_stat_activity` lock wait, then checks that the file gets the folder's deletedAt and that purge removes it and its blob.
  - `purging a folder never deletes an active file or folder found below it`.
- Mutation check: with the post-write `lockActiveFolder` removed, both race tests fail. With the guard restored they pass.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'`: Pass

### CR-2 - Concurrent same-name folder creation surfaces as 500 instead of 409

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` (dbdebd4)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesConcurrencyTests.kt` (new)

Problem:
The sibling check and the insert/update were not atomic. A violation of the `folder_name` index came out as `DataIntegrityViolationException`, which `ProblemDetailsAdvice` maps to 500.

Fix:
`createFolder` uses `saveAndFlush`, and `renameFolder` calls `folders.flush()`. Both run inside `flushedName`, which turns `DataIntegrityViolationException` into `ConflictException`. I checked the transaction path. The repository call marks the shared transaction rollback-only, and `ConflictException` (a RuntimeException) then propagates out of the `@Transactional` service method. Spring rolls back and rethrows the `ConflictException` itself, with no `UnexpectedRollbackException`. Nothing else runs on the aborted PostgreSQL transaction after the violation. The tests confirm that callers receive `ConflictException`, which `ProblemDetailsAdvice` maps to 409.

Validation:
- New tests:
  - `two parallel creates of the same folder name give one folder and one conflict`: the second create waits on the unique index entry of the first, uncommitted create, then gets `ConflictException`.
  - `a rename racing a create of the same name is a conflict`.
- Mutation check: without `flushedName`, both tests fail.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'`: Pass

### CR-3 - Adopted user folder keeps its own spelling and emits no change event

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` (dbdebd4)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesConcurrencyTests.kt` (new)

Problem:
A user folder `attachments`/`TASKS` was adopted unchanged. Its path stayed non-canonical and could no longer be renamed, and clients were not notified.

Fix:
On adoption, the folder is renamed to `kind.folderName`, reindexed, and `ChangeOp.UPDATED` is published. The rename changes only letter case, so the case-insensitive unique index is not affected.

Validation:
- New test `an adopted user folder gets the special spelling, is reindexed and announced`: checks the names, `/Attachments/Tasks`, the search_document titles, exactly one UPDATED per adopted folder, and the 409 on rename.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'`: Pass

### CR-4 - Upload streaming holds a DB transaction and connection for the whole transfer

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` (KDoc of `storeFile`)

Problem:
`storeFile` streams the content inside its transaction, so a pooled connection is held for the whole upload.

Fix:
Documented in the `storeFile` KDoc that the content is read inside the transaction. Callers should pass content that is already buffered locally (a multipart upload stored in memory or a temp file), not a stream fed by a slow client. The KDoc also says that the folder row lock is taken only after the blob write. I did not split the method into a non-transactional write plus a short transaction. With the class-level `@Transactional` and callers that may join an outer transaction, that is not a small or low-risk change. The parent explicitly allowed documentation as the minimum fix.

Validation:
- `cd server && ./gradlew check`: Pass (KDoc-only change, ktlint clean)

### CR-5 - ARCHITECTURE.md says COCKPIT_DATA_DIR is "not yet used"

Files changed:
- `docs/ARCHITECTURE.md` (dbdebd4)

Problem:
The env table row was out of date.

Fix:
The row now gives `cockpit.data-dir`, the default `data` relative to the working directory, the blob layout `blobs/ab/cd/<uuid>` (`files.infra.BlobStore`), and that tests use `server/build/test-data`. I checked it against `application.properties` (`${COCKPIT_DATA_DIR:data}`).

Validation:
- Read-only check of `docs/ARCHITECTURE.md` against `server/src/main/resources/application.properties`: consistent

### tests.md observations (no production bugs reported)

The test report lists two observations: concurrent same-name creates give `DataIntegrityViolationException`, and adoption keeps the spelling with no event. They are the same issues as CR-2 and CR-3 and are fixed above.

## Not fixed

None. One residual edge, not a reported finding: a user `createFolder("Attachments")` that commits first while the very first `specialFolder(ATTACHMENTS)` call is inserting. The user side now gets 409. The special-folder side can still get a `DataIntegrityViolationException` at flush, because it cannot retry inside a transaction that is already rollback-only. This can only happen once, on the first use of special folders. A retry fixes it, and the database keeps the invariant. I left it unchanged to stay within scope.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun`: Pass (83 tests, including 6 new in `FilesConcurrencyTests`)
- Mutation run (guards temporarily disabled, then restored from a backup): 4 of 6 new tests fail as expected. The other 2 cover purge and adoption, which the mutation did not touch.

Full test suite:
- `cd server && ./gradlew check`: Pass (BUILD SUCCESSFUL in 4m 18s; JUnit XML: 1144 tests, 0 failures, 0 errors, 0 skipped)

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` (no remaining changes), with ktlintCheck run as part of `./gradlew check`: Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "== all checks passed": server, web, android)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
