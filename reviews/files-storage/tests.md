# Test Report

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: base `04049cf47bd466ff94ab23b55873a6d15c840a9a`, task commits `2bc14da..c54fce3` (2bc14da, 6364e3b, c0551e0, 04e1267, 7409ea2, dec5a80, f77dff3, 1310ab8, c54fce3) on branch `main`, HEAD `c54fce3a0583c7234ccbdd71ea81c0f0b35a7e7d`, supplied by the parent worker. This is the final pass after fix c54fce3 (`purgeFile`/`purgeFolder` lock rows before checking that the item is in the Trash, new `FileRepository.lockTrashed`; purge vs restore race). No uncommitted source or test changes (the only working-tree change is `reviews/files-storage/review.md`, written by the parallel code-reviewer).

Requirements:
- `tasks/files-storage.md` acceptance criteria and recorded assumptions (subtree trash, case-insensitive folder names, special folder adoption/protection, UUIDv4 storage keys)
- SPEC.md §8.2 (files), §4.2 (Trash), §4.3 (resolvers), §4.4 (search)
- `reviews/files-storage/fixes.md` (fix for the purge vs restore race)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` conventions

## Test files changed

None in this pass. No existing test was modified, weakened or disabled. No production code was changed.

## Tests added or updated

Count: 0

The fix commit c54fce3 already added 4 tests to `FilesConcurrencyTests` (`a purge racing a restore of the same file that commits first does nothing`, `a restore racing a purge of the same file that commits first fails and the file is gone`, and the same two for a folder). These cover both orderings of the race for files and folders. I checked the remaining paths of the changed code against existing tests:
- `purgeFile` where `lockTrashed` finds nothing (active file, unknown id, already purged): `items deleted only with their folder cannot be restored or purged on their own` (active file), `restoring or purging twice, or items not in the Trash, is a 404` (FilesEdgeCaseTests).
- `purgeFile` where the file is trashed but not a Trash root (deleted with its folder), so `lockTrashed` finds it and the second check returns false: `items deleted only with their folder cannot be restored or purged on their own`.
- `purgeFolder` with an active, unknown, already purged or descendant folder (`lockSubtree` runs, then return false): FilesEdgeCaseTests tests listed above plus `a folder deleted only with its parent cannot be purged on its own`.
- Rollback after a purge keeps rows and blobs: `a purge that rolls back keeps the blob`, `a folder purge that rolls back keeps the folder, its files and their blobs`.

JaCoCo confirms that every branch of `purgeFile` and `purgeFolder` is covered (see Coverage). A new test would only repeat these, so I added none.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 migrations: folder tree, unique name per parent; file name/mime/size/sha256/storage_key | `folder names are unique per parent regardless of case, also enforced by the database`, `the database rejects blank names, bad hashes, negative sizes, duplicate keys and duplicate specials`, `folder names are trimmed and checked against siblings after trimming` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun jacocoTestReport` | Pass |
| AC1 blobs under `blobs/ab/cd/{uuid}`, written atomically (temp + move) | `BlobStoreTests` (5), `stored blobs never leave the blob folder and get distinct keys for equal content`, `a file above the maximum size stores neither a row nor a blob`, `the blob is removed again when the transaction rolls back` | same | Pass (atomicity checked indirectly, see "Unable to test") |
| AC2 special folders created on demand | `special folders are created on demand below Attachments, once`, `concurrent first uses create each special folder once`, `concurrent first uses of different special folders share one Attachments folder`, `a user folder in the Trash is not adopted as special folder`, `an existing user folder at a special child place is adopted with its content`, `an adopted user folder gets the special spelling, is reindexed and announced` | same | Pass |
| AC2 special folders protected from deletion/rename | `special folders cannot be deleted or renamed, their content can`, `a folder cannot be created next to a special folder with its name` | same | Pass |
| AC3 FILE/FOLDER resolvers | `resolvers show name, path, icon and deep link, trashed items do not resolve`, `FILE and FOLDER resolvers and trash contributors are registered`, `FileIconTests` (10) | same | Pass |
| AC3 search indexing of names | `names are searchable, renames are reindexed and trashed items leave the index`, `a file deleted on its own is restored from the Trash, searchable again, with its blob` | same | Pass |
| AC3 trash contributor, purge deletes the blob | `purging a file deletes its row, its blob and its links`, `purging a folder deletes the whole subtree with blobs, links and search documents`, `a purge that rolls back keeps the blob`, `a folder purge that rolls back keeps the folder, its files and their blobs`, `the purge job purges files older than the retention`, `purging a folder never deletes an active file or folder found below it` | same | Pass |
| AC3 purge vs restore race (fix c54fce3) | `a purge racing a restore of the same file that commits first does nothing`, `a restore racing a purge of the same file that commits first fails and the file is gone`, `a purge racing a restore of the same folder that commits first does nothing`, `a restore racing a purge of the same folder that commits first fails and the folder is gone` | same | Pass |
| AC4 files tests pass incl. path traversal in names rejected (Postgres via Testcontainers) | `FileNamesTests` (16), `names that could escape their folder are rejected`, `an invalid name or a missing folder writes no blob`, `keys that are not storage keys are rejected, so no path escapes the blob folder`; the persistence tests use the Testcontainers Postgres of the server test setup | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun jacocoTestReport` | Pass (90 tests, 0 failures, 0 errors, 0 skipped) |
| No regressions in other modules | full server suite plus web and android via `.taskcheck` | `sh scripts/check.sh` | Pass (server JUnit XML: 1324 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun jacocoTestReport`

