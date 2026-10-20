# Test Report

Date: 2026-10-20

Task: backup-contrib-core - Backup contributors: core entities

Scope: the final pass on the final task code. Branch main, base 307fdf1a0bfeda40f78324f448462dffe1a8aa26, HEAD d3620d37ee0129ecdc6c4605053fe0997f370a8d. The task commits are a86c38ad, f63fdb92, 061a0337 and d3620d37: every commit in `git log 307fdf1a..HEAD`, and all of them carry "backup-contrib-core" in their message. The worktree had no uncommitted changes. Production files: the 5 files in `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/`. Tests: `CoreBackupContributorsTests.kt` and `CoreBackupFixtures.kt` in the matching test folder, plus the step-count assertion in `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt`. Earlier passes used different commit hashes (base a8bc9af4, commits 0bf2a50b, d11717d7, 0524317d) because the history was rebased since. This report replaces those passes for the final revision.

Requirements:
- `tasks/backup-contrib-core.md`: both acceptance criteria and the 4 recorded assumptions (no TOTP secret in `user.json` and a fresh secret on import; settings in `data/settings.json`; no sessions or devices in the backup; JDBC instead of JPA)
- SPEC.md §10.2 and §14, through the `BackupContributor`/`BackupFixture` contract
- docs/ARCHITECTURE.md "Coverage"
- earlier reports: `reviews/backup-contrib-core/review.md` and `fixes.md`
- parent instructions for this final pass

## Test files changed

- None in this pass. The existing tests already cover every acceptance criterion and every changed behavior (see below), so nothing was added.

## Tests added or updated

Count: 0 in this pass. The task's tests now in place: `CoreBackupContributorsTests` (11 tests), the 4 `BackupFixture`s in `CoreBackupFixtures.kt` (run by `BackupRoundTripTest`), and the corrected step-count assertion in `BackupWebTests`.

