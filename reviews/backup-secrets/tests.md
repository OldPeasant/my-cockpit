# Test Report

Date: 2026-10-21

Task: backup-secrets — Backup secrets with passphrase

Scope: the whole task diff `git diff bc11781c..HEAD -- server api web` (base `bc11781c26c0fc960fe8b4a50507dc80e2ce1e0c` = main before the claim; the task commits came in through merge `bdc787f6` from `origin/wip/backup-secrets`, see `git log bc11781c..HEAD`). Measured HEAD: `bdc787f6953edac41a8423adff4aa3c27b5377c9` on `main`. Task ownership: the parent named the base and the merge, and the changed paths match the task's `touches`. The diff also matches the generated `api/openapi.yaml` and `web/src/app/api/generated/` files. `git diff --stat 56669f1f HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/backup server/src/main/kotlin/ch/sonensei/cockpit/core server/src/main/resources api web/src/app/api` is empty. So the production code of the task is identical to the reviewed revision `56669f1fbc7f4facbf5e29c0d7ac82ff87dc1ea3` (formerly 6fba2352). The only production difference between 56669f1f and HEAD is `files/backup/FilesBackupContributor.kt`, which came from task backup-contrib-files and is excluded. The working tree was clean before this run. The only uncommitted inputs are the two new test files listed below.

Requirements:
- tasks/backup-secrets.md: acceptance criteria 1-4, notes, and the recorded assumptions (password-only login when no recovery code is left, re-enrollment session handling, the account row lock)
- SPEC.md §10.2 (through the task)
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md
- reviews/backup-secrets/review.md (final re-review of 6fba2352, P3 CR-1/CR-2)
- parent instructions: review the existing tests against the criteria, add only what is meaningfully missing, run the backup, core.auth and core.backupcontrib tests, and measure task-scoped coverage

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretHidingTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/TotpEnrollmentSecretHidingTests.kt` (new)

Test files added earlier by test-writer runs and committed by the parent (not changed in this run) are `BackupSecretsCryptoTests`, `BackupSecretsZipTests`, `BackupSecretsTest`, the passphrase tests in `RestoreCliTests`, `TotpEnrollmentWebTests` (with the concurrency tests), `UserAccountTotpReenrollmentTests`, and the changes to `CoreBackupContributorsTests` / `CoreBackupFixtures`.

## Tests added or updated

Count: 4 (added in 2 new classes)

- `BackupSecretHidingTests`
    - `the backup and restore requests hide the passphrase`: `StartBackupRequest` and `ConfirmRestoreRequest`
    - `the user's backed-up secrets hide the TOTP secret`: `UserSecrets`
- `TotpEnrollmentSecretHidingTests`
    - `the response and the service result hide the new secret and its otpauth URI`: `TotpEnrollmentResponse`, `TotpEnrollment`
    - `the confirm request hides the TOTP code`: `ConfirmTotpEnrollmentRequest`

Why: the first coverage run showed that the `toString()` overrides of these task-added classes were never executed. Those overrides are the only thing that keeps the passphrase, the TOTP secret and the TOTP code out of logs and error messages (AGENTS.md: never log secrets). The project already tests this for the setup, login and recovery-code objects (`*SecretHidingTests`), and these tests follow the same pattern.

I reviewed the existing tests against the criteria. I found them thorough and added nothing else:
- the crypto envelope: round trip, parameters, fresh salt and nonce, tampering, bounds, wrong passphrase
- the zip writer and reader with and without a passphrase, corrupt or changed secrets files, and type and name errors
- the end-to-end export and restore with the TOTP secret, an OAuth token and a mail password probe
- reconnect-required and forced TOTP re-enrollment
- a wrong passphrase (422, nothing restored, upload kept, no pre-restore backup)
- passphrase validation on the API
- the CLI with and without a passphrase
- the re-enrollment endpoints: authorization, throttling, session ending, and the login/confirm, start/confirm and device-token/confirm races

