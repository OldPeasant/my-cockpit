# Test Report

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: task commits `3f0c01c6983106a3c91f1493a7c8842b0f566951..e61ada743f13895e18a72170fb47acb42e8ff684` (b3278d7, 80f99f2, 36126d7, 54e3156, 1a8faf2, e61ada7) on branch `main`, supplied by the parent worker. This is the re-run after the fixes for review findings CR-1 to CR-5 (folder locking in `FilesService`, purge rescue of active rows, 409 on a name race, special-folder adoption with canonical spelling). Plus the uncommitted test changes listed below; no other uncommitted changes.

Requirements:
- `tasks/files-storage.md` acceptance criteria and recorded assumptions (subtree trash, case-insensitive folder names, special folder adoption/protection, UUIDv4 storage keys)
- SPEC.md §8.2 (files), §4.2 (Trash), §4.3 (resolvers), §4.4 (search)
- `reviews/files-storage/review.md` (CR-1..CR-5) and `reviews/files-storage/fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` conventions

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesConcurrencyTests.kt` (2 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesEdgeCaseTests.kt` (1 test added)

No existing test was modified, weakened or disabled. No production code was changed.

## Tests added or updated

Count: 3 (files tests: 83 before this pass, 86 after).

- `a folder created in a folder that is being deleted waits for the delete and fails` (FilesConcurrencyTests): a `deleteFolder` is held open in a transaction; a `createFolder` into that folder must wait on the row lock (checked via `pg_stat_activity`) and then fail with `NotFoundException`, leaving no new folder. Covers the `lockActiveFolder` guard in `createFolder` (CR-1 "same window ... for createFolder"), which the fix pass did not test.
- `a folder delete waits for a subfolder created meanwhile and trashes it with the folder` (FilesConcurrencyTests): a `createFolder` is held open; a `deleteFolder` of the parent must wait, then trash the new subfolder with the parent's deletedAt; only the parent is listed in the Trash and purging it removes everything. This exercises the repeat path of `lockSubtree` (a folder that appears after the first tree read).
- `a folder purge that rolls back keeps the folder, its files and their blobs` (FilesEdgeCaseTests): `purgeFolder` inside a transaction that fails afterwards must keep rows and blobs (the after-commit-only blob deletion of a folder purge was untested; only the file purge rollback was).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 migrations: folder tree, unique name per parent; file name/mime/size/sha256/storage_key | `folder names are unique per parent regardless of case, also enforced by the database`, `the database rejects blank names, bad hashes, negative sizes, duplicate keys and duplicate specials`, `folder names are trimmed and checked against siblings after trimming` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*'`; `cd server && ./gradlew check jacocoTestReport` | Pass |
| AC1 blobs under `blobs/ab/cd/{uuid}`, written atomically (temp + move) | `BlobStoreTests` (5), `stored blobs never leave the blob folder and get distinct keys for equal content`, `a file above the maximum size stores neither a row nor a blob`, `the blob is removed again when the transaction rolls back` | same | Pass (atomicity checked indirectly, see "Unable to test") |
| AC2 special folders created on demand | `special folders are created on demand below Attachments, once`, `concurrent first uses create each special folder once`, `concurrent first uses of different special folders share one Attachments folder`, `a user folder in the Trash is not adopted as special folder`, `an existing user folder at a special child place is adopted with its content`, `an adopted user folder gets the special spelling, is reindexed and announced` (CR-3) | same | Pass |
| AC2 special folders protected from deletion/rename | `special folders cannot be deleted or renamed, their content can`, `a folder cannot be created next to a special folder with its name`, adoption test asserts 409 on rename | same | Pass |
| AC3 FILE/FOLDER resolvers | `resolvers show name, path, icon and deep link, trashed items do not resolve`, `FILE and FOLDER resolvers and trash contributors are registered`, `FileIconTests` (10) | same | Pass |
| AC3 search indexing of names | `names are searchable, renames are reindexed and trashed items leave the index`, `a file deleted on its own is restored from the Trash, searchable again, with its blob` | same | Pass |
| AC3 trash contributor, purge deletes the blob | `purging a file deletes its row, its blob and its links`, `purging a folder deletes the whole subtree with blobs, links and search documents`, `a purge that rolls back keeps the blob`, new `a folder purge that rolls back keeps the folder, its files and their blobs`, `the purge job purges files older than the retention`, restore/purge edge cases in `FilesEdgeCaseTests` | same | Pass |
| AC4 files tests pass incl. path traversal in names rejected (Postgres via Testcontainers) | `FileNamesTests` (16), `names that could escape their folder are rejected`, `an invalid name or a missing folder writes no blob`, `keys that are not storage keys are rejected, so no path escapes the blob folder` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*'` | Pass (86 tests, 0 failures, 0 errors, 0 skipped) |
| CR-1 fix: upload racing a folder delete | `an upload into a folder deleted while its blob is written fails and leaves neither row nor blob`, `a folder delete waits for an upload that passed the check, and trashes the new file with the folder` | same | Pass |
| CR-1 fix: folder creation racing a folder delete | new `a folder created in a folder that is being deleted waits for the delete and fails`, new `a folder delete waits for a subfolder created meanwhile and trashes it with the folder` | same | Pass |
| CR-1 fix: purge never deletes active rows (rescue to root) | `purging a folder never deletes an active file or folder found below it` | same | Pass |
| CR-2 fix: name race is a 409 (`ConflictException`) | `two parallel creates of the same folder name give one folder and one conflict`, `a rename racing a create of the same name is a conflict` | same | Pass |
| No regressions in other modules | full server suite | `cd server && ./gradlew check jacocoTestReport` | Pass (1147 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*' --console=plain`

Result:

Pass (BUILD SUCCESSFUL; 86 tests: BlobStoreTests 5, FileIconTests 10, FileNamesTests 16, FilesConcurrencyTests 8, FilesEdgeCaseTests 23, FilesServiceTests 24). One intermediate run failed only on ktlint (chained call formatting in a new test), fixed in the test.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --console=plain`

Result:

Pass (BUILD SUCCESSFUL in 3m 24s, 2026-10-20T16:42:55+02:00 to 16:46:20+02:00; JUnit XML: 1147 tests, 0 failures, 0 errors, 0 skipped).

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed" for server, web and android; 2026-10-20T16:46:28+02:00 to 16:47:12+02:00, on the final test files).

## Bugs found

None. No test failed against the production code.

Residual edge (not a defect found by a test, already noted in `fixes.md` "Not fixed"): the very first `specialFolder(ATTACHMENTS)` racing a user `createFolder("Attachments")` that commits first can still surface a `DataIntegrityViolationException` on the special-folder side; the database keeps the invariant and a retry succeeds. Not tested because forcing it needs a hook between the advisory lock and the flush.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage, because every in-scope Kotlin file was added by the task (`git diff --name-status 3f0c01c..HEAD -- server/src/main` shows `A` for all eight Kotlin files); no separate intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `files-storage`; base `3f0c01c6983106a3c91f1493a7c8842b0f566951` and commits b3278d7, 80f99f2, 36126d7, 54e3156, 1a8faf2, e61ada7 supplied by the parent; HEAD `e61ada743f13895e18a72170fb47acb42e8ff684`; files from `git diff --name-status 3f0c01c..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e61ada7 plus two modified test files, sha256 `FilesConcurrencyTests.kt` 1619a37cec4e8cc9317c7ec8a6b4e3da63979a38dbf94959dcc23625b775dca5, `FilesEdgeCaseTests.kt` 61593bf52385c0507df7ee8ca1d820b75d3b508600fbd982205677d7ef99042a; production files as committed (`FilesService.kt` e462b8661f53405d0ae0f8b403480d13d0c60f3f8dd394f2df47de3ec34d9669, `FilesRepositories.kt` c6b81b671a2cbadcc295238a9511847bce7929b93ec860c06bab0b141b500377).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/{FileItem,FileNames,FilesService,Folder}.kt`, `.../files/infra/{BlobStore,FilesItemResolvers,FilesRepositories,FilesTrashContributors}.kt`. Excluded: `db/migration/files/V202610201345__files_model.sql` (SQL, not instrumentable by JaCoCo; applied by Flyway at context start and exercised by the constraint tests), `application.properties` (configuration only). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1, plugin default tool version); `./gradlew check jacocoTestReport --console=plain` in `server/`; full server test suite.
- Run start/completion time and exit status: 2026-10-20T16:42:55+02:00 to 16:46:20+02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 16:46:20) sha256 27c71512a29d27ec4e82ae1ae2a7f165f83735d00273493643059ad3726df127; `server/build/jacoco/test.exec` (written 16:46:15).
- Evidence outputs are fresh and inputs stayed unchanged: test inputs changed before the run, so Gradle re-ran `test`; report and exec timestamps fall inside the run; source/test hashes were taken at 16:42:53 and verified unchanged after the run (`sha256sum -c`).
- Final run after latest test additions and production fixes: yes (all fixes are in e61ada7 and earlier; no production change after the run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `files/domain/FilesService.kt` | 1348/1350 (99.9%) | 89/92 (96.7%) | 40/40 (100%) | 187/187 (100%) |
| `files/domain/FileNames.kt` | 140/140 (100%) | 22/22 (100%) | 3/3 (100%) | 17/17 (100%) |
| `files/domain/Folder.kt` | 129/131 (98.5%) | 3/4 (75.0%) | 15/15 (100%) | 21/21 (100%) |
| `files/domain/FileItem.kt` | 61/80 (76.2%) | 1/2 (50.0%) | 11/13 (84.6%) | 18/19 (94.7%) |
| `files/infra/BlobStore.kt` | 315/318 (99.1%) | 8/8 (100%) | 16/17 (94.1%) | 49/49 (100%) |
| `files/infra/FilesItemResolvers.kt` | 156/156 (100%) | 16/16 (100%) | 7/7 (100%) | 34/34 (100%) |
| `files/infra/FilesTrashContributors.kt` | 100/100 (100%) | N/A | 10/10 (100%) | 18/18 (100%) |
| `files/infra/FilesRepositories.kt` | N/A (interfaces only, no bytecode counters) | N/A | N/A | N/A |
| **Aggregate (summed counters)** | 2249/2275 (98.9%) | 139/144 (96.5%) | 102/105 (97.1%) | 344/345 (99.7%) |

The aggregate excludes the SQL migration and `application.properties` (not instrumentable) and `FilesRepositories.kt` (no counters; its locking queries are exercised by the concurrency tests). A run on the same command before the purge rollback test (16:37:49-16:41:49, already including the two new concurrency tests) gave branches 138/144; `FilesService.kt:293` (folder purge after-completion, rollback branch) was the only branch closed by the last test.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesService.kt:67` (`pathOf`: ancestor row missing -> `break`) | Defensive; a parent cannot vanish while a child exists (FK `parent_id references folder`) | Left uncovered, not reachable without corrupting data |
| `FilesService.kt:249, 299` (one branch each of the `find { it.id == id }` lambda in `restoreFile`/`purgeFile`) | Kotlin-generated branch half of a lookup whose found / not-found outcomes are both tested (`restoring or purging twice, or items not in the Trash, is a 404`) | Left uncovered, non-material (needs two files in the Trash with the lookup hitting the second) |
| `FilesService.kt:293` (folder purge rolled back) | Blobs must survive a rolled-back folder purge | Covered now by `a folder purge that rolls back keeps the folder, its files and their blobs` |
| `FilesService.kt:329-336` (`lockSubtree` repeat when a folder appears after the first tree read) | CR-1: a subfolder created during a delete must be trashed with it | Covered by `a folder delete waits for a subfolder created meanwhile and trashes it with the folder` |
| `FileItem.kt:17,23,30,31`, `Folder.kt:22` | JPA no-arg constructor / unused generated accessors (Kotlin JPA plugin) | Left uncovered, generated code |
| `FileItem.kt:34`, `Folder.kt:33` (`trashAt` when already deleted) | Callers filter out trashed items first | Left uncovered, defensive |
| `BlobStore.kt:25` (`BlobTooLargeException.maxSize` getter) | Read by the future API layer (files-api) | Left uncovered, no consumer in this task |

## Unable to test

- True atomicity of the blob write under a crash (process killed between write and move) cannot be simulated in a unit test; covered indirectly (temp file below `blobs/tmp`, `ATOMIC_MOVE`, no leftovers after success, size failure or rollback). No follow-up needed.
- The first-use race between `specialFolder(ATTACHMENTS)` and a user `createFolder("Attachments")` (see "Bugs found"), because it needs a hook inside the production method to force the interleaving. Low impact; follow-up only if the parent wants it handled.
- Moving files/folders and HTTP status mapping (Problem Details 404/409) are out of scope (files-api).
- Mutation checks of the new concurrency tests were not run, because they require temporarily changing production code, which this helper may not do. By reasoning only: without the active-folder check in `createFolder`, the first new test fails (the insert would succeed after the delete); without the repeat in `lockSubtree`, the second leaves the subfolder active. Note that the foreign-key `KEY SHARE` lock of a child insert also blocks the delete's `FOR UPDATE`, so the wait in the second test does not by itself prove the explicit share lock.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
