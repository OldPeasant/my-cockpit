# Code Review

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: committed changes of the task, `git diff 0aaf156..8a0cd69 -- . ':!tasks' ':!reviews'` (files module main + test code, Flyway migration `files/V202610201345__files_model.sql`, `cockpit.data-dir` in main and test `application.properties`, `server/.gitignore`). No uncommitted changes were reviewed.

Requirements:
- tasks/files-storage.md acceptance criteria and recorded assumptions
- SPEC.md §8.2, §4.2 (Trash), §4.3 (links/resolvers), §4.4 (search), §11 (COCKPIT_DATA_DIR)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md
- core contracts: `TrashContributor`/`TrashService`/`TrashPurgeJob`, `ItemResolver`, `SearchIndexer`, `ChangeEventPublisher`, `SoftDeletableEntity`, `ProblemDetailsAdvice`

Git range:
- Branch: main
- HEAD: 8a0cd6947b01ce652adab40ea55bf23b5b475808
- Base/start: 0aaf15668648e6f31bc5febd6c3bc386fd149f01
- Commits reviewed: 3 (02179b8, f6abbb8, 8a0cd69)

Commands run:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` -> BUILD SUCCESSFUL; 5 test classes, 77 tests, 0 failures, 0 errors, 0 skipped (Postgres 16 via Testcontainers)
- read-only inspection: `git log`, `git diff`, `grep` of core contracts, deploy compose files and Dockerfile (COCKPIT_DATA_DIR=/data is set there, so the relative default only applies to local runs)

Acceptance criteria check:
1. Migrations / atomic blobs: met. `folder` (tree via `parent_id`, unique `(parent_id, lower(name)) nulls not distinct where deleted_at is null`, PG16 in tests and deploy) and `file` (name, mime, size, sha256, storage_key) tables; `BlobStore.write` streams to `blobs/tmp/*.part`, `force(true)`, then `ATOMIC_MOVE` to `blobs/ab/cd/<uuid>` on the same file system; temp removed on failure.
2. Special folders: met (created on demand under an advisory lock, 409 on rename/delete; see CR-3/CR-4 for adoption details).
3. Resolvers, search, trash contributor: met. Purge deletes the blob after commit; rollback keeps it; rollback of a store deletes the new blob.
4. Tests: met (77 pass, includes `..`, `.`, separators, control characters at `FileNames` and service level, and key traversal in `BlobStore.path`).

Summary: 0 P0, 0 P1, 1 P2, 4 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Upload racing a folder delete leaves an active file inside a trashed folder, which the later purge deletes permanently**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:146` (check), `:147` (blob streaming), `:242` (purge takes every file of the subtree)
    - Category: Correctness
    - Problem: `storeFile` checks that the folder is active (`folderId?.let(::folder)`, a plain READ COMMITTED read without a lock), then streams the whole blob, then inserts the row. Streaming a large upload takes seconds to minutes, so a `deleteFolder` of that folder (or an ancestor) committed in that window does not see the new file (`files.findByFolderIdIn(...)` runs before the insert) and the file row is inserted active (`deleted_at` null) under a trashed folder. That file stays searchable and resolvable while its folder is in the Trash, is not restored/trashed consistently, and `purgeFolder` (`files.findByFolderIdIn(subtreeIds)` with no `deletedAt` filter), including the automatic purge after 30 days, deletes the active file and its blob without it ever having been in the Trash. The same window exists, much shorter, for `createFolder` into a folder being deleted.
    - Why it matters: silent permanent loss of a file the user never deleted; the Trash contract (SPEC.md §4.2: only deleted items are purged, "an item that is not deleted is never purged" in `TrashContributor.purge`) is broken.
    - Suggested fix: after the blob is written, lock and re-check the target folder (e.g. a `PESSIMISTIC_READ`/`FOR SHARE` lookup of the folder that requires `deleted_at is null`) before inserting, and have `deleteFolder` lock the subtree folders `FOR UPDATE`; alternatively/additionally let `purgeFolder` refuse (or trash first) when the subtree contains active rows. Add a test that deletes the folder while a store is between the check and the insert (e.g. a blocking `InputStream`).

## P3 Low

- [ ] **CR-2 | Concurrent same-name folder creation surfaces as 500 instead of 409**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:84`
    - Category: Correctness
    - Problem: the sibling check and `folders.save` are not atomic; the unique index `folder_name` catches the race, but the resulting `DataIntegrityViolationException` (raised at flush/commit) is not mapped by `ProblemDetailsAdvice` (only `ApiException`, `OptimisticLockingFailureException`, `ConstraintViolationException`, then the generic `Exception` handler), so the client gets a 500. The same applies to a user `createFolder("Attachments")` racing `specialFolder`, since `createFolder` does not take the advisory lock. Noted by the test-writer as well.
    - Why it matters: the documented contract of `createFolder`/`renameFolder` is a `ConflictException` (409); a double submit from the future files-api would show a server error.
    - Suggested fix: flush inside the method and translate a violation of `folder_name` into `ConflictException` (or map that constraint in the files-api layer); a test with two parallel creates.

- [ ] **CR-3 | An adopted user folder keeps its own spelling and emits no change event**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:186`
    - Category: Correctness
    - Problem: `sibling` matches case-insensitively, so a user folder `attachments` or `TASKS` is adopted as-is; afterwards it can no longer be renamed (409), so the special path stays e.g. `/attachments/Tasks` instead of SPEC.md §8.2's `/Attachments/{Tasks,...}`. The adoption also publishes no `UPDATED` change event, so open clients do not learn that the folder became protected (icon `folder_special`, rename/delete disabled).
    - Why it matters: small deviation from the specified folder names and stale client state; the user cannot fix the name.
    - Suggested fix: on adoption set `name = kind.folderName`, reindex, and publish `ChangeOp.UPDATED`.

- [ ] **CR-4 | Upload streaming holds a DB transaction and connection for the whole transfer**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt:146-147`
    - Category: Quality
    - Problem: `storeFile` runs inside the class-level `@Transactional` and performs a query (`folder(...)`) before `blobs.write`, so a pooled connection (main pool default 10, tests 5) is held while the client upload streams. A few slow parallel uploads can exhaust the pool for the rest of the application. Also widens the CR-1 window.
    - Why it matters: resilience of the single-instance server once files-api streams uploads into this method.
    - Suggested fix: document that callers must pass already-buffered content, or split into a non-transactional blob write followed by a short transaction that inserts the row (deleting the blob if it fails), keeping the after-rollback cleanup.

- [ ] **CR-5 | ARCHITECTURE.md still says COCKPIT_DATA_DIR is "not yet used"**
    - File: `docs/ARCHITECTURE.md:109`
    - Category: Integration
    - Problem: this task introduces `cockpit.data-dir=${COCKPIT_DATA_DIR:data}` (blob storage), but the env table row still reads "see SPEC.md §11 (not yet used)"; no docs file is part of the diff, although the change summary mentions a docs row.
    - Why it matters: the run/deploy documentation no longer matches the configuration (including the relative `data` default for `bootRun`).
    - Suggested fix: update the row to say the server stores file blobs in `${COCKPIT_DATA_DIR}/blobs` and defaults to `server/data` locally.

## Review conclusion

Material issues found; fix before completing the task. All acceptance criteria are implemented and the files tests pass (77/77), but CR-1 (an upload racing a folder delete can lead to permanent loss of an active file on purge) should be fixed or explicitly accepted by the parent before `done`; CR-2 to CR-5 are low-priority improvements.
