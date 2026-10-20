# Fix Report

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: finding CR-1 (P2) of the re-review in `reviews/files-storage/review.md` (reviewed HEAD e2876a8). Base commit d373732efc3d32affba8c45cb78fe2870ebcd90d, task commits up to ad3f352. The parent worker accepted P3 findings CR-2 to CR-4 without changes and excluded them from this pass. The changes are uncommitted in the working tree.

Earlier pass (summary): the first fix pass resolved CR-1 to CR-5 of the first review. It added `FOR SHARE` locking of the target folder in `storeFile` and `createFolder`. It added `FOR UPDATE` subtree locking in `deleteFolder`. It made `purgeFolder` purge only rows in the Trash and move active rows to the root. It mapped unique-name races in `createFolder` and `renameFolder` to 409, and it added adoption reindexing and docs. `FilesConcurrencyTests` covered those changes (8 tests after the test-writer pass).

## Fixed findings

### CR-1 - A purge racing a restore of the same item permanently deletes the restored (active) file and its blob

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/FilesRepositories.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesConcurrencyTests.kt`

Problem:
`purgeFile` checked the Trash with an unlocked read. It then ran an unconditional bulk delete and deleted the blob after commit. A restore committed between the check and the delete was overwritten. `purgeFolder` loaded the root unlocked through `trashedFolders()` before `lockSubtree`. The locking read then returned the same stale managed instance, so a restored root still looked deleted and was purged.

Fix:
- `FileRepository.lockTrashed(id)` is a new query: `PESSIMISTIC_WRITE`, `where f.id = :id and f.deletedAt is not null`. It follows the style of `lockActiveForShare`. `purgeFile` now locks the row with it first and returns `false` when no row comes back. Only after that does it check that the file is still a trash root.
  - Under READ COMMITTED, a purge that waits for a restore re-evaluates `deleted_at is not null` on the new row version, so it gets no row and does nothing.
- `purgeFolder` now calls `lockSubtree(id)` (`FOR UPDATE`, id order) first, before anything is loaded into the persistence context. Only then does it check the trash-root membership with `findTrashRoots`, which evaluates `deletedAt` in SQL, and load the subtree. The entities it decides on (`isDeleted`) therefore come from the locking read and are current. The local variable that held the purged ids was renamed from `subtreeIds` to `purgedIds`, because `subtreeIds` now names the locked ids.
- Blobs: the existing `afterCompletion { committed -> ... }` hooks are registered only after the checks pass. So blobs are deleted only after commit, and only for rows that were actually purged.
- The other order: a restore that starts while a purge holds the lock waits at its versioned UPDATE. After the purge commits, the UPDATE fails with `OptimisticLockingFailureException`. `ProblemDetailsAdvice` maps that to 409, so the restore never reports success for an item that was purged.
- KDoc of the class, `purgeFile` and `lockTrashed` describes the lock-then-check order.

Validation:
- New tests in `FilesConcurrencyTests`, using the existing latch and `pg_stat_activity` lock-wait pattern:
  - `a purge racing a restore of the same file that commits first does nothing`: the restore flushes inside an open transaction, the purge waits, and then returns `false`. The file is active and its blob is intact.
  - `a restore racing a purge of the same file that commits first fails and the file is gone`: the restore waits on the purge's lock and then fails with `OptimisticLockingFailureException`. The row and the blob are gone.
  - `a purge racing a restore of the same folder that commits first does nothing`: the folder, its subfolder and the file stay in place (not scattered to the root), and the blob is intact.
  - `a restore racing a purge of the same folder that commits first fails and the folder is gone`.
- Mutation check: I temporarily restored the old order (check before lock, `trashedFiles()` instead of `lockTrashed`). Both "restore commits first" tests then fail: the folder test with a `StaleObjectStateException` from the purge, and the file test because the purge succeeds. I then restored the fix from a backup, and all tests pass.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun ktlintCheck`: Pass

## Not fixed

### CR-2 - Restore does not lock the parent

Status:
Out of scope

Reason:
P3, accepted by the parent worker. It was excluded from this fix pass.

### CR-3 - Some unique-name races still surface as 500 instead of 409

Status:
Out of scope

Reason:
P3, accepted by the parent worker. It was excluded from this fix pass.

### CR-4 - Two overlapping folder deletes can deadlock across `lockSubtree` rounds

Status:
Out of scope

Reason:
P3, accepted by the parent worker. It was excluded from this fix pass.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.FilesConcurrencyTests'`: Pass (12 tests, including 4 new)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun ktlintCheck`: Pass (BUILD SUCCESSFUL; JUnit XML: 90 tests, 0 failures. FilesConcurrencyTests 12, FilesServiceTests 24, FilesEdgeCaseTests 23, FileNamesTests 16, FileIconTests 10, BlobStoreTests 5.)

Full test suite:
- Covered by `.taskcheck` below (server, web and android unit tests).

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat`, then `ktlintCheck`: Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh` exited 0)

Remaining unresolved findings:
0 in scope (CR-2 to CR-4 were accepted by the parent and not fixed)

## Final status

All validated findings fixed and validation passed.
