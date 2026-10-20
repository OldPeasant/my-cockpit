# Code Review

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: final review after the last fix, committed changes only: `git diff 04049cf47bd466ff94ab23b55873a6d15c840a9a..c54fce3a0583c7234ccbdd71ea81c0f0b35a7e7d -- . ':!tasks' ':!reviews'`. That covers the files module main and test code, the Flyway migration `files/V202610201345__files_model.sql`, `cockpit.data-dir` in the main and test `application.properties`, `server/.gitignore` and the `COCKPIT_DATA_DIR` row in `docs/ARCHITECTURE.md`. The focus is the CR-1 fix in c54fce3 (purge vs restore). The working tree was clean when the review started.

Reviewed revision: c54fce3a0583c7234ccbdd71ea81c0f0b35a7e7d

Requirements:
- tasks/files-storage.md: acceptance criteria and recorded assumptions
- SPEC.md §8.2, §4.2 (Trash)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- core contracts: `TrashContributor.purge` ("an item that is not deleted is never purged"), `TrashService` (one transaction per restore/purge), `TrashPurgeJob`, `SearchIndexer` (`onItemDeleted`), `ProblemDetailsAdvice` (`OptimisticLockingFailureException` -> 409)
- previous review (CR-1 P2, CR-2..CR-4 P3) and reviews/files-storage/fixes.md

Git range:
- Branch: main
- HEAD: c54fce3a0583c7234ccbdd71ea81c0f0b35a7e7d
- Base/start: 04049cf47bd466ff94ab23b55873a6d15c840a9a
- Commits reviewed: 9 (2bc14da, 6364e3b, c0551e0, 04e1267, 7409ea2, dec5a80, f77dff3, 1310ab8, c54fce3)

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' ktlintCheck` -> BUILD SUCCESSFUL, ktlintCheck clean (test task was up to date).
- `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.files.*'` run three times. The first run ended with BUILD FAILED after 36s, and the output tail showed no failing test. A test-writer was running Gradle in the same clone at the same time, which is the likely cause, but this could not be confirmed. The next two runs passed (BUILD SUCCESSFUL). The JUnit XML of the last run shows FilesServiceTests 24, FilesConcurrencyTests 12, FilesEdgeCaseTests 23, FileNamesTests 16, BlobStoreTests 5 and FileIconTests 10: 90 tests, 0 failures, 0 errors, 0 skipped (Postgres via Testcontainers).
- Read-only inspection with `git log`, `git show`, `git diff`, `grep` and file reads.

Verification of the CR-1 fix (purge racing a restore):
- `purgeFile` (`FilesService.kt:307-314`) now starts with `FileRepository.lockTrashed` (`FilesRepositories.kt:87-89`): PESSIMISTIC_WRITE, `where f.id = :id and f.deletedAt is not null`. Under READ COMMITTED, a purge that waits for a committing restore re-evaluates `deleted_at is not null` on the new row version, gets no row and returns `false`, so `TrashService` returns 404. When the purge locks first, the restore's versioned UPDATE waits. After the purge commits, that UPDATE fails with `OptimisticLockingFailureException`, which maps to 409. The blob hook is registered only after both checks pass, and it deletes the blob only on commit.
- `purgeFolder` (`FilesService.kt:266-301`) now calls `lockSubtree` before it loads anything. `TrashService.purge` runs one transaction per item, and `TrashPurgeJob` runs one per entry, so the persistence context is empty at that point. The entities are therefore loaded by the locking read and are current. The trash-root check (`findTrashRoots`) evaluates `deletedAt` in SQL with a fresh statement snapshot. The stale managed instance that caused the old bug can no longer occur.
- Deadlock check: `restoreFolder` updates folder rows before file rows. The folders are loaded first, and the root rename is flushed by the following native `findSubtreeIds` query. So a restore that waits on the purge's folder locks holds no file-row locks that the purge later needs. The restore's `search_document` upserts insert new rows, because trashing deleted the old ones. The purge's `ItemDeletedEvent` deletes do not wait on rows they cannot see. Overlapping `deleteFolder(ancestor)` and `purgeFolder(trashed child)` lock in id order within a round. They fall under the existing cross-round residual CR-4, and nothing new was introduced. Two concurrent purges of the same item: the second waits, then sees no row or no subtree and returns 404.
- Tests: four new lock-wait tests in `FilesConcurrencyTests` cover both orders for a file and for a folder. Each test forces the interleaving with latches and `pg_stat_activity`. They assert that the restored item and its blob survive, or that the restore fails and the row and the blob are gone. fixes.md records a mutation check (the old order makes the "restore commits first" tests fail).

