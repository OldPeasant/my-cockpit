# Fix Report

Date: 2026-10-20

Task: backup-contrib-core - Backup contributors: core entities

Scope: current task. Findings TW-1 (reviews/backup-contrib-core/tests.md), CR-1 interim code part and CR-2 (reviews/backup-contrib-core/review.md), as approved by the parent worker.

## Fixed findings

### TW-1 — BackupWebTests hard-codes the export step count of a single contributor

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWebTests.kt`

Problem:
`POST starts a backup that completes and downloads as a zip` asserted `stepsDone`/`stepsTotal == 3`. That is correct only when the test contributor is the only one. The four core contributors added by this task make the real count `contributors * 2 + 1`.

Fix:
Both assertions now compare against `backups.exportSteps` (the injected `BackupService`), the same value `BackupJobService` reports. The assertion is not weakened: it still checks the exact step count of a completed job.

Validation:
- `./gradlew test --tests '*BackupWebTests'` (with the core backupcontrib and round-trip tests) — Pass (12/12)

### CR-1 — Restored account has an unknown TOTP secret (interim part only)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/backupcontrib/UserBackupContributor.kt`

Problem:
`import` writes a fresh random TOTP secret and keeps `totp_confirmed_at`, so the account is active but nobody knows the secret. The owner can sign in only with recovery codes, and is locked out if the backup holds none.

Fix:
After it inserts an active account (`totpConfirmedAt != null`), `import` logs a WARN through SLF4J (`LoggerFactory`, as the core auth services do). The message says the TOTP secret was not restored, and that the owner must sign in with a recovery code (with the count of unused codes) and re-enroll TOTP. When no unused recovery code is left, a separate WARN says the owner cannot sign in until TOTP is re-enrolled. Neither message contains the username, secrets or hashes. The class KDoc describes this interim limitation and names `backup-secrets` as the owner of the forced re-enrollment. No endpoint or auth code was changed. The tracked hand-off to `backup-secrets` (criterion 2) is recorded by the parent worker, as agreed. It is not part of this fix.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.core.backupcontrib.*'` — Pass (9/9, including the new warning assertions)

### CR-2 — Import-only behavior and readable mirrors had no tests

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/core/backupcontrib/CoreBackupContributorsTests.kt`

Problem:
The round trip compares only `data/`. The test-writer's CoreBackupContributorsTests already covers import-only behavior and the mirrors. The CR-1 warning path had no test.

Fix:
The class now uses `OutputCaptureExtension` (as `SetupEdgeCaseTests` does). The existing import test asserts that the re-enrollment WARN is logged and that the output contains neither the old TOTP secret nor the username. A new test, `importing an owner without unused recovery codes warns that the owner is locked out`, marks every recovery code as used, then exports, empties the core tables, imports, and asserts the lockout warning. `ktlintFormat` also re-wrapped three existing chained calls in this file. That change is formatting only.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.core.backupcontrib.*'` — Pass (9/9)

## Not fixed

### CR-1 — Tracked hand-off of forced TOTP re-enrollment to backup-secrets

Status:
Out of scope

Reason:
Changing `tasks/backup-secrets.md` (dependency, touches, note) is TFP plan work. The parent worker records it. Re-enrollment endpoints and auth changes belong to `backup-secrets` criterion 2.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.backupcontrib.*' --tests '*BackupWebTests' --tests '*BackupRoundTripTest'` — Pass (CoreBackupContributorsTests 9/9, BackupWebTests 12/12, BackupRoundTripTest 1/1)

Full test suite:
- `cd server && ./gradlew check` — BUILD SUCCESSFUL (4m 19s). 2046 tests, 0 failures, 0 errors, 0 skipped (from build/test-results XML). ktlint main and test source sets pass.

Lint/type-check/build:
- Included in `./gradlew check` (ktlint, compile) — Pass

`.taskcheck`:
- Not run by the fixer. Only the server part is affected, and its `./gradlew check` passed. The parent worker runs `sh scripts/check.sh` before `done`.

Remaining unresolved findings:
0 in code. The CR-1 hand-off is recorded by the parent worker.

## Final status

All validated findings fixed and validation passed.
