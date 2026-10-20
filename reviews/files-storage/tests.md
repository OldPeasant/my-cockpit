# Test Report

Date: 2026-10-20

Task: files-storage - Files: model and blob storage

Scope: task commits `0aaf15668648e6f31bc5febd6c3bc386fd149f01..f6abbb87098187ea1828e47ad8db4f37dd981c4a` (02179b8, f6abbb8) on branch `main`, plus the two new, uncommitted test files listed below. No other uncommitted changes.

Requirements:
- `tasks/files-storage.md` acceptance criteria and recorded assumptions (subtree trash, case-insensitive folder names, special folder adoption/protection, UUIDv4 storage keys)
- SPEC.md §8.2 (files), §4.2 (Trash), §4.3 (resolvers), as referenced by the production code
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md` conventions
- Design notes supplied by the parent worker

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/files/FilesEdgeCaseTests.kt` (new, Spring Boot + Postgres via Testcontainers, same context configuration as `FilesServiceTests`)
- `server/src/test/kotlin/ch/sonensei/cockpit/files/FileIconTests.kt` (new, unit)

Existing tests (`FileNamesTests`, `BlobStoreTests`, `FilesServiceTests`) were not modified.

## Tests added or updated

Count: 32 (22 test methods in `FilesEdgeCaseTests`, 10 parameterized cases in `FileIconTests`). Files tests: 45 before, 77 after.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 migrations: folder tree, unique name per parent; file name/mime/size/sha256/storage_key | existing `folder names are unique per parent ...`; new `the database rejects blank names, bad hashes, negative sizes, duplicate keys and duplicate specials`, `folder names are trimmed and checked against siblings after trimming` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` and `./gradlew test jacocoTestReport` | Pass |
| AC1 blobs under `blobs/ab/cd/{uuid}`, written atomically (temp + move) | existing `BlobStoreTests`; new `stored blobs never leave the blob folder and get distinct keys for equal content`, `a file above the maximum size stores neither a row nor a blob` | same | Pass (atomicity checked indirectly: no leftover temp file, final path only; a crash mid-write cannot be simulated in a unit test) |
| AC2 special folders created on demand | existing special folder tests; new `concurrent first uses of different special folders share one Attachments folder`, `a user folder in the Trash is not adopted as special folder`, `an existing user folder at a special child place is adopted with its content` | same | Pass |
| AC2 special folders protected from deletion/rename | existing `special folders cannot be deleted or renamed, their content can`; new `a folder cannot be created next to a special folder with its name`, adoption test asserts 409 on delete | same | Pass |
| AC3 FILE/FOLDER resolvers | existing resolver test; new `FileIconTests` (all icon branches, case and parameters) | same | Pass |
| AC3 search indexing of names | existing search test; new `a file deleted on its own is restored from the Trash, searchable again, with its blob` | same | Pass |
| AC3 trash contributor, purge deletes the blob | existing purge tests; new file restore test, `restoring or purging twice, or items not in the Trash, is a 404`, `a folder deleted only with its parent cannot be purged on its own`, `a subfolder deleted before its parent keeps its own deletion and reappears when the parent is restored`, `restoring a folder announces its restored contents`, `a restored folder gets the next free number`, `an empty folder can be purged`, `a blob that cannot be deleted does not fail the purge`, `the trash lists folders with their parent path and deletion time` | same | Pass |
| AC4 files tests pass incl. path traversal in names rejected | existing traversal tests; new `an invalid name or a missing folder writes no blob` (traversal names and missing folder leave no blob on disk and no row) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.*'` | Pass (77 tests, 0 failures) |
| Error paths: missing/trashed items | `missing or trashed items cannot be read, renamed or deleted`, `deleting a file twice keeps the first deletion time` | same | Pass |
| New real FILE/FOLDER contributors and resolvers do not break other suites | full server suite | `cd server && ./gradlew ktlintCheck test jacocoTestReport` | Pass (1078 tests, 0 failures, 0 errors, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*'`

Result:

Pass (BUILD SUCCESSFUL; one initial failure was a test defect - the query "receipt" also matched a folder named "Receipts" - fixed by renaming the fixture folder).

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck test jacocoTestReport --console=plain`

Result:

Pass (BUILD SUCCESSFUL in 2m 12s; JUnit XML: 1078 tests, 0 failures, 0 errors, 0 skipped; 77 in `ch.sonensei.cockpit.files`).

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed", server, web and android parts, run 2026-10-20 ~16:10 CEST on the final test files).

## Bugs found

None. No production defect was found by the tests.

Observations (not defects, no test fails, for the parent/files-api to judge):
- Two concurrent `createFolder` calls with the same name in the same parent pass the service check and the second fails on the `folder_name` unique index with `DataIntegrityViolationException` instead of `ConflictException`. The database keeps the invariant; mapping this to 409 belongs to the API layer (files-api) if wanted.
- Adopting an existing user folder as special folder (e.g. root `attachments` in lower case) keeps its spelling, so the path may read `/attachments/Tasks`; it also publishes no change event or reindex for the adopted folder. Consistent with the recorded assumption, not specified otherwise.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage for the Kotlin files, because every in-scope Kotlin file was added by the task (`git diff --name-status` shows `A` for all eight); no separate per-line intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `files-storage`; base `0aaf15668648e6f31bc5febd6c3bc386fd149f01` (supplied by the parent, merge-base with origin/main); HEAD `f6abbb87098187ea1828e47ad8db4f37dd981c4a`; task commits 02179b8, f6abbb8 (`git log 0aaf156..HEAD`), files from `git diff --name-status 0aaf156..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD f6abbb8 plus untracked tests, sha256: `FileIconTests.kt` 5ab44e59f1528f78f8274724b92dc147ecfc3fe154161653194130094baf8673, `FilesEdgeCaseTests.kt` ade794ea410d5c9a01230ab000954318c402ebf2a64c38cd61f29434a2def40a (committed tests unchanged: `BlobStoreTests.kt` aa3dcb3b..., `FileNamesTests.kt` daf2f998..., `FilesServiceTests.kt` 6fa3f77f...).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/{FileItem,FileNames,FilesService,Folder}.kt`, `.../files/infra/{BlobStore,FilesItemResolvers,FilesRepositories,FilesTrashContributors}.kt`. Excluded: `db/migration/files/V202610201345__files_model.sql` (SQL, not instrumentable by JaCoCo; exercised by Flyway at context start and by the constraint test), `application.properties` (configuration only). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1, plugin default tool version, not pinned in `build.gradle.kts`); `./gradlew ktlintCheck test jacocoTestReport --console=plain` in `server/`; full server test suite.
- Run start/completion time and exit status: started 2026-10-20T16:07:25+02:00, `test.exec` written 16:09:37, report 16:09:39; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 5a6778c28b7baada1b2db363981f3d63b44c48e12d0a8ef48ce9e7567fa17678; `server/build/jacoco/test.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: report and exec timestamps fall within this run; test file hashes were taken before the run and verified unchanged after it (`sha256sum -c`); production code was not touched.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `files/domain/FilesService.kt` | 1096/1098 (99.8%) | 73/78 (93.6%) | 33/33 (100%) | 147/147 (100%) |
| `files/domain/FileNames.kt` | 140/140 (100%) | 22/22 (100%) | 3/3 (100%) | 17/17 (100%) |
| `files/domain/Folder.kt` | 125/131 (95.4%) | 3/4 (75.0%) | 14/15 (93.3%) | 21/21 (100%) |
| `files/domain/FileItem.kt` | 57/80 (71.3%) | 1/2 (50.0%) | 10/13 (76.9%) | 18/19 (94.7%) |
| `files/infra/BlobStore.kt` | 315/318 (99.1%) | 8/8 (100%) | 16/17 (94.1%) | 49/49 (100%) |
| `files/infra/FilesItemResolvers.kt` | 156/156 (100%) | 16/16 (100%) | 7/7 (100%) | 34/34 (100%) |
| `files/infra/FilesTrashContributors.kt` | 100/100 (100%) | N/A | 10/10 (100%) | 18/18 (100%) |
| `files/infra/FilesRepositories.kt` | N/A (interfaces only, no bytecode counters) | N/A | N/A | N/A |
| **Aggregate (summed counters)** | 1989/2023 (98.3%) | 123/130 (94.6%) | 93/98 (94.9%) | 304/305 (99.7%) |

Aggregate excludes the SQL migration and `application.properties` (not instrumentable) and `FilesRepositories.kt` (no counters). Before the new tests (same command restricted to files tests, 16:00): aggregate 1947/2023 instructions, 113/130 branches, 299/305 lines; `restoreFile` (lines 228-231) was never executed.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesService.kt:62` (pathOf: ancestor row missing -> `break`) | Defensive; a parent cannot vanish while a child exists (FK) | Left uncovered, not reachable without corrupting data |
| `FilesService.kt:99, 228, 254, 260` (one branch each: other sibling is the folder itself with null id check; `find` lambda mismatch; `contained.isNotEmpty` / `none` short-circuit variants) | Kotlin-generated branch halves of conditions whose meaningful outcomes are tested | Left uncovered, non-material |
| `FileItem.kt:17,21,23,30,31`, `Folder.kt:22` | JPA no-arg constructor / synthetic accessors generated by the Kotlin JPA plugin | Left uncovered, generated code |
| `FileItem.kt:34`, `Folder.kt:33` (`trashAt` when already deleted) | Callers filter out trashed items first | Left uncovered, defensive |
| `BlobStore.kt:25` (`BlobTooLargeException.maxSize` getter) | Read by the future API layer (files-api) | Left uncovered, no consumer in this task |

## Unable to test

- True atomicity of the blob write under a crash (process killed between write and move) cannot be simulated in a unit test; covered indirectly (temp file below `blobs/tmp`, `ATOMIC_MOVE`, no leftovers after success or size failure). No follow-up needed.
- Move of files/folders and HTTP-level behavior (Problem Details status codes) are out of scope (files-api).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
