# Test Report

Date: 2026-10-21

Task: backup-restore - Backup restore: validation, full replace, CLI

Scope: base `c64f99c12708f0a0f10ccd1cc90a2c5f2834d9ed`, task commits 9e473389, 01fc3588, 81ad8442, 9f9f9a95, d86d2a35, a49052d7 (merged into HEAD `2d37483dab77296c393e294413a4dada69855c0d`, branch `main`; `origin/wip/backup-restore` is the same commit). Server production files from those commits only. The `web/src/app/features/gtd/task-detail/**` files in `git diff c64f99c1 origin/wip/backup-restore` come from the `web-task-detail` task merged from main, not from this task, so they are excluded. Uncommitted inputs: the two test files listed below (this report's changes).

Requirements:
- `tasks/backup-restore.md` acceptance criteria and its 4 ASSUMPTION notes
- SPEC.md §10.1 (restore), docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/RestoreCliTests.kt`

## Tests added or updated

Count: 3 added (plus a `cliWithDataDir` helper in RestoreCliTests; the existing `cli` helper delegates to it unchanged)

- `RestoreWebTests.the pre-restore backup is a valid backup that brings the replaced data back`: the pre-restore zip uploads as valid, confirming it brings back the replaced rows and blobs, and each restore keeps its own pre-restore file.
- `RestoreCliTests.a restore over existing data replaces it and saves it to pre-restore first`: covers the assumption that the CLI replaces a non-empty install and saves the old data first.
- `RestoreCliTests.restores into a data directory that does not exist yet`: a fresh install whose `cockpit.data-dir` does not exist yet.

The existing tests already covered the criteria well (upload report, confirm replace, corrupt zip, wrong format version, sha256 mismatch, failing import rollback of DB and data dir, 409 during backup, 401/403, content-free problems, CLI exit codes). No existing test was changed or weakened.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 upload returns a validation report (format version, counts, sha256 mismatches, warnings) without changing data | RestoreWebTests: `upload returns the validation report and changes nothing`, `a sha256 mismatch is rejected...`, `a backup of another app version is valid with a warning`, `a backup without owner account warns...`; UserRestoreWarningsTests (5) | `server/gradlew -p server test --rerun --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' --tests '*UserRestoreWarningsTests' jacocoTestReport` | Pass |
| AC1 confirm makes a pre-restore backup to `${COCKPIT_DATA_DIR}/pre-restore/` and replaces all data transactionally | RestoreWebTests: `confirm saves the current data to pre-restore...`, `a failing import rolls back the database and the data directory`, `confirm also empties tables that are not in the backup, such as sessions`, `a pre-restore backup never overwrites an earlier one of the same minute`, `a failing pre-restore backup changes nothing...`, new `the pre-restore backup is a valid backup that brings the replaced data back`; BackupJobServiceTests `exclusive ...` (4) | same | Pass |
| AC2 `java -jar cockpit-server.jar --restore=...` restores on a fresh install and exits; setup then skipped | RestoreCliTests (11, incl. new `a restore over existing data...`, `restores into a data directory that does not exist yet`) call `RestoreCli.run` in-process | same | Pass |
| AC2, run as named (the real jar) | Manual run: `server/gradlew -p server bootJar`, own `postgres:16-alpine` container (`tw-backup-restore-pg`, removed afterwards), fictitious hand-built format-1 zip with an active owner (`.tmp/e2e-scripts/make_backup.py`), `java -jar server/build/libs/cockpit-server-0.0.1-SNAPSHOT.jar --restore=.tmp/e2e/backup.zip` (also `missing.zip`, `corrupt.zip`) with `SPRING_DATASOURCE_*`, the test master key, and `COCKPIT_DATA_DIR=.tmp/e2e/data`. The scripts are in Git-ignored `.tmp/` of agent-4's clone. | `sh .tmp/e2e-scripts/run_cli.sh "$PWD" 33511`, then `sh .tmp/e2e-scripts/run_server.sh "$PWD" 33511 18947` (33511 = the container's mapped Postgres port) | Pass: missing file -> exit 2; corrupt zip -> exit 1 "Problem: Not a zip file"; valid backup -> exit 0, "pre-restore/cockpit-backup-2026-10-20T2331Z.zip", "setup is skipped"; normal start afterwards `GET /api/v1/setup/status` -> `{"setupRequired":false}`. The jar file name is the Gradle bootJar name, not the `cockpit-server.jar` name that the deployment image may use. |
| AC3 corrupt zip / wrong format version / sha mismatch -> rejected with a clear report, data untouched | RestoreWebTests: `a corrupt zip is rejected...`, `a wrong format version is rejected...`, `a sha256 mismatch is rejected...`, `an empty upload is rejected as not a zip...`; ZipBackupReaderValidationTests (32); RestoreCliTests `an invalid backup exits with 1 and changes nothing` | same | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Testcontainers Postgres) | whole backup package | same (the backup package is part of it) | Pass: 132 tests in 13 classes, 0 failures, 0 skipped |
| AC5 `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | same | Pass (1 test). Regenerating with `UPDATE_OPENAPI=1` was not re-run by the test-writer. |

## Focused test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.backup.RestoreCliTests' --tests 'ch.sonensei.cockpit.backup.RestoreWebTests'`

