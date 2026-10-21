# Test Report

Date: 2026-10-21

Task: backup-contrib-files — Backup contributor: files

Scope: the task commits rebased onto main. The parent supplied base f4c6df3c and commits 97c51a16 / f39f92a5 / 5c2126f5. After the rebase, `git range-diff f4c6df3c..5c2126f5 6c4f9434..HEAD` shows the patches are identical (`=`): 1c29b9ff (impl), a15e5cb2 (review), b856e075 (tests), 9985ecdf (fixes). Measured HEAD: 9985ecdfbe1cf1ae696a9a341e5383aa9cba2b14 on `main`. Rebased base: 6c4f94340b7a77a8152cc09736300ba3bd01ba59.

Requirements:
- tasks/backup-contrib-files.md: acceptance criteria and recorded assumptions
- SPEC.md §10.2 (referenced by the task)
- docs/ARCHITECTURE.md "Coverage"
- parent instructions: review the existing tests, add only what is missing, and don't test fetch-size behaviour

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributorTests.kt`

## Tests added or updated

Count: 2 (added). The suite now has 24 tests.

- `import fails when folders form a cycle without a root`: covers the "cycle" half of the folder-ordering check. Before, only the missing-parent half was tested.
- `a file row that cannot be inserted fails the import and leaves no blob behind`: the file's folder is removed from `folders.json`, so the row insert breaks the foreign key after the blob was written. The test checks that the `catch` deletes the new blob. Before, only `InvalidBackupException` exercised that cleanup.

I reviewed the existing 22 tests and the `FilesBackupFixture` against the criteria and found them thorough:
- tree layout and original names
- ` (2)` / ` (3)` numbering before the extension, and skipping a taken numbered name
- case-insensitive clashes
- folder vs file clashes, active vs trashed clashes, and root-level clashes
- uniqueness of paths, and that each record's path points to its blob
- folders.json / files.jsonl fields, with no storage key
- an empty drive, and unicode or empty files
- import of rows, ids, times, versions and Trash state with new keys
- search re-indexing
- SHA-256 mismatch, size mismatch and missing blob, each leaving no blob behind
- a missing parent
- a 60 MB file streamed with allocation below size/4 on both export and import

The constant-memory check for large blobs already existed (`a 60 MB file is streamed through export and import with memory use far below its size`), so I added nothing there.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: data/files.jsonl and folders.json | `folders json holds every folder ...`, `files jsonl holds every file ... but not its storage key`, `an empty drive exports ...` | `cd server && ./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck` | Pass |
| AC1: files/ holds blobs in the original tree with original names | `blobs are stored in the original folder tree ...`, `nested folders of the same name in different parents do not clash`, `empty and unicode-named files are exported and restored` | same | Pass |
| AC1: clashes get ' (2)', with the mapping in files.jsonl | `the same name twice in a folder ...`, `names that differ only in case clash ...`, `a numbered name that already exists is skipped`, `a file named like a sibling folder ...`, `a trashed folder next to an active one ...`, `an active file keeps its name ...`, `root-level files clash ...`, `every blob path is unique ...`, `import finds each blob by the path in its record ...` | same | Pass |
| AC1: streamed, constant memory for large files | `a 60 MB file is streamed through export and import with memory use far below its size` (thread-allocated bytes < 15 MB for a 60 MB blob, on both export and import) | same | Pass |
| AC2: round-trip test with this module's BackupFixture (nested folders, name clashes, 60 MB file) | `BackupRoundTripTest` (seeds `FilesBackupFixture`) | same | Pass (1 test, 0 failures, 34.5 s) |
| Restore integrity (assumption: new key, size and SHA-256 checked, Trash not indexed) | `import restores folder and file rows ...`, `import indexes active folders and files ...`, `import rejects a blob whose SHA-256 ...`, `import rejects a blob whose size ...`, `import fails on a missing blob`, `import fails when a folder's parent is not in the backup`, `import fails when folders form a cycle without a root` (new), `a file row that cannot be inserted ...` (new) | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL: FilesBackupContributorTests 24/24, BackupRoundTripTest 1/1, ktlintCheck clean. The first attempt failed with `NoSuchFileException .../build/test-results/test/binary/in-progress-results-generic.bin` because another Gradle build was running in the same clone at the same time. That is an infrastructure collision, not a test failure. The retry passed.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Fail: 2671 tests, 1 failed. The failure is unrelated to this task and is a flaky test: `ClockConfigurationTests > the application clock is the system clock in UTC()` failed with `2026-10-21T02:20:22.238596Z should be >= 2026-10-21T02:20:22.238596022Z`. The clock is truncated to microseconds, while the comparison uses nanoseconds. Rerunning it alone (`./gradlew test --tests '*ClockConfigurationTests'`) gave BUILD SUCCESSFUL. The task did not change that test or any code under `core`. All files-backup and backup tests passed in this run.

