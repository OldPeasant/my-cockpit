# Test Report

Date: 2026-10-26

Task: pdf-fulltext - Full-text search in PDFs and text files

Scope: base `7294e9c62762ab22230acf6142206e60106b94ae`..HEAD `20cedc1e64b56b395c1efe361fae600f2299014c` on `main`. Task commits after the parent's rebase: `96782c7cd` (impl), `7c79a15f6` (tests + review), `20cedc1e6` (review fixes). The parent named them as `ce35cbb45`, `808b28fd1` and `550f26737` before the rebase. `git diff 550f26737 20cedc1e6` only touches `tasks/`, so the code under test is the same. Uncommitted: my new test file (below), and `reviews/pdf-fulltext/review.md`, which the code-reviewer changed while I worked (not production code, not part of the measurement).

Requirements:
- `tasks/pdf-fulltext.md`: acceptance criteria and the recorded assumptions (file_text cache, limits, supported types)
- SPEC.md §4.4 (full-text search), as cited by the task
- `docs/ARCHITECTURE.md` "Coverage"
- `reviews/pdf-fulltext/review.md` and `fixes.md` (CR-1 to CR-5: the behaviour the fixes changed)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextDriveOperationsTests.kt` (new, uncommitted, sha256 `3902b3be10766ad9f5bc355618e5b26efb29c926b74d9c47124d6199beed947a`)

## Tests added or updated

Count: 7 (new). No existing test was changed.

The 47 existing fulltext tests already covered the extractor limits, the job, the indexer races, the missing blob retry, StackOverflowError/OOM handling and the rebuild after a wipe. Some behaviour was still untested: the FilesService paths the task changed (CR-3: `deleteFolder`/`restoreFolder` restructured, move via `updateFile`), the `on delete cascade` of the migration, and the read-error classification branches of `FileTextIndexer.failed` other than `NoSuchFileException`. The new tests cover these:

- `a file moved to another folder keeps its extracted text searchable`
- `deleting a folder hides the text of its files, restoring it brings the text back without a new extraction` (nested subtree)
- `a folder deleted before the job ran gets its files extracted only after it is restored`
- `purging a file removes its extracted text` (FK cascade)
- `a PDF whose blob is missing gets no row and stays pending` (the real PDFBox path, not a mock)
- `any extractor exception for a blob that is no longer readable is treated as a read error, not as a broken file` (`!Files.isReadable` branch)
- `a FileNotFoundException of the extractor is treated as a read error even when the blob looks readable`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: text extracted with PDFBox | `FileTextExtractorTests` (PDF read, mime case, broken/encrypted PDF), `FileTextIndexerTests.the text of a PDF is extracted in the background job and found by search` | `./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` | Pass |
| AC1: in a background job | `FileTextJobTests` (13: batching, 50-batch and 5-min limits, own `file-text` thread, busy skip, shutdown, schedule 30s/1min, configurable, disabled switch) | same | Pass |
| AC1: size-limited | `FileTextExtractorTests` (file size boundary 11/12 bytes, PDF not opened above it, max-chars for text/PDF/multi-byte UTF-8, max-pages, properties validation), `FileTextIndexerEdgeCaseTests.only the first max-chars characters of a long text file are searchable`, `FileTextIndexerTests.files above the size limit...` | same | Pass |
| AC1: indexed; search finds it | `FileTextIndexerTests` (4), `FileTextIndexerEdgeCaseTests` (15: NUL removal, races with change/trash/purge/rename/blob swap, retry of unreadable blobs, rebuild after wipe), `FileTextDriveOperationsTests` (7: move, folder delete/restore, purge cascade, read-error classification) | same | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'` passes (Postgres via Testcontainers) | all 54 fulltext tests (Indexer/EdgeCase/DriveOperations run on `PostgresTestBase`) | same | Pass: 54 tests, 0 failures, 0 skipped |
| Assumption: rename/move/restore keep the body (`FilesService.indexed` reads `file_text`) | `FileTextIndexerTests.the text of a text file is found, and kept when the file is renamed`, `...a file in the Trash is not indexed...`, `FileTextDriveOperationsTests` move and folder restore tests | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.files.fulltext.*'`

Result:

Pass. BUILD SUCCESSFUL, 54 tests (Extractor 15, IndexerEdgeCase 15, Job 13, DriveOperations 7, Indexer 4), 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 1m 9s, 22 test classes, 303 tests (296 before + 7 new), 0 failures, 0 errors, 0 skipped. ktlintCheck (main and test sources) passed.

I did not run the full server suite (`./gradlew check`, about 9 min). Other helpers work in the same clone and build directory at the same time, and a concurrent run would have overwritten the coverage data. The fixer reports `./gradlew check` passing on the same production code (fixes.md). The parent should run it, or `.taskcheck`, on the final revision.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also covers web and android, and the parent runs it before `done`.

## Bugs found

None. No test failed, and no production behaviour contradicts the acceptance criteria or the recorded assumptions.

Observation, not a defect in the current code: `FileTextRepository.textOf` returns the `EXTRACTED` text without checking that its `sha256` matches the file's current digest. `FileTextIndexer.store` also does not clear the search body when re-extraction of changed contents gives `SKIPPED`/`FAILED`. Today `FileItem.sha256` and `storageKey` are immutable `val`s and no code path replaces a file's contents, so this cannot happen. If a later task adds content replacement (new version upload, WebDAV PUT), the old text would stay searchable.

## Coverage

