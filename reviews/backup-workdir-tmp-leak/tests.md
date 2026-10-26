# Test Report

Date: 2026-10-26

Task: backup-workdir-tmp-leak - BackupJobService/RestoreService leak their lazy temp work dirs

Scope: `git diff 60cf97e18663aaa3496bdb8c4b3bfeeb1caf9557..59fa3e4d21854428860c12064bbed6ab0173d6f2` (one task commit `59fa3e4d2` "backup: delete lazy temp work dirs on bean destroy", on branch `main`). Production files: `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt`. Uncommitted at measurement time: only the new test file below (untracked).

Requirements:
- `tasks/backup-workdir-tmp-leak.md` acceptance criteria
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", code conventions)
- Parent worker instructions (focused unit tests in the existing backup test folder)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWorkDirCleanupTests.kt` (new; plain unit tests without Spring, same setup style as `BackupJobServiceTests`)

## Tests added or updated

Count: 10

The tests find a service's work dir either via the zip path (`BackupJobService.zip(id).second.parent`) or by
diffing the `cockpit-backups*` / `cockpit-restores*` folders of `java.io.tmpdir` before and after use (the server
tests run in one fork, without JUnit parallel execution, so the diff is deterministic).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: BackupJobService deletes its work dir (if created) on destroy | `destroying the backup service deletes its work dir with the zip in it`, `destroying the backup service deletes its work dir also when the only backup failed`, `backups of one service share one work dir until it is destroyed, and destroying twice is harmless` | `cd server && ./gradlew test --tests '*BackupWorkDirCleanupTests' --tests '*BackupJobServiceTests'`; again in `./gradlew check jacocoTestReport --rerun-tasks` | Pass |
| AC1: BackupJobService creates no work dir when never used (destroy does not force the lazy) | `a backup service that never made a backup creates no work dir, and destroying it creates none either` | same | Pass |
| AC1: RestoreService deletes its work dir (if created) on destroy, incl. the pending upload | `destroying the restore service deletes its work dir with the pending upload in it`, `destroying the restore service deletes its work dir also after an invalid upload`, `uploads of one restore service share one work dir, a new upload replaces the pending one` | same | Pass |
| AC1: RestoreService creates no work dir when never used | `a restore service that never got an upload creates no work dir, and destroying it creates none either` | same | Pass |
| AC1: deletion is limited to the service's own dir | `destroying the backup service leaves other cockpit-backups dirs alone`, `destroying the restore service leaves other cockpit-restores dirs alone` | same | Pass |
| AC1: backup/restore behavior unchanged | Existing `BackupJobServiceTests` (11, incl. `shutting down deletes the finished zips`), `RestoreWebTests`, `RestoreCliTests`, `BackupRoundTripTest`, `BackupWebTests` and the rest of the server suite; new tests also check one shared work dir per service and that a new upload replaces the pending one | `cd server && ./gradlew check jacocoTestReport --rerun-tasks` | Pass (4714 tests, 0 failures, 0 errors, 0 skipped) |
| AC2: `./gradlew check` passes and leaves no `cockpit-backups*`/`cockpit-restores*` dirs | `./gradlew check` run here: Pass. The tmp-dir count with `java.io.tmpdir` redirected was not re-run by the test-writer (the sandbox refused the `JAVA_TOOL_OPTIONS=...` command form); the parent reports it verified 0 dirs | `cd server && ./gradlew check jacocoTestReport --rerun-tasks` | Pass (check); count: Not run here, see parent's evidence |
| AC3: `sh scripts/check.sh` passes | - | `sh scripts/check.sh` | Pass ("== all checks passed") |

Regression intent: at the base commit `destroy()` did not touch the work dir, so the four "deletes its work dir" tests
would fail there (the dir would still exist). This was reasoned from the diff, not executed against the base revision
(the test-writer does not check out other revisions).

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*BackupWorkDirCleanupTests' --tests '*BackupJobServiceTests' -q`

Result:

