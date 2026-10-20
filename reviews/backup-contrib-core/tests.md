# Test Report

Date: 2026-10-20

Task: backup-contrib-core - Backup contributors: core entities

Scope: task commit 0bf2a50b94558ed5a43967813a6e66e1b3efae3a on base a8bc9af4a560dd82724d89669dac06c0d9dd5bdc (`git log a8bc9af4..HEAD` = only 0bf2a50b), branch main. Production files: the 5 files in `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/`. Plus the new, uncommitted test file listed below.

Requirements:
- `tasks/backup-contrib-core.md` acceptance criteria
- the task's 4 recorded assumptions (`python tasks.py assumptions`): no TOTP secret in `user.json` and a fresh secret on import; settings in their own `data/settings.json`; sessions and devices not backed up; JDBC instead of JPA
- SPEC.md §10.2 (via the contributor KDoc and `BackupContributor` contract), docs/ARCHITECTURE.md "Coverage"
- parent instructions (which behavior to focus on; stay inside `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/CoreBackupContributorsTests.kt` (new)

## Tests added or updated

Count: 8

All 8 are Spring Boot + Testcontainers Postgres tests (`PostgresTestBase`). Their context matches `LoginEdgeCaseTests` (`@AutoConfigureMockMvc`, `MutableClockTestConfiguration`), so no extra connection pool. Exports go through the real `BackupService`. Imports run only the 4 core contributors, in dependency order, through the real `ZipBackupReader`, after the core tables are emptied.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| User contributor: TOTP not exported in plain text (assumption #1, SPEC §10.2 secrets only via the secrets mechanism) | `the TOTP secret is in no file of the backup, neither in plain text nor encrypted` (checks the Base32, lower-case, Base64, hex and stored ciphertext forms against every zip entry; also checks that `user.json` has exactly the expected properties) | `cd server && ./gradlew test --tests '*CoreBackupContributorsTests' --tests '*BackupRoundTripTest'` | Pass |
| User contributor: on import the user gets a new, decryptable TOTP secret. Password hash and recovery codes survive. The restored user is found by the auth repository | `after import the owner has a new decryptable TOTP secret, the old one no longer works, and a recovery code does` (`UserAccountRepository.findFirstBy`, `SecretCipher.decrypt`, `LoginService.verify`: old TOTP rejected, recovery code and new TOTP accepted) | same | Pass |
| User/settings contributors: an empty install exports no `user.json`/`settings.json`. Importing a backup without them is a no-op | `an install without owner and settings exports no user or settings file, and importing it creates neither` | same | Pass |
| Settings contributor: restored settings are readable via `SettingsService` and keep their version (ETag) | `restored settings are read through the settings service with their version` | same | Pass |
| Readable section `readable/settings.md`, incl. Markdown escaping | `the settings mirror lists the changed settings sorted by key with Markdown table cells escaped`, plus the defaults text in the empty-install test | same | Pass |
| Readable section `readable/links.md`, incl. escaping of `\|`, `\`, `\n`, `\r\n` and a null label | `the links mirror lists every link in id order with type-prefixed ids and an escaped label` | same | Pass |
| Readable section `readable/reminders.md`: pending only, earliest first. Fired reminders stay in `data/` | `the reminders mirror lists only pending reminders, earliest first, in UTC` | same | Pass |
| Mirrors with no links/reminders | `empty links and reminders give mirrors with only their table header` | same | Pass |
| AC2: round trip passes with this module's `BackupFixture`s | existing `BackupRoundTripTest` (unchanged) | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*CoreBackupContributorsTests' --tests '*BackupRoundTripTest'`

Result:

Pass. JUnit XML: CoreBackupContributorsTests has 8 tests, 0 failures, 0 errors, 0 skipped. BackupRoundTripTest has 1 test, 0 failures.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --console=plain` (ktlint main and test source sets, then the full server test suite)

Result:

Fail. 2045 tests completed, 1 failed: `BackupWebTests > POST starts a backup that completes and downloads as a zip()`. ktlint passed, including the new test file. See TW-1. It is caused by this task, but the cause is the stale expectation in an existing test, not wrong production behavior.

## .taskcheck

Result:

Not run. The server part of `scripts/check.sh` runs `./gradlew check`, which fails on TW-1 (see above). Run `.taskcheck` again once TW-1 is fixed.

## Bugs found

### Existing BackupWebTests hard-codes the export step count of a single contributor

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.backup.BackupWebTests` > `POST starts a backup that completes and downloads as a zip` (existing test)

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/*BackupContributor.kt`. The task adds 4 `@Component` contributors. The failing assertions are in `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt:147-148`.

Requirement:

The project check (`./gradlew check`, `.taskcheck`) must pass before `tasks.py done`. Export steps are `contributors * 2 + 1` (`BackupService.exportSteps`).

Expected behavior:

`stepsDone`/`stepsTotal` of a completed job equal `BackupService.exportSteps`: 11 with the 5 contributors now in the context.

Actual behavior:

`JSON path "$.stepsDone" expected:<3> but was:<11>`. The test was written when only its test contributor existed: 1 * 2 + 1 = 3.

Severity:

High. It blocks `.taskcheck` and `done`. Production behavior is correct.

Fix required:

`Yes. The parent worker / fixer must fix the stale assertion at BackupWebTests.kt:147-148, e.g. expect backups.exportSteps; the BackupService bean is already injected there. Production code does not need to change. The test file is outside this task's touches and outside the test-writer's allowed folder, so it was not edited here.`

No production defects were found in the contributors themselves.

## Coverage

Status: verified (task-focused run, exit 0)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage here. All 5 files are new in 0bf2a50b (`git diff --stat a8bc9af4..HEAD`: additions only), so every executable line in them is a changed line. No separate intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-contrib-core. Base a8bc9af4a560dd82724d89669dac06c0d9dd5bdc, HEAD 0bf2a50b94558ed5a43967813a6e66e1b3efae3a. The only task commit is 0bf2a50b, as given by the parent and confirmed by `git log a8bc9af4..HEAD`. It matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0bf2a50b plus the untracked `CoreBackupContributorsTests.kt`, sha256 `183825e80e804c245e5f14b91019d94942da78976694b0e0667ed0ca56ab3750`. `CoreBackupFixtures.kt` is committed, sha256 `e8b05a4a...`. The production file hashes were identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: `BackupRows.kt`, `LinksBackupContributor.kt`, `RemindersBackupContributor.kt`, `SettingsBackupContributor.kt`, `UserBackupContributor.kt` (package `ch/sonensei/cockpit/core/backupcontrib`). Excluded: `CoreBackupFixtures.kt` (test code). No renames or deletions. All 5 files are present in the report.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (project config, unchanged). Command `./gradlew test --tests 'ch.sonensei.cockpit.core.backupcontrib.*' --tests '*BackupRoundTripTest' jacocoTestReport --console=plain -q`, run in `server/`. Test selection: CoreBackupContributorsTests (8 tests) and BackupRoundTripTest (1 test). The full suite was not used for coverage because it currently fails on TW-1.
- Run start/completion time and exit status: start 2026-10-20T22:48:18+02:00, finish 22:49:09+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 22:49:06), sha256 `01cadc05e270b2485cfd71a0558b3593a08c4622112ce90d121ecae69003c0dd`. `server/build/jacoco/test.exec` mtime 22:49:03.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report and exec mtimes fall inside the run window, and the source hashes match before and after.
- Final run after latest test additions and production fixes: yes for the tests. No production fix has been made yet. TW-1 only needs a change in a test outside the scope, which does not change the coverage of these files.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/BackupRows.kt` | 70/70 (100.0%) | 4/4 (100.0%) | 6/6 (100.0%) | 6/6 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/LinksBackupContributor.kt` | 361/369 (97.8%) | 5/6 (83.3%) | 22/22 (100.0%) | 65/65 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/RemindersBackupContributor.kt` | 326/334 (97.6%) | 1/2 (50.0%) | 21/21 (100.0%) | 61/61 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/SettingsBackupContributor.kt` | 269/269 (100.0%) | 12/12 (100.0%) | 14/14 (100.0%) | 53/53 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/UserBackupContributor.kt` | 339/339 (100.0%) | 4/4 (100.0%) | 23/23 (100.0%) | 70/70 (100.0%) |
| Aggregate (summed counters, all 5 files measured, none missing or excluded) | 1365/1381 (98.8%) | 26/28 (92.9%) | 86/86 (100.0%) | 255/255 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LinksBackupContributor.kt:30,84`, `RemindersBackupContributor.kt:29,83` (partial instructions/branch) | Compiler-generated exception path of Kotlin `use {}` on the JDBC result stream (close after a failing consumer) and of the buffered writer | Non-material: this is not task behavior, and it would only be reachable by injecting a database or stream failure in the middle of a stream. No test added. |

## Unable to test

- Re-enrollment of TOTP after a restore and secrets in `data/secrets.enc.json`: out of scope. Assumption #1 defers both to task `backup-secrets`. The tests only check what this task promises: no secret in the backup, and a new working secret after import.
- A full `BackupService.import` across all modules from the core tests: the shared test database holds other modules' data, so these tests import only through the 4 core contributors. The full import is covered by `BackupRoundTripTest`, which has its own database.

## Summary

Tests added; TW-1 must go to the fixer before task completion. It is an existing test (`BackupWebTests`) with a stale step-count expectation, broken by the new contributors and blocking `./gradlew check` / `.taskcheck`. No defects were found in the contributors themselves. All 8 new tests and the round trip pass, and task-scoped coverage is 100% of lines and methods.