Gap analysis, branch by branch, for the final code:
- `UserBackupContributor.export`. No user: the empty-install test. User present: the TOTP-not-exported test, which also checks the exact property set of `user.json`.
- `UserBackupContributor.import`. Missing file: the empty-install test. Active owner: the new-secret test, which logs in with a recovery code and with the new TOTP and rejects the old TOTP. TOTP never confirmed, so no warning: the unconfirmed-owner test. Warning with N unused codes: the warning-count test. Lockout warning with 0 unused codes: the lockout test. The logs are checked to contain neither the username nor the secret.
- `SettingsBackupContributor`. No row: the empty-install test, which also checks the defaults text of the mirror. Row present, and the restored version (ETag) still works: the settings-service test. Mirror sorting and Markdown escaping: the settings mirror test.
- Links and reminders. Mirror contents, order, escaping and the null label: the mirror tests. The fired reminder stays in `data/`: the reminders mirror test. Empty mirrors: the empty-mirrors test. Ids, timestamps and version survive export and import exactly: `BackupRoundTripTest`, which compares `data/` before and after with this module's fixtures.
- `BackupRows.kt` helpers: all counters are covered.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 user contributor: password hash and recovery code hashes exported; TOTP secret in no backup file, in any encoding (assumption #1) | `the TOTP secret is in no file of the backup, neither in plain text nor encrypted` | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.backupcontrib.*' --tests '*BackupRoundTripTest' --tests '*BackupWebTests' jacocoTestReport` | Pass |
| AC1 user contributor import: new decryptable TOTP secret, the old one rejected, password hash and recovery code survive and work | `after import the owner has a new decryptable TOTP secret, the old one no longer works, and a recovery code does` | same | Pass |
| AC1 user contributor import: re-enrollment WARN (with the count of unused codes, the lockout variant, none for an unconfirmed owner), never with the username or secret | `importing an active owner warns with the number of unused recovery codes`, `importing an owner without unused recovery codes warns that the owner is locked out`, `importing an owner who never confirmed TOTP logs no re-enrollment warning`, plus the assertions in the import test above | same | Pass |
| AC1 user and settings contributors: an empty install exports neither file, and importing that backup is a no-op | `an install without owner and settings exports no user or settings file, and importing it creates neither` | same | Pass |
| AC1 settings contributor: restored settings readable through `SettingsService` with their version | `restored settings are read through the settings service with their version` | same | Pass |
| AC1 readable sections: `readable/settings.md`, `links.md`, `reminders.md` (sorting, escaping, pending reminders only, empty cases) | `the settings mirror lists ...`, `the links mirror lists ...`, `the reminders mirror lists only pending reminders ...`, `empty links and reminders give mirrors with only their table header` | same | Pass |
| AC2: round trip passes with this module's `BackupFixture`s seeding a user, settings, links and reminders | `BackupRoundTripTest` (finds `UserBackupFixture`, `SettingsBackupFixture`, `LinksBackupFixture`, `RemindersBackupFixture` on the classpath; fails if a contributor has no fixture) | same | Pass (1/1) |
| Existing backup job API stays correct with the 4 new contributors (export step count) | `BackupWebTests` > `POST starts a backup that completes and downloads as a zip` (expects `backups.exportSteps`) | same | Pass (12/12 in the class) |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.backupcontrib.*' --tests '*BackupRoundTripTest' --tests '*BackupWebTests' jacocoTestReport --console=plain -q`

Result:

Pass, exit 0. JUnit XML: CoreBackupContributorsTests 11 tests, 0 failures, 0 errors, 0 skipped. BackupRoundTripTest 1/0/0/0. BackupWebTests 12/0/0/0.

Lint: `cd server && ./gradlew ktlintCheck --console=plain -q` passed, exit 0.

## Full test execution

Command:

`cd server && ./gradlew check --console=plain -q` (ktlint plus the full server test suite), run 2026-10-20 23:09:28 to 23:15:57 +02:00

Result:

Pass, exit 0. `build/test-results/test`: 173 suites, 2048 tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Not run by the test-writer. `scripts/check.sh` also covers web and Android, which this task does not touch. Its server part (`./gradlew check`) passed (see above). The parent worker runs `.taskcheck` before `done`.

## Bugs found

None. The earlier finding TW-1 (stale step count in `BackupWebTests`) was fixed in 061a0337, and that test now passes.

## Coverage

Status: verified. Fresh task-focused run, exit 0.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage. All 5 files are new in this task: `git diff --stat 307fdf1a..HEAD` shows additions only for them. So every executable line is a changed line. No separate intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-core. Base 307fdf1a0bfeda40f78324f448462dffe1a8aa26, as given by the parent. HEAD d3620d37ee0129ecdc6c4605053fe0997f370a8d. Task commits: a86c38ad, f63fdb92, 061a0337, d3620d37, from the parent and confirmed with `git log 307fdf1a..HEAD`. The changed production paths match the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d3620d37 with a clean worktree. sha256 of the files: BackupRows.kt 5b92accd..., LinksBackupContributor.kt 6741bb0e..., RemindersBackupContributor.kt af4b1ffe..., SettingsBackupContributor.kt 912ad03d..., UserBackupContributor.kt 76b77dde..., CoreBackupContributorsTests.kt cef3133f..., CoreBackupFixtures.kt e8b05a4a..., BackupWebTests.kt 043fc817.... The hashes were identical before and after the runs.
- Exact included files; renamed/deleted/excluded files and reasons: `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/{BackupRows,LinksBackupContributor,RemindersBackupContributor,SettingsBackupContributor,UserBackupContributor}.kt`. There are no renames or deletions. All 5 files are in the report. The JaCoCo package node also lists `Comparisons.kt` and `BackupContributor.kt` with no counters. They are not task files (most likely inlined sources from other modules), so they are excluded.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin, with the project's configuration unchanged. Command `./gradlew test --rerun --tests 'ch.sonensei.cockpit.core.backupcontrib.*' --tests '*BackupRoundTripTest' --tests '*BackupWebTests' jacocoTestReport --console=plain -q`, run in `server/`. The selection was CoreBackupContributorsTests (11), BackupRoundTripTest (1) and BackupWebTests (12). `--rerun` forces a fresh test execution.
- Run start/completion time and exit status: 2026-10-20T23:07:57+02:00 to 23:08:54+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, mtime 23:08:54.00, sha256 `106b8cc216d3c7fb644fdda4340fe8c1ead4099c2b6ea69e30402c47cd86343e`. `server/build/jacoco/test.exec`, mtime 23:08:48. The later full `./gradlew check` run regenerated `test.exec` but not the XML. The numbers below come from the focused run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both artifact mtimes fall inside the run window, and the source hashes match before and after.
- Final run after latest test additions and production fixes: yes. The run measured d3620d37, which includes the fix commit 061a0337 and the warning-count tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/BackupRows.kt` | 70/70 (100.0%) | 4/4 (100.0%) | 6/6 (100.0%) | 6/6 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/LinksBackupContributor.kt` | 361/369 (97.8%) | 5/6 (83.3%) | 22/22 (100.0%) | 65/65 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/RemindersBackupContributor.kt` | 326/334 (97.6%) | 1/2 (50.0%) | 21/21 (100.0%) | 61/61 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/SettingsBackupContributor.kt` | 269/269 (100.0%) | 12/12 (100.0%) | 14/14 (100.0%) | 53/53 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/UserBackupContributor.kt` | 376/376 (100.0%) | 10/10 (100.0%) | 24/24 (100.0%) | 79/79 (100.0%) |
| Aggregate (summed counters, all 5 task files measured, none missing; package total from JaCoCo) | 1402/1418 (98.9%) | 32/34 (94.1%) | 87/87 (100.0%) | 264/264 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LinksBackupContributor.kt:30` and `RemindersBackupContributor.kt:29` (3 instructions, 1 branch each, on the `val text = out.bufferedWriter(...)` line inside the `writer.stream` lambda) | Compiler-generated bytecode of the inlined `bufferedWriter` (the already-buffered check in `writer()`/`buffered()`). It is not task behavior. | Non-material. The mirror contents themselves are fully tested. No test added. |
| `LinksBackupContributor.kt:84` and `RemindersBackupContributor.kt:83` (5 instructions each, the `.use { ... }` on the JDBC result stream) | The exception path of Kotlin `use {}`: close the stream when the consumer throws. Reaching it would need an injected database or stream failure in the middle of a stream. | Non-material. No test added. |

## Unable to test

- Restoring the TOTP secret from `data/secrets.enc.json` and the forced TOTP re-enrollment: out of scope by assumption #1. They are handed off to task `backup-secrets` (its criterion and note). This task's tests check only what it promises: no secret in the backup, a fresh working secret after import, and the operator warning.
- A full `BackupService.import` across all modules from the core tests: the shared test database holds other modules' data, so these tests import through the 4 core contributors only. `BackupRoundTripTest`, which has its own database, covers the full import.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No test files were changed in this pass. The focused tests (24), ktlint and the full server `./gradlew check` (2048 tests) pass on d3620d37. Task-scoped coverage: 100% of lines and methods, 98.9% of instructions, 94.1% of branches. The only gaps are compiler-generated paths.
