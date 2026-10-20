# Code Review

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: re-review of the final code after fixes, committed changes only: `git diff d373732efc3d32affba8c45cb78fe2870ebcd90d..e2876a8e61f1bd0312b0929d1fe25b1c4f2e7608 -- . ':!tasks' ':!reviews'`. That covers the files module main and test code, the Flyway migration `files/V202610201345__files_model.sql`, `cockpit.data-dir` in the main and test `application.properties`, `server/.gitignore` and the `COCKPIT_DATA_DIR` row in `docs/ARCHITECTURE.md`. The working tree was clean.

Reviewed revision: e2876a8e61f1bd0312b0929d1fe25b1c4f2e7608

Requirements:
- tasks/files-storage.md: acceptance criteria and recorded assumptions
- SPEC.md §8.2, §4.2 (Trash), §4.3 (resolvers), §4.4 (search), §11 (COCKPIT_DATA_DIR)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- core contracts: `TrashContributor` ("an item that is not deleted is never purged"), `TrashService`, `TrashPurgeJob`, `ItemResolver`, `SearchIndexer`, `SoftDeletableEntity`/`BaseEntity` (`@Version`)
- previous review (CR-1..CR-5), reviews/files-storage/fixes.md, reviews/files-storage/tests.md

Git range:
- Branch: main
- HEAD: e2876a8e61f1bd0312b0929d1fe25b1c4f2e7608
- Base/start: d373732efc3d32affba8c45cb78fe2870ebcd90d
- Commits reviewed: 7 (396a5c2, 6d3f3fd, 92e0837, d3ffb0b, 9cd6132, 0c463cc, e2876a8)

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun ktlintCheck` -> BUILD SUCCESSFUL. JUnit XML: FilesServiceTests 24, FilesConcurrencyTests 8, FilesEdgeCaseTests 23, FileNamesTests 16, BlobStoreTests 5, FileIconTests 10. That is 86 tests, 0 failures, 0 errors, 0 skipped (Postgres via Testcontainers). ktlintCheck clean.
- Read-only inspection with `git log`, `git diff`, `grep` and file reads of the core trash/persistence contracts.

Prior findings:
- CR-1 (an upload racing a folder delete, then a purge deletes an active file): resolved. `storeFile` and `createFolder` re-check the target folder under `FOR SHARE` (`lockActiveForShare`, PESSIMISTIC_READ, `deleted_at is null` in SQL, so it is re-evaluated after waiting). `deleteFolder` locks the subtree `FOR UPDATE` in id order and repeats until no new folder appears. `purgeFolder` deletes only trashed rows and moves active ones to the root. Covered by four lock-wait tests (`pg_stat_activity`) and a purge-rescue test.
- CR-2 (a same-name race gives 500): resolved for `createFolder` and `renameFolder` (`flushedName` maps `DataIntegrityViolationException` to `ConflictException`). Tests cover create/create and rename/create. CR-4 below lists the paths that are still unmapped.
- CR-3 (adoption spelling and event): resolved. The folder is renamed to `kind.folderName`, reindexed, and an `UPDATED` event is published. The test checks this.
- CR-4 (upload holds a connection): accepted as documented. The `storeFile` KDoc tells callers to pass buffered content. The share lock is taken only after the blob write.
- CR-5 (docs row): resolved. The row matches `cockpit.data-dir=${COCKPIT_DATA_DIR:data}` and the test value `build/test-data`.

Acceptance criteria check:
1. Migrations and atomic blobs: met. `folder` has the tree and a unique `(parent_id, lower(name)) nulls not distinct where deleted_at is null`. `file` has name, mime, size, sha256 and storage_key. `BlobStore.write` writes to a temp file, runs `force(true)`, then does an `ATOMIC_MOVE` to `blobs/ab/cd/<uuid>`.
2. Special folders: met. They are created on demand under an advisory lock, a user folder in that place is adopted with the canonical spelling, and rename/delete return 409.
3. Resolvers, search and trash contributor: met. The purge deletes blobs after commit, and a rollback of a store removes its blob. CR-1 below describes a concurrency gap in purge.
4. Tests pass, including path traversal: met. 86 of 86 pass. They cover `..`, `.`, separators and control characters at the `FileNames` and service level, plus key traversal in `BlobStore.path`.

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Purge racing a restore of the same item permanently deletes the restored (active) file and its blob**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:298-303` (`purgeFile`), `:264-266` (`purgeFolder`); `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/FilesRepositories.kt:83-84` (`FileRepository.deleteAllByIds`)
    - Category: Correctness
    - Problem: `purgeFile` checks that the file is a trash root with a plain read (`trashedFiles()`, no lock). It then runs a bulk JPQL `delete from FileItem f where f.id in :ids` with no `deletedAt is not null` condition and no version check, and deletes the blob after commit. A `restoreFile` of the same file that commits between the check and the delete (a user clicks Restore in one tab and Delete forever in another, or restores at the moment `TrashPurgeJob` purges the expired item) is overwritten. The file the user just restored is deleted permanently, together with its blob, and the restore had already reported success. `purgeFolder` has the same gap for the root folder. The root is loaded unlocked by `trashedFolders()`, and the later `lockSubtree` query returns the same managed (stale) instance, so the root still appears deleted and is bulk-deleted. Its restored children are loaded fresh and survive only through `rescue`, which scatters them to the drive root. The locking added for CR-1 covers insert-vs-delete but not purge-vs-restore.
    - Why it matters: it breaks the `TrashContributor.purge` contract ("an item that is not deleted is never purged") and the stated guarantee in the `purgeFolder` KDoc ("Only rows in the Trash are ever purged"). The result is silent loss of a file the user explicitly restored. The window is narrow, but the loss is permanent.
    - Suggested fix: in `purgeFile`, lock the row `FOR UPDATE` and re-check `deleted_at` in SQL (for example a PESSIMISTIC_WRITE query `where id = :id and deletedAt is not null`), or make the bulk delete conditional (`... and f.deletedAt is not null`) and return `false` when 0 rows were affected. In `purgeFolder`, decide on the root after `lockSubtree` from fresh state, for example a locked query with `deletedAt is not null` or `entityManager.refresh`. Then a racing restore either waits or causes a 404. Add a lock-wait test in `FilesConcurrencyTests`: restore holds its transaction open, purge waits, and the file must survive.