Pass (BackupWorkDirCleanupTests 10/10, BackupJobServiceTests 11/11). A first `check` run then failed on one ktlint
`function-signature` violation in the new test file; fixed in the test, and the final runs below include the fix.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --rerun-tasks -q`

Result:

Pass (all tasks of `check`, including ktlint and `test`; 347 test classes, 4714 tests, 0 failures, 0 errors, 0 skipped;
`jacocoTestReport` ran after `test`, which Gradle only does when `check` succeeded).

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "== all checks passed": server, web, android).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured from the same JaCoCo XML by intersecting its per-line entries with the added lines of
the task diff (BackupJobService.kt 49, 50, 170; RestoreService.kt 70, 71, 271-274; line 271 `override fun destroy() {`
has no JaCoCo line entry). Result: 8/8 executable changed lines hit; the 2 changed `if (lazyWorkDir.isInitialized())`
lines have 4/4 branches covered. The `runCatching` failure path of those lines (7 instructions each) is not hit.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-workdir-tmp-leak`; base `60cf97e18663aaa3496bdb8c4b3bfeeb1caf9557` and task commit `59fa3e4d21854428860c12064bbed6ab0173d6f2` (= measured HEAD) supplied by the parent; the diff touches exactly the task's `touches` folder.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `59fa3e4d2` plus untracked `BackupWorkDirCleanupTests.kt` sha256 `3bdee01376c4d6957a914f454acd5ecc13be8ff3c20e2ccdbe62df5d8cfcd59a`; `BackupJobService.kt` sha256 `78f0620982bbdd602cadeebaf1199e9c22315975489bb9c4db15483a1c343d5c`, `RestoreService.kt` sha256 `0fd98db3f66c62576064f518ad3f97457adb933da5937e836182f60cde2a3f2a` (same hashes before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: both files above included; none renamed, deleted or excluded.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin); `./gradlew check jacocoTestReport --rerun-tasks -q` in `server/`; full `test` task (all non-`external` tests).
- Run start/completion time and exit status: started after 2026-10-26T08:36:45Z, report written 2026-10-26T08:43:52Z; build succeeded (report produced after `check`; 0 test failures).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `81860b17aa5a5bc5e51af7d206e8b405b38d1545343d70275519b3f8ff7866bd` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun-tasks` forced every task to run; report mtime is within the run; input hashes unchanged.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt` | 658/687 (95.8%) | 20/22 (90.9%) | 35/35 (100.0%) | 82/82 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt` | 889/953 (93.3%) | 48/56 (85.7%) | 35/36 (97.2%) | 138/146 (94.5%) |
| Aggregate (both files, summed counters; no files missing or excluded) | 1547/1640 (94.3%) | 68/78 (87.2%) | 70/71 (98.6%) | 220/228 (96.5%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BackupJobService.kt:169,170`, `RestoreService.kt:273` (runCatching failure handler, 7 instr. each) | Defensive: `deleteIfExists`/`deleteRecursively` throwing during shutdown | Justified gap: `deleteRecursively` reports failure by return value and does not throw for normal I/O errors; forcing a throw would need mocking the file system |
| `RestoreService.kt:176-251` (pre-existing restore paths: stash/unstash failure, pre-restore backup failure, etc.) | Pre-existing restore behavior, not changed by this task | Out of task scope; no test added |
| `BackupJobService.kt:109,140,160,162`, `RestoreService.kt:86,132,262,266` | Pre-existing defensive branches (copy failure cleanup, delete failures) | Out of task scope; no test added |

## Unable to test

- AC2's tmp-dir count with `java.io.tmpdir` redirected into the clone: the test-writer's sandbox refused the `JAVA_TOOL_OPTIONS=... ./gradlew check` command form, so the count was not re-run here. The parent reports it ran it (0 `cockpit-backups*`/`cockpit-restores*` dirs). No further follow-up needed if that evidence is recorded.
- A backup still running when `destroy()` runs (`shutdownNow()` interrupts it; the export thread could in theory write a zip into the dir after it was deleted). Not required by the task; behavior is a pre-existing race and was not tested.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