Status: verified (fresh run, exit 0, all tests passed)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately. JaCoCo `<line>` elements of the XML report were intersected with the added lines (`git diff -U0 7294e9c62..HEAD`) of the six files: 187/189 executable changed lines covered (98.9%), and 1118/1143 instructions on those lines (97.8%). The 2 uncovered lines are `FileTextExtractor.kt:78,80` (see gaps). Branch coverage of the changed lines was not derived separately. Script: `.tmp/changed_lines.py` (Git-ignored).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `pdf-fulltext`. Base `7294e9c62762ab22230acf6142206e60106b94ae` comes from the parent. HEAD is `20cedc1e64b56b395c1efe361fae600f2299014c`. The task commits are `96782c7cd`, `7c79a15f6` and `20cedc1e6`, the rebased equivalents of the parent's list. The file scope is `git diff --name-only 7294e9c62..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `20cedc1e6` plus the untracked `FileTextDriveOperationsTests.kt` (sha256 `3902b3be...947a`). No production files are modified in the working tree.
- Exact included files; renamed/deleted/excluded files and reasons: `files/domain/FilesService.kt` (modified), `files/fulltext/FileTextExtractor.kt`, `FileTextIndexer.kt`, `FileTextJob.kt`, `FileTextProperties.kt`, `FileTextRepository.kt` (new). Excluded: `V202610261300__files_fulltext.sql` (DDL, not instrumentable; exercised by every Postgres test and the cascade test), `build.gradle.kts` (dependency line), `src/test/resources/config/application.properties` (test config), docs and reviews. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (plugin default tool version; the XML report carries no version), JDK 21.0.12.1, Gradle 9.8.1 wrapper. Command `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.files.*' jacocoTestReport`, working directory `server/`, selection `ch.sonensei.cockpit.files.*`.
- Run start/completion time and exit status: started about 2026-10-26T14:09:50+01:00, `test.exec` written 14:10:59, report written 14:11:02. BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `30205c1eb6d51c4b5c2be394ef570f885ce317d3564b1067f469537d6f7b861c`. Exec data in `server/build/jacoco/test.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: the test task ran again because of the changed filter and the new test class (`compileTestKotlin` executed). The exec and report timestamps fall within this run. HEAD and the new test file's hash were the same before and after the run. The only working-tree change during the run was the reviewer's `review.md`, which is not a test or source input.
- Final run after latest test additions and production fixes: yes. It ran after all 7 new tests and on the post-fix code (`20cedc1e6` is identical in code to `550f26737`).

Values below use covered/total (percentage). "Statements" are JaCoCo instructions. Zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextExtractor.kt` | 221/233 (94.8%) | 14/14 (100%) | 9/11 (81.8%) | 27/29 (93.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextIndexer.kt` | 284/293 (96.9%) | 21/22 (95.5%) | 11/11 (100%) | 49/49 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextJob.kt` | 158/162 (97.5%) | 10/12 (83.3%) | 11/12 (91.7%) | 30/30 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextProperties.kt` | 98/98 (100%) | 16/16 (100%) | 6/6 (100%) | 13/13 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/fulltext/FileTextRepository.kt` | 258/258 (100%) | 2/2 (100%) | 21/21 (100%) | 55/55 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt` (whole file, mostly older code) | 1625/1635 (99.4%) | 111/114 (97.4%) | 53/54 (98.1%) | 225/226 (99.6%) |
| **Aggregate (summed counters, 6 files)** | 2644/2679 (98.7%) | 174/180 (96.7%) | 111/115 (96.5%) | 399/402 (99.3%) |

All six scoped files are measured, and none is missing from the report. The migration SQL is excluded as non-instrumentable.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FileTextExtractor.kt:78,80` (`BoundedWriter.flush`/`close`) | none: no-op overrides that PDFTextStripper never calls | Justified gap |
| `FileTextExtractor.kt:41,55` (5 instructions each) | exceptional close path that Kotlin `use` generates; the throwing paths themselves are tested (broken/encrypted PDF, missing file) | Justified gap (compiler-generated) |
| `FileTextIndexer.kt:59` (1 branch, 9 instr.) | `checkNotNull` on `TransactionTemplate.execute` returning null, unreachable for a non-null lambda result | Justified gap (defensive) |
| `FileTextJob.kt:78` (2 branches) | `close()` with a plain `Executor` that is not an `ExecutorService` (only the tests construct one); a no-op | Justified gap (trivial) |
| `FileTextJob.kt:86` (2 instr.) | companion initializer bytecode; the line itself is covered | Justified gap (compiler-generated) |
| `FilesService.kt:70,78,252,337` | older code (`open`, `pathOf`, the move-to-same-folder branch, `restoreFile` not-in-Trash) that the task did not change; all 13 changed executable lines are covered | Outside task changes; covered by other suites or not material here |
| `FileTextIndexer.failed` read-error branches | reading the existing tests (no coverage was measured before my changes) showed only `NoSuchFileException` was exercised, not the `FileNotFoundException` and `!Files.isReadable` branches | Two read-error tests added; in this run `FileTextIndexer` has no missed branch except line 59 |

## Unable to test

- Real concurrency (a FilesService delete/restore blocking on the job's `FOR UPDATE` lock, CR-3 deadlock avoidance) is not tested with two real transactions. The existing race tests simulate it deterministically by running the change inside the extractor call, which does not prove the lock order under real contention. A Testcontainers test with two threads would be slow and timing-dependent. No follow-up is required for the acceptance criteria, but the parent may record it.
- A real crafted PDF that causes a `StackOverflowError` in PDFBox is not used. The handling is tested with a stub extractor. No follow-up needed.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient (98.7% of instructions and 96.7% of branches across the six files, 187/189 changed lines), and no production defects were found.