The review's P3 CR-2 (no start/confirm serialization test) is covered now by `a start that runs while a confirm is not committed yet waits, then sees the confirm and cannot undo it` and `a confirm that runs while another session's start is not committed yet checks the code against the new secret` in `TotpEnrollmentWebTests`.

## Requirement coverage

Command for every row (the final run, with the new tests): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests 'ch.sonensei.cockpit.core.auth.*' --tests 'ch.sonensei.cockpit.core.backupcontrib.*' jacocoTestReport --rerun` → BUILD SUCCESSFUL, 398 tests, 0 failures, 0 errors, 0 skipped (JUnit XML in `server/build/test-results/test`). Postgres runs through Testcontainers (Podman socket) in this run.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. With a passphrase, secrets (TOTP secret, IMAP passwords, OAuth tokens) are exported to `data/secrets.enc.json` (Argon2id, AES-GCM); without, omitted | `BackupSecretsTest`: `with a passphrase the TOTP secret, OAuth tokens and mail passwords are only in secrets enc json, encrypted`, `without a passphrase the secrets are omitted`, `a restore with the passphrase brings the secrets back`, `a backup started with a passphrase through the API includes the encrypted secrets`, `a backup started without a body, with an empty body object or an empty passphrase has no secrets`, passphrase length tests. `BackupSecretsCryptoTests` (14 tests: envelope, KDF defaults, fresh salt and nonce, tamper detection, bounds). `BackupSecretsZipTests` (13 tests). `CoreBackupContributorsTests`: `the TOTP secret is in no file of the backup, neither in plain text nor encrypted`. New: `BackupSecretHidingTests`, `TotpEnrollmentSecretHidingTests` | above | Pass |
| 2. After restoring without secrets, accounts are flagged 'reconnect required' and the next login forces TOTP re-enrollment (test) | `BackupSecretsTest`: `after a restore without secrets accounts must reconnect and the next login forces TOTP re-enrollment`, `the restore report tells whether a backup has secrets and warns what is lost without the passphrase`, `an account still waiting for TOTP re-enrollment exports no TOTP secret and waits again after a restore with passphrase`. `RestoreCliTests`: `without the passphrase the CLI says how to restore the secrets and the owner must re-enroll TOTP`. `CoreBackupContributorsTests`: `after import the owner has a new decryptable TOTP secret, must re-enroll, and a recovery code logs in`. `TotpEnrollmentWebTests` (25 tests). `UserAccountTotpReenrollmentTests` | above | Pass |
| 3. Wrong passphrase → validation error, nothing restored | `BackupSecretsTest`: `a wrong passphrase is a validation error, restores nothing and keeps the upload`, `a TOTP secret in the secrets that is not Base32 is a validation error and restores nothing`, `a corrupt secrets file with a matching manifest is a validation error with the passphrase, and ignored without`. `RestoreCliTests`: `a wrong passphrase exits with 1, says so and changes nothing`. `BackupSecretsZipTests`: `a wrong passphrase fails open, before anything can be imported`. `BackupSecretsCryptoTests`: `a wrong passphrase is an InvalidBackupException too, so a restore reports it as a validation error` | above | Pass |
| 4. `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*'` passes (Postgres via Testcontainers) | all 14 `ch.sonensei.cockpit.backup.*` classes (incl. `BackupSecretsTest`, `RestoreCliTests`, `RestoreWebTests`, `BackupRoundTripTest`) | above (a superset of the criterion's filter, in the same Gradle test task) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.backup.BackupSecretHidingTests' --tests 'ch.sonensei.cockpit.core.auth.TotpEnrollmentSecretHidingTests'`

Result:

Pass (ktlint clean; 2 + 2 tests, 0 failures)

Commands for the scoped suites:

1. `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests 'ch.sonensei.cockpit.core.auth.*' --tests 'ch.sonensei.cockpit.core.backupcontrib.*' jacocoTestReport`, before the new tests → BUILD SUCCESSFUL in 5m 28s, 394 tests, 0 failures, 0 errors, 0 skipped
2. the same command with `--rerun`, after the new tests → BUILD SUCCESSFUL in 4m 51s, 398 tests, 0 failures, 0 errors, 0 skipped

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`: TFP gate tests, `server ./gradlew check` = ktlint plus the full server test suite, web `npm run check`, android ktlint, unit tests and lint)