Result:

Pass (RestoreWebTests 30, RestoreCliTests 11, 0 failures; the 3 new tests show up in the JUnit XML)

Task-scoped suite (also the coverage run):

`server/gradlew -p server test --rerun --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' --tests '*UserRestoreWarningsTests' jacocoTestReport` -> BUILD SUCCESSFUL, 132 tests, 0 failures, 0 errors, 0 skipped.

`server/gradlew -p server ktlintCheck` -> BUILD SUCCESSFUL.

## Full test execution

Command:

`sh scripts/check.sh` (tfp gate tests, server ktlint + full server tests, web eslint + tests + build, android ktlint + unit tests + lint)

Result:

Pass ("== all checks passed", exit 0). Server: 2297 tests in 188 classes, 0 failures. Web: 1074 tests in 57 files passed. Run 01:37:14 to 01:45:17 (+02:00), about 8 min: under the 15 min `TASK_CHECK_TIMEOUT` but over the 2 min warning. The duration is the whole repository's check, not something this task added.

## .taskcheck

Result:

Pass (`.taskcheck` = `sh scripts/check.sh`, see above)

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 276/287 executable added/modified lines (96.2%). Method: `git diff -U0 c64f99c1 HEAD` added-line numbers per file, intersected with the JaCoCo XML line entries (covered when `ci > 0`). Removed and non-executable lines are not in the denominator. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `backup-restore`; base `c64f99c12708f0a0f10ccd1cc90a2c5f2834d9ed`; HEAD `2d37483dab77296c393e294413a4dada69855c0d`; task commits from the parent (9e473389, 01fc3588, 81ad8442, 9f9f9a95, d86d2a35, a49052d7), checked with `git show --stat` on each. `git diff origin/wip/backup-restore HEAD -- server/src/main` is empty.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2d37483d plus the uncommitted `RestoreCliTests.kt` sha256 `a137498dcc35e0868ceaa3a015f379f752641b9eedfad497c1594990db8b7ae8` and `RestoreWebTests.kt` sha256 `08795ae7880a695ccac7b4b22c0a3f9aedb38eb89cca1f86393a57068e09cd81`. Hashes were the same before and after the run, and there were no other changes or untracked files.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/` `CockpitServerApplication.kt`, `backup/api/RestoreCli.kt`, `backup/api/RestoreController.kt`, `backup/domain/BackupContributor.kt`, `backup/domain/BackupJobService.kt`, `backup/domain/BackupService.kt`, `backup/domain/RestoreService.kt`, `backup/infra/ZipBackupReader.kt`, `core/backupcontrib/UserBackupContributor.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated, no executable task logic); `web/src/app/features/gtd/**` (another task, see Scope). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1 default tool version), `server/gradlew -p server test --rerun --tests 'ch.sonensei.cockpit.backup.*' --tests '*OpenApiContractTest' --tests '*UserRestoreWarningsTests' jacocoTestReport`, run from the repository root.
- Run start/completion time and exit status: 2026-10-21T01:34:36+02:00 to 01:36:12+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (mtime 01:36:05, sha256 `cde3695949c0a7d2396f6471e72111f7d5b5eb59a982d2ab1051913faef860ab`), `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 01:36:12, sha256 `91fd7e9d5cfd2d7b5a192c0c7d509bf851b928885d6fd871921b57b1c8453d12`). Both are Git-ignored. The later `scripts/check.sh` run rewrote `test.exec`; the numbers here come from the XML above.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task to run, both artifacts are newer than the run start, and the source and test hashes matched after the run.
- Final run after latest test additions and production fixes: yes. It includes all 3 new tests, and no production code changed during this session.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `backup/api/RestoreController.kt` | 164/164 (100.0%) | N/A (0/0) | 21/21 (100.0%) | 43/43 (100.0%) |
| `backup/api/RestoreCli.kt` | 241/259 (93.1%) | 16/16 (100.0%) | 3/4 (75.0%) | 37/39 (94.9%) |
| `backup/domain/RestoreService.kt` | 800/847 (94.5%) | 44/52 (84.6%) | 33/33 (100.0%) | 124/130 (95.4%) |
| `backup/domain/BackupService.kt` | 566/566 (100.0%) | 3/4 (75.0%) | 22/22 (100.0%) | 84/84 (100.0%) |
| `backup/domain/BackupJobService.kt` | 619/641 (96.6%) | 18/20 (90.0%) | 34/34 (100.0%) | 79/79 (100.0%) |
| `backup/domain/BackupContributor.kt` | 200/200 (100.0%) | 14/14 (100.0%) | 11/11 (100.0%) | 27/27 (100.0%) |
| `backup/infra/ZipBackupReader.kt` | 798/809 (98.6%) | 71/72 (98.6%) | 21/21 (100.0%) | 128/130 (98.5%) |
| `core/backupcontrib/UserBackupContributor.kt` | 412/417 (98.8%) | 16/18 (88.9%) | 25/25 (100.0%) | 85/87 (97.7%) |
| `CockpitServerApplication.kt` | 3/28 (10.7%) | 0/2 (0.0%) | 1/2 (50.0%) | 1/4 (25.0%) |
| Aggregate (summed counters, all 9 files, none missing) | 3803/3931 (96.7%) | 182/198 (91.9%) | 171/173 (98.8%) | 608/623 (97.6%) |

All 9 scoped files are in the report. None is missing or unmeasured. The pre-existing code in modified files (BackupService, BackupJobService, ZipBackupReader, UserBackupContributor) counts toward these whole-file numbers.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CockpitServerApplication.kt:13-15` (`main`, `exitProcess`) | AC2 `java -jar --restore` entry point | Cannot run in the test JVM (`exitProcess`). Exercised by the manual `java -jar` run (see AC2 row): exit codes 2/1/0 and setup skipped. |
| `RestoreCli.kt:30,32` (default `out = System.out` overload) | only called from `main` | Covered by the same `java -jar` run; the tests pass their own PrintStream. |
| `RestoreCli.kt:41` (`use {}` exception path, partly) | closing the context when it throws | Kotlin `use` bytecode. Not material. |
| `RestoreService.kt:202-205` (moving the data dir into the stash fails) | moving files back if the stash move fails | Needs a filesystem fault partway through a move, which is hard to cause deterministically without mocking `Files`. Low risk: the code calls the same `unstash` that the rollback test covers. Gap left as is. |
| `RestoreService.kt:216,218` (`unstash` fails) | logs an error; the data stays in the stash and the pre-restore zip | Defensive. Needs a filesystem fault during rollback. Gap left as is. |
| `RestoreService.kt:226` (data dir does not exist) | fresh install without a data dir | New test `restores into a data directory that does not exist yet` passes. The line stays uncovered because the application creates the data directory on startup, so the branch looks unreachable in a running context. |
| `RestoreService.kt:171` (`VirtualMachineError` rethrow), `177` (stash delete fails) | fatal errors, logging cleanup failures | Defensive. Not material. |
| `RestoreService.kt:83,192,241` (partial: inlined `runCatching` catch paths) | cleanup when the cleanup itself fails | Kotlin inlining. The surrounding paths are tested (broken upload stream, failing pre-restore backup, discarded uploads). |
| `RestoreService.kt:119` (1/4: no problems but no manifest), `213` / `223` (partial), `237` (2/4: no tables) | impossible combinations or an empty schema | Not reachable with a migrated schema or with what `inspect` returns. |
| `ZipBackupReader.kt:150-151` (`IOException` while reading the manifest) | corrupt manifest bytes | Corrupt compressed bytes reach the `JacksonException` path instead (test `a manifest whose compressed bytes are corrupt is not valid, for open and inspect`). Defensive. |
| `ZipBackupReader.kt:59` (1/2, pre-existing lambda branch), `121` | inspect when the zip cannot be opened | `121` is the `return zip.use` closing path, partly. Not material. |
| `BackupJobService.kt:107,135,155-164`, `BackupService.kt:36`, `UserBackupContributor.kt:81,102-104` | pre-existing code from backup-core | Outside this task's changed lines (changed-line coverage of these files: 2/2, 10/10, 8/8). |

## Unable to test

- Running the exact image or deployment artifact `cockpit-server.jar`: the manual run used the Gradle `bootJar` output `server/build/libs/cockpit-server-0.0.1-SNAPSHOT.jar` (same Spring Boot executable jar, Java 21 Temurin). The container image build was not run. Follow-up only if the parent wants AC2 evidence from the image path.
- Filesystem faults during the stash or unstash of the data directory (see the coverage gaps): not tested deterministically without mocking core JDK file APIs. No follow-up planned.
- `UPDATE_OPENAPI=1` regeneration of `api/openapi.yaml` was not re-run here. `OpenApiContractTest` passes without the flag, so the committed contract matches the server.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