## P3 Low

- [ ] **CR-2 | Restore does not lock the parent, so an item can be restored into a folder that is being deleted**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:229-253` (`restoreFolder`, `restoreFile`)
    - Category: Correctness
    - Problem: `storeFile` and `createFolder` now lock their target folder `FOR SHARE`, but the restore paths do not lock the parent. Take a `restoreFile` (or `restoreFolder`) whose parent is deleted concurrently. The delete's `FOR UPDATE` on the subtree does not touch the file row, and it does not change the already-trashed folder row it locks. The restore therefore commits an active item under a trashed folder. That item is searchable and resolvable while its folder is in the Trash, and it is not in the Trash itself. When the parent is purged, `rescue` moves it to the root, so no data is lost.
    - Why it matters: it leaves the tree inconsistent in the same way the CR-1 fix of the previous review was meant to prevent. It is a narrow race and recoverable.
    - Suggested fix: in `restoreFolder` and `restoreFile`, call `lockActiveFolder(parentId)` when the parent is not the root, after finding the trash root. The parent is active by definition of a trash root, so a racing delete then makes the restore wait and fail with 404.

- [ ] **CR-3 | Some unique-name races still surface as 500 instead of 409**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:209` (`specialFolder`, `folders.save` without flush or mapping), `:232` (`restoreFolder`, `freeName` then a flush at commit), `:309` (`rescue`)
    - Category: Correctness
    - Problem: `flushedName` covers only `createFolder` and `renameFolder`. A user `createFolder("Attachments")` that commits during the first `specialFolder(ATTACHMENTS)` call still fails the special-folder side with `DataIntegrityViolationException` at commit. fixes.md records this as a residual. The same happens when a folder is restored and another folder takes its `freeName` result concurrently. `ProblemDetailsAdvice` maps these to 500.
    - Why it matters: the database keeps the invariant and a retry succeeds, but clients get a server error instead of the documented 409.
    - Suggested fix: flush inside `flushedName` in `restoreFolder` too. For `specialFolder`, also take the advisory lock in `createFolder` when `parentId` is null and the name matches `Attachments` case-insensitively, or retry once in a new transaction. Otherwise record the residual with `tasks.py assume`.

- [ ] **CR-4 | Two overlapping folder deletes can deadlock across `lockSubtree` rounds**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:329-337`
    - Category: Correctness
    - Problem: each `lockForUpdate` call locks its ids in id order. A second round, triggered when a folder appeared meanwhile, locks a superset again in id order, but by then the first round's rows are already held. So two transactions working on overlapping subtrees (for example deleting a parent and a child at the same time while a subfolder is being created) are not guaranteed a global lock order. PostgreSQL detects the deadlock and aborts one transaction with a `CannotAcquireLockException`/`PessimisticLockingFailureException`, which surfaces as 500.
    - Why it matters: the outcome is a rare spurious 500 on delete, not corruption, because the aborted transaction rolls back cleanly.
    - Suggested fix: accept and document it, or map the lock failure to 409 so the client can retry. No structural change is needed.

## Review conclusion

Material issues found; fix before completing the task. All four acceptance criteria are met, CR-1 to CR-5 of the previous review are resolved or deliberately accepted (CR-4 as documentation), and the 86 files tests pass with ktlint clean. The remaining P2 (CR-1: purge racing a restore can permanently delete a restored file and its blob) should be fixed, or explicitly accepted by the parent with a recorded assumption, before `done`. CR-2 to CR-4 are low-priority improvements.