Result:

Pass. The full server suite (2881 tests, 0 failures), web and Android ran inside `.taskcheck`, see below.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ran from 2026-10-21T05:45:25+02:00 to 05:57:04+02:00 with exit 0 and ended with "== all checks passed". The run included the two new test files. Steps:
- TFP gate tests: 20 tests OK
- server `./gradlew check`: ktlint plus the full server suite, 2881 tests, 0 failures, 0 errors, 0 skipped (JUnit XML)
- web: 79 files / 1435 tests passed, plus the build
- android: ktlint, unit tests and lint

The wall time was 11m39s. That is above the 2-minute target but below the 15-minute `TASK_CHECK_TIMEOUT`. Other agents' Gradle daemons were busy on the same machine during the run ("5 busy … Daemons").

## Bugs found

None. No test failed, and no production behavior was found to violate the acceptance criteria or the recorded assumptions.

## Coverage

Status: verified (fresh JaCoCo run on a stable snapshot, exit 0)

Metric: whole-file coverage of task-changed production files. This includes the older code in modified files and hits from tests outside this task, within the three test packages that ran.
Changed-line coverage: measured separately. Method: the added lines of `git diff -U0 bc11781c..HEAD` per scoped file, intersected with the lines JaCoCo reports as executable in the same report. Removed lines and non-executable lines are not counted. Result: **375/382 lines (98.2%)**. JaCoCo branch counters on those changed lines: 214/226 (94.7%). That is line-level branch data and not a precise changed-branch metric.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: backup-secrets. Base `bc11781c26c0fc960fe8b4a50507dc80e2ce1e0c`, HEAD `bdc787f6953edac41a8423adff4aa3c27b5377c9`. The task commits are those of `git log bc11781c..HEAD` (merge of `origin/wip/backup-secrets`: bd6be796, e2d0734d, a5046e16, 3f4d911e, 0c3a5e79, 56669f1f plus test, review and task commits). The file scope is `git diff --name-only bc11781c..HEAD -- server/src/main`, checked against the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `bdc787f6` with a clean tracked tree (`git diff --quiet HEAD -- server/src/main` true), plus the untracked `BackupSecretHidingTests.kt` (sha256 `21f6ad46fc13cf666594309522e9f50d4687ebdd16fa5ba8e71bb69c07c921d4`) and `TotpEnrollmentSecretHidingTests.kt` (sha256 `a943391ffde93bb88c4108148ae1df3b8778c76c728e0236195d9850c58e7d89`). Both hashes match before and after the run.
- Exact included files: the 25 Kotlin files listed in the table. Excluded: `server/src/main/resources/db/migration/core/V202610210100__core_auth_totp_reenroll.sql` (SQL migration, not instrumented by JaCoCo; it is executed by Flyway in every Spring test above). Also excluded: the generated `api/openapi.yaml` and `web/src/app/api/generated/**` (generated clients; no web code of this task uses them yet, see task web-totp-reenroll). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default plugin version of Gradle 9.8.1), JDK 21.0.12.1. Command `./gradlew test --tests 'ch.sonensei.cockpit.backup.*' --tests 'ch.sonensei.cockpit.core.auth.*' --tests 'ch.sonensei.cockpit.core.backupcontrib.*' jacocoTestReport --rerun` in `server/`. The selection is the three packages the task touches. The coverage is from those tests only, not from the whole server suite.
- Run start/completion time and exit status: 2026-10-21T05:40:10+02:00 – 05:45:02+02:00, exit 0 (BUILD SUCCESSFUL), `test` executed (not up to date).
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (written 05:44:52), `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 05:45:02, sha256 `6951512068ecd994d202b404c5353c334ba4755e65e768d74c751974f2907ef8`). Both are Git-ignored.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both artifacts were written inside the run window, the test count (398) includes the 4 new tests, and the snapshot hashes were unchanged after the run.
- Final run after latest test additions and production fixes: yes. There were no production changes, and the run includes the new tests.

Values below use covered/total (percentage) from the JaCoCo XML `<sourcefile>` counters. Zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters (JaCoCo has no statement counter).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `backup/api/BackupController.kt` | 218/245 (89.0%) | 8/8 (100.0%) | 19/20 (95.0%) | 60/61 (98.4%) |
| `backup/api/RestoreCli.kt` | 269/295 (91.2%) | 21/26 (80.8%) | 4/4 (100.0%) | 41/42 (97.6%) |
| `backup/api/RestoreController.kt` | 208/220 (94.5%) | 6/8 (75.0%) | 25/26 (96.2%) | 50/51 (98.0%) |
| `backup/domain/BackupContributor.kt` | 204/209 (97.6%) | 14/14 (100.0%) | 14/17 (82.4%) | 29/32 (90.6%) |
| `backup/domain/BackupFormat.kt` | 201/201 (100.0%) | 18/18 (100.0%) | 16/16 (100.0%) | 26/26 (100.0%) |
| `backup/domain/BackupJobService.kt` | 631/653 (96.6%) | 18/20 (90.0%) | 35/35 (100.0%) | 80/80 (100.0%) |
| `backup/domain/BackupSecrets.kt` | 482/482 (100.0%) | 40/40 (100.0%) | 26/26 (100.0%) | 66/66 (100.0%) |
| `backup/domain/BackupService.kt` | 614/625 (98.2%) | 3/4 (75.0%) | 24/25 (96.0%) | 92/94 (97.9%) |
| `backup/domain/RestoreService.kt` | 862/919 (93.8%) | 46/54 (85.2%) | 35/36 (97.2%) | 135/143 (94.4%) |
| `backup/infra/ZipBackupReader.kt` | 976/987 (98.9%) | 89/90 (98.9%) | 26/26 (100.0%) | 156/158 (98.7%) |
| `backup/infra/ZipBackupWriter.kt` | 625/628 (99.5%) | 31/32 (96.9%) | 37/37 (100.0%) | 95/95 (100.0%) |
| `core/auth/api/AuthController.kt` | 354/354 (100.0%) | 31/32 (96.9%) | 19/19 (100.0%) | 49/49 (100.0%) |
| `core/auth/api/DeviceTokenController.kt` | 439/439 (100.0%) | 32/32 (100.0%) | 36/36 (100.0%) | 75/75 (100.0%) |
| `core/auth/api/TotpEnrollmentController.kt` | 134/134 (100.0%) | 4/4 (100.0%) | 10/10 (100.0%) | 32/32 (100.0%) |
| `core/auth/domain/DeviceTokenService.kt` | 397/400 (99.2%) | 17/18 (94.4%) | 26/26 (100.0%) | 67/67 (100.0%) |
| `core/auth/domain/LoginService.kt` | 716/722 (99.2%) | 60/60 (100.0%) | 45/47 (95.7%) | 103/103 (100.0%) |
| `core/auth/domain/SetupService.kt` | 310/313 (99.0%) | 25/26 (96.2%) | 16/16 (100.0%) | 50/50 (100.0%) |
| `core/auth/domain/TotpEnrollmentService.kt` | 200/200 (100.0%) | 18/18 (100.0%) | 10/10 (100.0%) | 33/33 (100.0%) |
| `core/auth/domain/UserAccount.kt` | 178/189 (94.2%) | 16/16 (100.0%) | 23/26 (88.5%) | 38/39 (97.4%) |
| `core/auth/infra/AuthSessionRepository.kt` | N/A (interface, no bytecode in the report) | N/A | N/A | N/A |
| `core/auth/infra/SecurityConfiguration.kt` | 309/311 (99.4%) | 2/4 (50.0%) | 11/11 (100.0%) | 44/44 (100.0%) |
| `core/auth/infra/SessionAuthenticationFilter.kt` | 104/106 (98.1%) | 14/14 (100.0%) | 5/6 (83.3%) | 23/24 (95.8%) |
| `core/auth/infra/UserAccountRepository.kt` | N/A (interface, no bytecode in the report) | N/A | N/A | N/A |
| `core/backupcontrib/OAuthBackupContributor.kt` | 370/372 (99.5%) | 29/30 (96.7%) | 17/17 (100.0%) | 68/68 (100.0%) |
| `core/backupcontrib/UserBackupContributor.kt` | 574/579 (99.1%) | 43/46 (93.5%) | 29/29 (100.0%) | 105/105 (100.0%) |
| **Aggregate (summed counters, 23 measured files)** | **9375/9583 (97.8%)** | **585/614 (95.3%)** | **508/521 (97.5%)** | **1517/1537 (98.7%)** |

Paths are relative to `server/src/main/kotlin/ch/sonensei/cockpit/`. The aggregate excludes the two repository interfaces (Spring Data proxies; their queries run in `TotpEnrollmentWebTests` and the login tests) and the SQL migration. No scoped file is missing from the report.

Before the new tests (first run, report sha256 `4d88a14a…`), the aggregate was 9363/9583 instructions and 1511/1537 lines. The uncovered lines were `toString()` in `BackupController.kt:154`, `RestoreController.kt:132`, `TotpEnrollmentController.kt:127,136`, `TotpEnrollmentService.kt:86` and `UserBackupContributor.kt:164`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BackupController.kt:154`, `RestoreController.kt:132`, `TotpEnrollmentController.kt:127,136`, `TotpEnrollmentService.kt:86`, `UserBackupContributor.kt:164` (first run) | secrets (passphrase, TOTP secret, TOTP code) must not reach logs through `toString` | tests added: `BackupSecretHidingTests`, `TotpEnrollmentSecretHidingTests`. All lines are covered now |
| `RestoreService.kt:227-229, 240-250` and partial branches 226, 237, 247, 261 | failing to stash or unstash the data directory, and an empty table list in the wipe | not from this task (from backup-restore, already in the base). Left uncovered |
| `RestoreService.kt:175,177`, `BackupService.kt:130,132`, `RestoreCli.kt:34-35`, `RestoreController.kt:131`, `BackupController.kt:184` | Kotlin default-argument bridges (`passphrase: String? = null`). `RestoreCli.kt:34-35` reads `COCKPIT_RESTORE_PASSPHRASE` from the real environment as a default argument | non-material. The tests pass the passphrase explicitly. Reading the environment variable would need the process environment changed, which the project's tests do not do. Justified gap |
| `BackupContributor.kt:68,78,128` | the default no-op `includesSecrets` / `secret()` of the `BackupDataWriter`/`BackupDataReader` interfaces | non-material: the real writer and reader override them and are fully tested |
| `ZipBackupReader.kt:203-204` | an `IOException` while reading a JSON entry turns into an invalid backup | hard to trigger with a valid `ZipFile`. The JSON-error branch next to it is covered. Justified gap |
| `RestoreService.kt:131,195,201`, `ZipBackupReader.kt:106`, `ZipBackupWriter.kt:33`, `OAuthBackupContributor.kt:50`, `UserBackupContributor.kt:66`, `SecurityConfiguration.kt:125`, `AuthController.kt:98`, `DeviceTokenService.kt:110`, `SetupService.kt:84`, `BackupJobService.kt:108,159` (partial branches) | Kotlin null-safety and elvis branches, short-circuit halves | inspected. None is a missing acceptance-criterion path: wrong passphrase, missing secrets, no tokens and the restricted session are all covered. Left uncovered |
| `UserAccount.kt:76`, `SessionAuthenticationFilter.kt:62` | JPA entity/filter members that are not used by these tests | not changed behavior of the task. Left uncovered |

## Unable to test

- Web or Android UI for the forced re-enrollment: no client exists yet. The code review moved it to task `web-totp-reenroll`, so it is outside this task.
- `RestoreCli` reading the passphrase from the `COCKPIT_RESTORE_PASSPHRASE` environment variable (`RestoreCli.kt:34`): tests would have to change the process environment. The passphrase handling itself is tested through the `passphrase` parameter. No follow-up needed.
- Review P3 CR-1 (`LoginService.verify` relies on the caller's transaction): this is a structural invariant, not observable behavior of the current callers. Both callers are covered by the concurrency tests in `TotpEnrollmentWebTests`. A test for a hypothetical future caller without a transaction is not meaningful.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient (whole-file lines 1517/1537 = 98.7%, changed lines 375/382 = 98.2%), and no production defects were found.