Result:

Pass (BUILD SUCCESSFUL in 31s, about 17:08:28 to 17:08:59 +02:00; JUnit XML: 90 tests, 0 failures, 0 errors, 0 skipped. BlobStoreTests 5, FileIconTests 10, FileNamesTests 16, FilesConcurrencyTests 12, FilesEdgeCaseTests 23, FilesServiceTests 24.)

## Full test execution

Command:

`sh scripts/check.sh` (server ktlint plus the full server test suite, web check, android ktlint/unit tests/lint)

Result:

Pass on the second run. Server JUnit XML: 1324 tests, 0 failures, 0 errors, 0 skipped (the files tests ran again at 15:18:20Z = 17:18:20 +02:00).

The first run (started about 17:10 +02:00) failed after 4m 39s with `java.nio.file.NoSuchFileException: server/build/test-results/test/binary/in-progress-results-generic.bin` in the Gradle `:test` task. This was not a test failure. At 17:12:36 a second Gradle run in this clone (the parallel code-reviewer's files-only test run; the results folder then held only the 6 files test XMLs) rewrote `server/build/test-results` while my run was still going. I ran it again once no Gradle process from this clone was running, and it passed.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed", finished about 17:19 +02:00, on HEAD c54fce3 with no source or test changes).

## Bugs found

None. No test failed against the production code.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage, because the task added every in-scope Kotlin file (`git diff --name-only 04049cf..HEAD -- server/src/main` lists only files that are new in the task). I did not compute a separate intersection.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `files-storage`; base `04049cf47bd466ff94ab23b55873a6d15c840a9a` and commits `2bc14da..c54fce3` from the parent worker; HEAD `c54fce3a0583c7234ccbdd71ea81c0f0b35a7e7d`; files from `git diff --name-only 04049cf..HEAD -- server/src/main`. These match the task's `touches` (`files` packages and `db/migration/files`) plus `application.properties`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c54fce3 with a clean source/test tree. sha256 of `git ls-files -s` over `server/src/main/kotlin/ch/sonensei/cockpit/files`, `server/src/test/kotlin/ch/sonensei/cockpit/files` and `server/src/main/resources/db/migration/files`: `b02f86cec8aa8ac677723403c85a05e4d695dc0099ac1c0b30a552ff2072fbf0`. Taken at 17:08:22 +02:00 and found unchanged at 17:09:10 and 17:19:02.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/{FileItem,FileNames,FilesService,Folder}.kt` and `.../files/infra/{BlobStore,FilesItemResolvers,FilesRepositories,FilesTrashContributors}.kt`. Excluded: `db/migration/files/V202610201345__files_model.sql` (SQL, which JaCoCo cannot instrument; Flyway applies it when the context starts and the constraint tests exercise it) and `application.properties` (configuration only). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1, plugin default tool version); `./gradlew test --tests 'ch.sonensei.cockpit.files.*' --rerun jacocoTestReport` in `server/`; the files test package only (90 tests).
- Run start/completion time and exit status: 2026-10-20 about 17:08:28 to 17:08:59 +02:00 (test suites timestamped 15:08:37Z to 15:08:58Z); BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `4c858924b837170486342692ad18898c80b3647a8c83bd3762ee2b8271af68da`, written at the end of that run. I parsed it at 17:09:10 +02:00, before any other run in this clone.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the `test` task, and Gradle reported "7 actionable tasks: 7 executed". The report was written inside the run, and the snapshot hash was unchanged before and after.
- Final run after latest test additions and production fixes: yes. The last production fix (c54fce3) is in the measured HEAD, and this pass added no tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `files/domain/FilesService.kt` | 1342/1344 (99.9%) | 88/90 (97.8%) | 40/40 (100%) | 189/189 (100%) |
| `files/domain/FileNames.kt` | 140/140 (100%) | 22/22 (100%) | 3/3 (100%) | 17/17 (100%) |
| `files/domain/Folder.kt` | 129/131 (98.5%) | 3/4 (75.0%) | 15/15 (100%) | 21/21 (100%) |
| `files/domain/FileItem.kt` | 61/80 (76.2%) | 1/2 (50.0%) | 11/13 (84.6%) | 18/19 (94.7%) |
| `files/infra/BlobStore.kt` | 315/318 (99.1%) | 8/8 (100%) | 16/17 (94.1%) | 49/49 (100%) |
| `files/infra/FilesItemResolvers.kt` | 156/156 (100%) | 16/16 (100%) | 7/7 (100%) | 34/34 (100%) |
| `files/infra/FilesTrashContributors.kt` | 100/100 (100%) | N/A | 10/10 (100%) | 18/18 (100%) |
| `files/infra/FilesRepositories.kt` | N/A (interfaces only, no bytecode counters) | N/A | N/A | N/A |
| **Aggregate (summed counters, 7 measured files)** | 2243/2269 (98.9%) | 138/142 (97.2%) | 102/105 (97.1%) | 346/347 (99.7%) |

The aggregate excludes the SQL migration and `application.properties` (not instrumentable) and `FilesRepositories.kt` (no counters). The concurrency tests exercise its locking queries, including the new `lockTrashed`. Compared with the previous report, `FilesService.kt` changed shape with fix c54fce3: `purgeFile` no longer has the uncovered `find` lambda branch, and the new lock-then-check paths in `purgeFile` and `purgeFolder` are fully covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesService.kt:69` (`pathOf`: ancestor row missing -> `break`) | Defensive; a parent cannot vanish while a child exists (FK `parent_id references folder`) | Left uncovered: unreachable without corrupting data |
| `FilesService.kt:251` (one branch of the `find { it.id == id }` lambda in `restoreFile`) | Kotlin-generated half-branch of a lookup; both outcomes (found / not found) are tested (`restoring or purging twice, or items not in the Trash, is a 404`) | Left uncovered, non-material |
| `FileItem.kt:17,23,30,31`, `Folder.kt:22` | JPA no-arg constructor / unused generated accessors (Kotlin JPA plugin) | Left uncovered: generated code |
| `FileItem.kt:34`, `Folder.kt:33` (`trashAt` when already deleted) | Callers filter out trashed items first | Left uncovered: defensive code |
| `BlobStore.kt:25` (`BlobTooLargeException.maxSize` getter) | Read by the future API layer (files-api) | Left uncovered: nothing in this task reads it |

## Unable to test

- True atomicity of the blob write under a crash (process killed between write and move) cannot be simulated in a unit test. It is covered indirectly: the temp file lives below `blobs/tmp`, the move uses `ATOMIC_MOVE`, and no leftovers remain after success, a size failure or a rollback. No follow-up needed.
- The first-use race between `specialFolder(ATTACHMENTS)` and a user `createFolder("Attachments")` that commits first. Forcing this interleaving needs a hook inside the production method. Low impact, and already recorded as not fixed in `fixes.md`.
- Moving files/folders and HTTP status mapping (Problem Details 404/409) are out of scope (files-api).
- Mutation checks of the new purge/restore race tests were not run, because they need temporary production changes, which this helper may not make. By reasoning only: without `lockTrashed` (or `lockSubtree` before the Trash check), the "purge racing a restore that commits first" tests would delete the just-restored item, and the test asserts that the item survives.

## Summary

No additional tests were necessary for the reviewed scope. Tests and task-scoped coverage are sufficient; no production defects found. The focused files suite (90 tests) and `.taskcheck` pass on HEAD c54fce3.