## .taskcheck

Result:

Not run. `.taskcheck` is `sh scripts/check.sh`, which covers the server, web and android parts. Only the server changed, and its full `./gradlew check` was run above (it took 7.5 min with another build running in parallel). The parent runs `.taskcheck` through `tasks.py done`.

## Bugs found

None. Both new tests passed against the current production code.

## Coverage

Status: verified.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate figure. The file is entirely new in this task (269 added lines), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-files. Parent-supplied base f4c6df3c, which maps to rebased base 6c4f9434 (patch-identical range-diff). HEAD 9985ecdfbe1cf1ae696a9a341e5383aa9cba2b14. Production scope from `git diff --name-only 6c4f9434..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9985ecdf plus uncommitted test edits. sha256 before and after the run:
  - `FilesBackupContributor.kt` 4fb5f38084f4855d0e4774c2a4d94ca0b8b16a60820041f0802af83515f7e2db
  - `FilesBackupContributorTests.kt` f2adfbaf13a881cc39e1ac30bd4b0ff689f11c08ad7e708c45b0ecf6738a8031
  - `FilesBackupFixture.kt` cec9bf307d128786c70cd33fed0439416445176d4a49faeac320381d108eaa84
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt`, the only task-changed production file. Excluded: the test files, and `reviews/backup-contrib-files/{review,fixes}.md` (documents). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (Gradle 9.8.1). Command `./gradlew test --tests '*FilesBackup*' --tests '*BackupRoundTripTest' jacocoTestReport ktlintCheck` in `server/`. Selection: FilesBackupContributorTests and BackupRoundTripTest.
- Run start/completion time and exit status: about 2026-10-21T04:13 to 04:14:34 +02:00, exit 0. The test results XML is dated 04:14:30 and the report 04:14:33.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 eac283fa6aaf0079512f0ba97cd94604e43eb43f99ee71cf2d0f7833198f6e75. Exec file `server/build/jacoco/test.exec`, dated 04:14:23.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The `:test` task ran (it was not up to date), the report was regenerated after it, and the input hashes matched before and after.
- Final run after latest test additions and production fixes: yes. It covers the final production code at HEAD 9985ecdf (patch-identical to 5c2126f5) and both new tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/files/backup/FilesBackupContributor.kt` | 1074/1091 (98.4%) | 47/48 (97.9%) | 55/55 (100%) | 156/156 (100%) |

Aggregate: these are the same numbers, because the scope is one file. No files are missing or unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesBackupContributor.kt:51, 69, 193` (5 instructions each, no branches) | The exceptional-close paths that Kotlin's `use {}` generates (close-on-throw with a suppressed exception) | Not material: compiler-generated. The error paths around them (blob mismatch, missing blob, FK failure) are tested. |
| `FilesBackupContributor.kt:55` (1 of 2 branches) | `.orEmpty()` when `data/folders.json` is missing from the backup | Not material: the backup format always writes `folders.json` (an empty drive writes `[]`, tested). A backup with files but without that entry would fail on the foreign key, which `a file row that cannot be inserted ...` covers. No test added. |

## Unable to test

- The fetch-size/cursor behaviour of the PgJDBC file query is not tested, as the parent instructed. Memory for a large number of rows is not tested either. The constant-memory criterion is checked for one large blob, which is what the criterion names.
- Restoring the zip extracted on a case-insensitive or Windows file system is not run. The tests check this indirectly through case-insensitive path uniqueness. No follow-up is needed for this task.

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. I added 2 tests (folder cycle on import; blob cleanup when a row insert fails). The focused tests and ktlint pass. The full server `check` had 1 unrelated flaky failure (`ClockConfigurationTests`), which passed when rerun.