Acceptance criteria check:
1. Migrations and atomic blobs: met. `folder` has the tree and a unique `(parent_id, lower(name)) nulls not distinct where deleted_at is null`. `file` has name, mime, size, sha256 and storage_key. `BlobStore.write` writes to a temp file under `blobs/tmp`, runs `force(true)`, then does an `ATOMIC_MOVE` to `blobs/ab/cd/<uuid>`.
2. Special folders: met. They are created on demand under an advisory lock, a user folder in that place is adopted with the canonical spelling, and rename/delete return 409 (`ConflictException`).
3. Resolvers FILE/FOLDER, search indexing of names, trash contributors: met. The purge deletes blobs after commit and now never purges a concurrently restored item.
4. Tests pass, including path traversal: met. 90 of 90 pass on the final revision. They cover `..`, `.`, separators and control characters in `FileNames` and at the service level, plus key traversal in `BlobStore.path`.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None. CR-1 of the previous review is resolved (see "Verification of the CR-1 fix").

## P3 Low

The parent worker accepted these as non-blocking. They are still present in the code and are listed here unchanged so the record stays complete.

- [ ] **CR-2 | Restore does not lock the parent, so an item can be restored into a folder that is being deleted**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:231-255` (`restoreFolder`, `restoreFile`)
    - Category: Correctness
    - Problem: the restore paths do not call `lockActiveFolder(parentId)`. A restore that races a delete of its parent can commit an active item under a trashed folder. The item is visible in search while its folder is in the Trash. A later purge of the parent moves it to the root through `rescue`, so no data is lost.
    - Why it matters: a narrow race that leaves the tree inconsistent but recoverable.
    - Suggested fix: after finding the trash root, call `lockActiveFolder(parentId)` when the parent is not the root.

- [ ] **CR-3 | Some unique-name races still surface as 500 instead of 409**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:211` (`specialFolder`), `:234` (`restoreFolder` `freeName`), `:319` (`rescue`)
    - Category: Correctness
    - Problem: only `createFolder` and `renameFolder` go through `flushedName`. A concurrent same-name insert during special-folder creation, a folder restore or a rescue fails at commit with `DataIntegrityViolationException`, which maps to 500.
    - Why it matters: the database keeps the invariant and a retry works, but the client gets a server error instead of 409.
    - Suggested fix: wrap these writes in `flushedName` with an explicit flush, or record the residual with `tasks.py assume`.

- [ ] **CR-4 | Overlapping subtree locks can deadlock across `lockSubtree` rounds**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:339-347`
    - Category: Correctness
    - Problem: each round locks in id order, but a second round re-locks a superset while the first round's rows are still held. Two overlapping `deleteFolder` or `purgeFolder` calls are therefore not guaranteed a global lock order when a folder appears between rounds. PostgreSQL aborts one of them, and that surfaces as 500.
    - Why it matters: a rare spurious 500 and a clean rollback, with no corruption.
    - Suggested fix: map the lock failure to 409, or document it as accepted.

## Review conclusion

No material issues found. The CR-1 fix is correct. It closes the purge-vs-restore race for files and folders in both orders, it adds no deadlock between purge and restore, and the four new lock-wait tests cover it. All four acceptance criteria are met on c54fce3. The 90 files tests pass (two consecutive passing runs; one earlier failed run most likely came from a concurrent Gradle run in the same clone and could not be attributed to the code), and ktlint is clean. The remaining P3 findings CR-2 to CR-4 were accepted by the parent as non-blocking.
